package com.phonecam;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

public class StreamServer extends NanoWSD {

    private static final String TAG = "StreamServer";
    public static final int HTTP_PORT = 8080;

    private final Set<ViewerSocket> viewers = Collections.synchronizedSet(new HashSet<>());
    private volatile byte[] lastFrame = null;
    private final OnViewerCountChanged listener;
    private final CameraStreamService service;

    public interface OnViewerCountChanged {
        void onCountChanged(int count);
    }

    public StreamServer(Context ctx, OnViewerCountChanged listener) {
        super(HTTP_PORT);
        this.listener = listener;
        this.service  = (CameraStreamService) ctx;
    }

    // ── HTTP routing ─────────────────────────────────────────────────────────

    @Override
    public Response serve(IHTTPSession session) {
        // WebSocket upgrade — let super handle it
        Map<String, String> headers = session.getHeaders();
        if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
            return super.serve(session);
        }

        String uri = session.getUri();
        Method method = session.getMethod();

        // Settings API — called from browser
        if ("/api/settings".equals(uri) && method == Method.POST) {
            return handleSettingsPost(session);
        }

        // Status API
        if ("/api/status".equals(uri)) {
            int[] res = CameraStreamService.RESOLUTIONS[service.getResIdx()];
            String json = "{\"quality\":" + service.getQuality()
                + ",\"res\":" + service.getResIdx()
                + ",\"facing\":" + service.getFacing()
                + ",\"viewers\":" + viewers.size()
                + ",\"resW\":" + res[0]
                + ",\"resH\":" + res[1]
                + "}";
            return newFixedLengthResponse(Response.Status.OK, "application/json", json);
        }

        // Default — serve viewer page
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", getViewerHtml());
    }

    private Response handleSettingsPost(IHTTPSession session) {
        try {
            Map<String, String> body = new java.util.HashMap<>();
            session.parseBody(body);
            String postData = body.get("postData");
            if (postData == null || postData.isEmpty()) {
                // Try reading from files map
                Map<String, String> params = session.getParms();
                String action = params.get("action");
                if (action != null) postData = "{\"action\":\"" + action + "\"}";
            }

            if (postData != null) {
                if (postData.contains("\"action\":\"quality\"")) {
                    int val = extractInt(postData, "value", 50);
                    service.setQualityFromBrowser(val);
                } else if (postData.contains("\"action\":\"res\"")) {
                    int val = extractInt(postData, "value", 1);
                    service.setResFromBrowser(val);
                } else if (postData.contains("\"action\":\"switchCam\"")) {
                    service.switchCameraFromBrowser();
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"ok\":false}");
        }
    }

    private int extractInt(String json, String key, int def) {
        try {
            int idx = json.indexOf("\"" + key + "\":");
            if (idx < 0) return def;
            String rest = json.substring(idx + key.length() + 3).trim();
            StringBuilder num = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c) || c == '-') num.append(c);
                else if (num.length() > 0) break;
            }
            return num.length() > 0 ? Integer.parseInt(num.toString()) : def;
        } catch (Exception e) { return def; }
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ViewerSocket(handshake);
    }

    public void broadcastFrame(byte[] jpeg) {
        lastFrame = jpeg;
        synchronized (viewers) {
            Set<ViewerSocket> dead = new HashSet<>();
            for (ViewerSocket ws : viewers) {
                if (!ws.offer(jpeg)) dead.add(ws);
            }
            if (!dead.isEmpty()) {
                viewers.removeAll(dead);
                notifyCount();
            }
        }
    }

    public int getViewerCount() { return viewers.size(); }

    private void notifyCount() {
        if (listener != null) listener.onCountChanged(viewers.size());
    }

    // ── ViewerSocket — each viewer gets a dedicated send queue ───────────────

    private class ViewerSocket extends WebSocket {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(4);
        private Thread sendThread;
        private volatile boolean open = false;

        ViewerSocket(IHTTPSession hs) { super(hs); }

        /** Offer frame to queue — drop if full (backpressure) */
        boolean offer(byte[] frame) {
            if (!open) return false;
            queue.poll(); // remove oldest if full to keep latency low
            queue.offer(frame);
            return open;
        }

        @Override
        protected void onOpen() {
            open = true;
            viewers.add(this);
            notifyCount();
            Log.d(TAG, "Viewer connected, total: " + viewers.size());

            // Send last frame immediately
            if (lastFrame != null) queue.offer(lastFrame);

            // Dedicated send thread per viewer — avoids blocking broadcast loop
            sendThread = new Thread(() -> {
                while (open) {
                    try {
                        byte[] frame = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                        if (frame != null && open) {
                            send(frame);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "ViewerSend");
            sendThread.setDaemon(true);
            sendThread.start();
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            open = false;
            viewers.remove(this);
            if (sendThread != null) sendThread.interrupt();
            notifyCount();
            Log.d(TAG, "Viewer disconnected, total: " + viewers.size());
        }

        @Override protected void onMessage(WebSocketFrame msg) {
            // Browser may send settings via WebSocket too
            try {
                String text = msg.getTextPayload();
                if (text != null && text.startsWith("{")) {
                    if (text.contains("\"action\":\"quality\"")) {
                        int val = extractInt(text, "value", 50);
                        service.setQualityFromBrowser(val);
                    } else if (text.contains("\"action\":\"res\"")) {
                        int val = extractInt(text, "value", 1);
                        service.setResFromBrowser(val);
                    } else if (text.contains("\"action\":\"switchCam\"")) {
                        service.switchCameraFromBrowser();
                    }
                }
            } catch (Exception ignored) {}
        }

        @Override protected void onPong(WebSocketFrame pong) {}
        @Override protected void onException(IOException e) {
            open = false;
            viewers.remove(this);
            if (sendThread != null) sendThread.interrupt();
            notifyCount();
        }
    }

    // ── Viewer HTML ───────────────────────────────────────────────────────────

    private String getViewerHtml() {
        return "<!DOCTYPE html><html><head>" +
        "<meta charset='UTF-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>" +
        "<title>PhoneCam</title>" +
        "<style>" +
        "*{margin:0;padding:0;box-sizing:border-box}" +
        "body{background:#0a0a0f;color:#e0e0f0;font-family:'Courier New',monospace;display:flex;flex-direction:column;height:100dvh;overflow:hidden}" +
        "header{display:flex;align-items:center;padding:8px 14px;gap:10px;background:#12121a;border-bottom:1px solid #1e1e2e;flex-shrink:0}" +
        ".logo{font-size:11px;letter-spacing:4px;color:#5fffb0;font-weight:bold}" +
        ".sep{flex:1}" +
        "#st{font-size:10px;letter-spacing:2px;color:#555570}" +
        ".dot{width:7px;height:7px;border-radius:50%;background:#555570}" +
        ".dot.live{background:#5fffb0;box-shadow:0 0 8px rgba(95,255,176,.6);animation:pulse 2s infinite}" +
        ".dot.wait{background:#ffcc44}" +
        ".dot.err{background:#ff5f5f}" +
        "@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(95,255,176,.5)}70%{box-shadow:0 0 0 7px rgba(95,255,176,0)}100%{box-shadow:0 0 0 0 rgba(95,255,176,0)}}" +
        "#wrap{flex:1;background:#000;display:flex;align-items:center;justify-content:center;position:relative;overflow:hidden;cursor:pointer}" +
        "#img{max-width:100%;max-height:100%;object-fit:contain;opacity:0;transition:opacity .3s}" +
        "#img.show{opacity:1}" +
        "#ph{position:absolute;display:flex;flex-direction:column;align-items:center;gap:10px;color:#44445a;font-size:11px;letter-spacing:3px;pointer-events:none}" +
        ".ph-icon{font-size:36px;opacity:.3}" +
        // Controls panel
        "#ctrl{background:#12121a;border-top:1px solid #1e1e2e;padding:10px 14px;flex-shrink:0;display:none}" +
        "#ctrl.show{display:block}" +
        ".ctrl-row{display:flex;align-items:center;gap:10px;margin-bottom:8px}" +
        ".ctrl-row:last-child{margin-bottom:0}" +
        ".ctrl-lbl{font-size:9px;letter-spacing:2px;color:#555570;text-transform:uppercase;width:70px;flex-shrink:0}" +
        ".ctrl-val{font-size:11px;color:#5fffb0;width:36px;text-align:right;flex-shrink:0}" +
        "input[type=range]{flex:1;accent-color:#5fffb0;height:3px}" +
        ".btn-group{display:flex;gap:5px;flex:1}" +
        ".rbtn{flex:1;padding:5px;background:#1e1e2e;border:1px solid #2a2a3e;border-radius:3px;color:#888;font-family:'Courier New',monospace;font-size:10px;cursor:pointer;transition:all .15s}" +
        ".rbtn.on{background:rgba(95,255,176,.15);border-color:#5fffb0;color:#5fffb0}" +
        ".rbtn:active{transform:scale(.96)}" +
        // Stats bar
        "footer{display:flex;gap:6px;padding:6px 14px;background:#0e0e18;border-top:1px solid #1a1a2a;flex-shrink:0}" +
        ".stat{flex:1;text-align:center}" +
        ".sv{font-size:16px;font-weight:bold;color:#5bc8ff;display:block}" +
        ".sl{font-size:8px;color:#44445a;letter-spacing:2px;text-transform:uppercase}" +
        "</style></head><body>" +
        "<header>" +
        "<span class='logo'>PHONECAM</span><span class='sep'></span>" +
        "<span id='st'>CONNECTING</span>" +
        "<span class='dot' id='dot'></span>" +
        "</header>" +
        "<div id='wrap' onclick='toggleCtrl()'>" +
        "<div id='ph'><div class='ph-icon'>📷</div><span>Tap to show controls</span></div>" +
        "<img id='img' alt=''>" +
        "</div>" +
        "<div id='ctrl'>" +
        // Quality
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Quality</span>" +
        "<input type='range' id='sQ' min='10' max='95' value='50' oninput='onQ(this.value)'>" +
        "<span class='ctrl-val' id='vQ'>50%</span>" +
        "</div>" +
        // Resolution
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Resolution</span>" +
        "<div class='btn-group'>" +
        "<button class='rbtn' id='r0' onclick='setRes(0)'>480p</button>" +
        "<button class='rbtn on' id='r1' onclick='setRes(1)'>854p</button>" +
        "<button class='rbtn' id='r2' onclick='setRes(2)'>720p</button>" +
        "</div>" +
        "</div>" +
        // Camera switch + fullscreen
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Camera</span>" +
        "<button class='rbtn' id='btnCam' onclick='switchCam()' style='flex:none;padding:5px 14px'>↺ Switch</button>" +
        "<span class='sep' style='flex:1'></span>" +
        "<button class='rbtn' onclick='toggleFs()' style='flex:none;padding:5px 14px'>⛶ Full</button>" +
        "</div>" +
        "</div>" +
        "<footer>" +
        "<div class='stat'><span class='sv' id='fps'>0</span><span class='sl'>FPS</span></div>" +
        "<div class='stat'><span class='sv' id='res'>—</span><span class='sl'>RES</span></div>" +
        "<div class='stat'><span class='sv' id='kb'>0</span><span class='sl'>KB/s</span></div>" +
        "</footer>" +
        "<script>" +
        "var ws,img=document.getElementById('img'),ph=document.getElementById('ph')," +
        "dot=document.getElementById('dot'),st=document.getElementById('st')," +
        "fps=0,kb=0,lastFrame=0,ctrlVisible=false,qTimer=null,curRes=1;" +

        // Stats update
        "setInterval(function(){" +
        "document.getElementById('fps').textContent=fps;" +
        "document.getElementById('kb').textContent=Math.round(kb/1024);" +
        "fps=0;kb=0;" +
        "if(lastFrame&&Date.now()-lastFrame>4000&&img.classList.contains('show')){" +
        "st.textContent='STALLED';dot.className='dot wait';}" +
        "},1000);" +

        // Load settings from server
        "function loadSettings(){" +
        "fetch('/api/status').then(r=>r.json()).then(d=>{" +
        "var q=d.quality||50; document.getElementById('sQ').value=q;" +
        "document.getElementById('vQ').textContent=q+'%';" +
        "curRes=d.res||1; highlightRes(curRes);" +
        "document.getElementById('res').textContent=(d.resW||'?')+'x'+(d.resH||'?');" +
        "}).catch(function(){});" +
        "}" +

        // WebSocket connect
        "function connect(){" +
        "st.textContent='CONNECTING';dot.className='dot wait';" +
        "ws=new WebSocket('ws://'+location.hostname+':8080');" +
        "ws.binaryType='arraybuffer';" +
        "ws.onopen=function(){st.textContent='WAITING';dot.className='dot wait';loadSettings();};" +
        "ws.onmessage=function(e){" +
        "var b=new Blob([e.data],{type:'image/jpeg'});" +
        "var url=URL.createObjectURL(b);" +
        "img.onload=function(){" +
        "URL.revokeObjectURL(url);" +
        "document.getElementById('res').textContent=img.naturalWidth+'x'+img.naturalHeight;};" +
        "img.src=url;" +
        "if(!img.classList.contains('show')){img.classList.add('show');ph.style.display='none';}" +
        "st.textContent='LIVE';dot.className='dot live';" +
        "fps++;kb+=e.data.byteLength;lastFrame=Date.now();};" +
        "ws.onclose=function(){st.textContent='OFFLINE';dot.className='dot err';setTimeout(connect,2000);};" +
        "ws.onerror=function(){ws.close();};" +
        "}" +

        // Send setting via WebSocket
        "function send(obj){try{if(ws&&ws.readyState===1)ws.send(JSON.stringify(obj));}catch(e){}}" +

        // Quality — debounced
        "function onQ(v){" +
        "document.getElementById('vQ').textContent=v+'%';" +
        "clearTimeout(qTimer);" +
        "qTimer=setTimeout(function(){send({action:'quality',value:parseInt(v)});},150);" +
        "}" +

        // Resolution
        "function setRes(idx){curRes=idx;highlightRes(idx);send({action:'res',value:idx});}" +
        "function highlightRes(idx){" +
        "[0,1,2].forEach(function(i){" +
        "var b=document.getElementById('r'+i);" +
        "b.className='rbtn'+(i===idx?' on':'');});}" +

        // Camera switch
        "function switchCam(){send({action:'switchCam'});}" +

        // Controls toggle
        "function toggleCtrl(){" +
        "ctrlVisible=!ctrlVisible;" +
        "document.getElementById('ctrl').className=ctrlVisible?'show':'';" +
        "}" +

        // Fullscreen
        "function toggleFs(){" +
        "if(document.fullscreenElement)document.exitFullscreen();" +
        "else document.documentElement.requestFullscreen();}" +

        "connect();" +
        "</script></body></html>";
    }
}
