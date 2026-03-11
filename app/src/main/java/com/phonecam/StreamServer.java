package com.Bluetooth;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class StreamServer {

    private static final String TAG = "StreamServer";
    public static final int HTTP_PORT = 8080;

    private final CameraStreamService service;
    private final OnViewerCountChanged listener;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final Set<MjpegClient> clients = Collections.synchronizedSet(new HashSet<>());
    private final AtomicReference<byte[]> lastFrame = new AtomicReference<>(null);

    public interface OnViewerCountChanged {
        void onCountChanged(int count);
    }

    public StreamServer(CameraStreamService svc, OnViewerCountChanged listener) {
        this.service  = svc;
        this.listener = listener;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(HTTP_PORT);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "HttpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.d(TAG, "Server started on port " + HTTP_PORT);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        synchronized (clients) {
            for (MjpegClient c : clients) c.close();
            clients.clear();
        }
        clientPool.shutdownNow();
    }

    public void broadcastFrame(byte[] jpeg) {
        lastFrame.set(jpeg);
        synchronized (clients) {
            Set<MjpegClient> dead = new HashSet<>();
            for (MjpegClient c : clients) {
                if (!c.sendFrame(jpeg)) dead.add(c);
            }
            if (!dead.isEmpty()) {
                clients.removeAll(dead);
                notifyCount();
            }
        }
    }

    public int getViewerCount() { return clients.size(); }
    private void notifyCount() { if (listener != null) listener.onCountChanged(clients.size()); }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                clientPool.execute(() -> handleClient(socket));
            } catch (Exception e) {
                if (running) Log.e(TAG, "Accept error", e);
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();

            // --- Read headers byte-by-byte until \r\n\r\n ---
            StringBuilder headerBuf = new StringBuilder();
            int b0 = 0, b1 = 0, b2 = 0, b3;
            while (true) {
                int ch = in.read();
                if (ch == -1) { socket.close(); return; }
                headerBuf.append((char) ch);
                b3 = ch;
                if (b0 == '\r' && b1 == '\n' && b2 == '\r' && b3 == '\n') break;
                b0 = b1; b1 = b2; b2 = b3;
            }
            String rawHeaders = headerBuf.toString();
            String[] lines = rawHeaders.split("\r\n");
            if (lines.length == 0) { socket.close(); return; }

            // Parse request line
            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) { socket.close(); return; }
            String method = reqParts[0];
            String path   = reqParts[1].split("\\?")[0];

            // --- Read POST body using Content-Length ---
            String body = "";
            if ("POST".equalsIgnoreCase(method)) {
                int contentLength = 0;
                for (String line : lines) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (contentLength > 0) {
                    byte[] bodyBytes = new byte[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int n = in.read(bodyBytes, totalRead, contentLength - totalRead);
                        if (n == -1) break;
                        totalRead += n;
                    }
                    body = new String(bodyBytes, 0, totalRead, "UTF-8");
                }
            }

            Log.d(TAG, method + " " + path + (body.isEmpty() ? "" : " body=" + body));

            // Remove read timeout before handing off
            socket.setSoTimeout(0);

            switch (path) {
                case "/stream":   handleStream(socket);          break;
                case "/api/status":  handleStatus(socket);       break;
                case "/api/settings": handleSettings(socket, body); break;
                default:          handlePage(socket);             break;
            }
        } catch (Exception e) {
            Log.e(TAG, "handleClient error", e);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── MJPEG stream ──────────────────────────────────────────────────────────

    private void handleStream(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace;boundary=frame\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.flush();

        MjpegClient client = new MjpegClient(socket, out);
        clients.add(client);
        notifyCount();

        byte[] last = lastFrame.get();
        if (last != null) client.sendFrame(last);

        client.waitUntilDead();

        clients.remove(client);
        notifyCount();
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ── HTML page ─────────────────────────────────────────────────────────────

    private void handlePage(Socket socket) throws IOException {
        byte[] body = getViewerHtml().getBytes("UTF-8");
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes("UTF-8"));
        out.write(body);
        out.flush();
        socket.close();
    }

    // ── /api/status ───────────────────────────────────────────────────────────

    private void handleStatus(Socket socket) throws IOException {
        int[] res = CameraStreamService.RESOLUTIONS[service.getResIdx()];
        String json = "{\"quality\":" + service.getQuality()
            + ",\"res\":"     + service.getResIdx()
            + ",\"facing\":"  + service.getFacing()
            + ",\"viewers\":" + clients.size()
            + ",\"resW\":"    + res[0]
            + ",\"resH\":"    + res[1] + "}";
        byte[] body = json.getBytes("UTF-8");
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes("UTF-8"));
        out.write(body);
        out.flush();
        socket.close();
    }

    // ── /api/settings ─────────────────────────────────────────────────────────

    private void handleSettings(Socket socket, String body) throws IOException {
        Log.d(TAG, "Settings body: " + body);
        if (body.contains("\"action\":\"quality\"")) {
            int val = extractInt(body, "value", 50);
            Log.d(TAG, "Set quality: " + val);
            service.setQualityFromBrowser(val);
        } else if (body.contains("\"action\":\"res\"")) {
            int val = extractInt(body, "value", 1);
            Log.d(TAG, "Set res: " + val);
            service.setResFromBrowser(val);
        } else if (body.contains("\"action\":\"switchCam\"")) {
            Log.d(TAG, "Switch cam");
            service.switchCameraFromBrowser();
        }

        byte[] resp = "{\"ok\":true}".getBytes("UTF-8");
        String headers =
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + resp.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes("UTF-8"));
        out.write(resp);
        out.flush();
        socket.close();
    }

    private int extractInt(String json, String key, int def) {
        try {
            int i = json.indexOf("\"" + key + "\":");
            if (i < 0) return def;
            String rest = json.substring(i + key.length() + 3).trim();
            StringBuilder num = new StringBuilder();
            for (char ch : rest.toCharArray()) {
                if (Character.isDigit(ch) || (ch == '-' && num.length() == 0)) num.append(ch);
                else if (num.length() > 0) break;
            }
            return num.length() > 0 ? Integer.parseInt(num.toString()) : def;
        } catch (Exception e) { return def; }
    }

    // ── MjpegClient ───────────────────────────────────────────────────────────

    private static class MjpegClient {
        private final Socket socket;
        private final OutputStream out;
        private volatile boolean alive = true;
        private final Object lock = new Object();

        MjpegClient(Socket s, OutputStream o) { socket = s; out = o; }

        boolean sendFrame(byte[] jpeg) {
            if (!alive) return false;
            try {
                String partHeader =
                    "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + jpeg.length + "\r\n" +
                    "\r\n";
                synchronized (lock) {
                    out.write(partHeader.getBytes("UTF-8"));
                    out.write(jpeg);
                    out.write("\r\n".getBytes("UTF-8"));
                    out.flush();
                }
                return true;
            } catch (Exception e) {
                alive = false;
                return false;
            }
        }

        void waitUntilDead() {
            while (alive) {
                try {
                    int b = socket.getInputStream().read();
                    if (b == -1) { alive = false; break; }
                } catch (Exception e) {
                    alive = false;
                    break;
                }
            }
        }

        void close() {
            alive = false;
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── Viewer HTML ───────────────────────────────────────────────────────────

    private String getViewerHtml() {
        return "<!DOCTYPE html><html><head>" +
        "<meta charset='UTF-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>" +
        "<title>Bluetooth</title>" +
        "<style>" +
        "*{margin:0;padding:0;box-sizing:border-box}" +
        "body{background:#0a0a0f;color:#e0e0f0;font-family:'Courier New',monospace;display:flex;flex-direction:column;height:100dvh;overflow:hidden}" +
        "header{display:flex;align-items:center;padding:8px 14px;gap:10px;background:#12121a;border-bottom:1px solid #1e1e2e;flex-shrink:0}" +
        ".logo{font-size:11px;letter-spacing:4px;color:#5fffb0;font-weight:bold}" +
        ".sep{flex:1}" +
        "#st{font-size:10px;letter-spacing:2px;color:#5fffb0}" +
        ".dot{width:7px;height:7px;border-radius:50%;background:#5fffb0;box-shadow:0 0 8px rgba(95,255,176,.6);animation:pulse 2s infinite}" +
        "@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(95,255,176,.5)}70%{box-shadow:0 0 0 7px rgba(95,255,176,0)}100%{box-shadow:0 0 0 0 rgba(95,255,176,0)}}" +
        "#wrap{flex:1;background:#000;display:flex;align-items:center;justify-content:center;position:relative;overflow:hidden;cursor:pointer}" +
        "#stream{max-width:100%;max-height:100%;object-fit:contain;display:block}" +
        "#ph{position:absolute;display:flex;flex-direction:column;align-items:center;gap:10px;color:#44445a;font-size:11px;letter-spacing:3px;pointer-events:none}" +
        ".ph-icon{font-size:36px;opacity:.3}" +
        "#ctrl{background:#12121a;border-top:1px solid #1e1e2e;padding:10px 14px;flex-shrink:0;display:none}" +
        "#ctrl.show{display:block}" +
        ".ctrl-row{display:flex;align-items:center;gap:10px;margin-bottom:8px}" +
        ".ctrl-row:last-child{margin-bottom:0}" +
        ".ctrl-lbl{font-size:9px;letter-spacing:2px;color:#555570;width:70px;flex-shrink:0}" +
        ".ctrl-val{font-size:11px;color:#5fffb0;width:36px;text-align:right;flex-shrink:0}" +
        "input[type=range]{flex:1;accent-color:#5fffb0;height:3px}" +
        ".btn-group{display:flex;gap:5px;flex:1}" +
        ".rbtn{flex:1;padding:5px;background:#1e1e2e;border:1px solid #2a2a3e;border-radius:3px;color:#888;font-family:'Courier New',monospace;font-size:10px;cursor:pointer;transition:all .15s}" +
        ".rbtn.on{background:rgba(95,255,176,.15);border-color:#5fffb0;color:#5fffb0}" +
        ".rbtn:active{transform:scale(.96)}" +
        "footer{display:flex;gap:6px;padding:6px 14px;background:#0e0e18;border-top:1px solid #1a1a2a;flex-shrink:0}" +
        ".stat{flex:1;text-align:center}" +
        ".sv{font-size:16px;font-weight:bold;color:#5bc8ff;display:block}" +
        ".sl{font-size:8px;color:#44445a;letter-spacing:2px}" +
        "</style></head><body>" +
        "<header>" +
        "<span class='logo'>Bluetooth</span><span class='sep'></span>" +
        "<span id='st'>LIVE</span><span class='dot'></span>" +
        "</header>" +
        "<div id='wrap' onclick='toggleCtrl()'>" +
        "<div id='ph'><div class='ph-icon'>📷</div><span>Загрузка...</span></div>" +
        "<img id='stream' src='/stream' alt='' onload='onLoad()' onerror='onErr()'>" +
        "</div>" +
        "<div id='ctrl'>" +
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Quality</span>" +
        "<input type='range' id='sQ' min='10' max='95' value='50' oninput='onQ(this.value)'>" +
        "<span class='ctrl-val' id='vQ'>50%</span>" +
        "</div>" +
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Resolution</span>" +
        "<div class='btn-group'>" +
        "<button class='rbtn' id='r0' onclick='setRes(0)'>480p</button>" +
        "<button class='rbtn on' id='r1' onclick='setRes(1)'>854p</button>" +
        "<button class='rbtn' id='r2' onclick='setRes(2)'>720p</button>" +
        "</div>" +
        "</div>" +
        "<div class='ctrl-row'>" +
        "<span class='ctrl-lbl'>Camera</span>" +
        "<button class='rbtn' onclick='switchCam()' style='flex:none;padding:5px 14px'>&#8635; Switch</button>" +
        "<span style='flex:1'></span>" +
        "<button class='rbtn' onclick='toggleFs()' style='flex:none;padding:5px 14px'>&#x26F6; Full</button>" +
        "</div>" +
        "</div>" +
        "<footer>" +
        "<div class='stat'><span class='sv' id='fps'>-</span><span class='sl'>FPS</span></div>" +
        "<div class='stat'><span class='sv' id='res'>-</span><span class='sl'>RES</span></div>" +
        "<div class='stat'><span class='sv' id='viewers'>-</span><span class='sl'>VIEWERS</span></div>" +
        "</footer>" +
        "<script>" +
        "var ctrlVisible=false,qTimer=null,curRes=1,fpsCount=0;" +
        "function onLoad(){" +
        "document.getElementById('ph').style.display='none';" +
        "var s=document.getElementById('stream');" +
        "document.getElementById('res').textContent=s.naturalWidth+'x'+s.naturalHeight;" +
        "fpsCount++;}" +
        "function onErr(){setTimeout(function(){" +
        "document.getElementById('stream').src='/stream?t='+Date.now();},1500);}" +
        "setInterval(function(){document.getElementById('fps').textContent=fpsCount;fpsCount=0;},1000);" +
        "function pollStatus(){fetch('/api/status').then(function(r){return r.json();}).then(function(d){" +
        "document.getElementById('viewers').textContent=d.viewers;" +
        "}).catch(function(){});}" +
        "setInterval(pollStatus,3000);pollStatus();" +
        "fetch('/api/status').then(function(r){return r.json();}).then(function(d){" +
        "var q=d.quality||50;" +
        "document.getElementById('sQ').value=q;" +
        "document.getElementById('vQ').textContent=q+'%';" +
        "curRes=d.res||1;highlightRes(curRes);" +
        "}).catch(function(){});" +
        "function api(obj){" +
        "fetch('/api/settings',{method:'POST'," +
        "headers:{'Content-Type':'application/json'}," +
        "body:JSON.stringify(obj)})" +
        ".catch(function(){});}" +
        "function onQ(v){document.getElementById('vQ').textContent=v+'%';" +
        "clearTimeout(qTimer);" +
        "qTimer=setTimeout(function(){api({action:'quality',value:parseInt(v)});},200);}" +
        "function setRes(i){curRes=i;highlightRes(i);api({action:'res',value:i});}" +
        "function highlightRes(i){" +
        "for(var j=0;j<3;j++){" +
        "document.getElementById('r'+j).className='rbtn'+(j===i?' on':'');}}" +
        "function switchCam(){api({action:'switchCam'});}" +
        "function toggleCtrl(){ctrlVisible=!ctrlVisible;" +
        "document.getElementById('ctrl').className=ctrlVisible?'show':'';}" +
        "function toggleFs(){" +
        "if(document.fullscreenElement)document.exitFullscreen();" +
        "else document.documentElement.requestFullscreen();}" +
        "</script></body></html>";
    }
}
