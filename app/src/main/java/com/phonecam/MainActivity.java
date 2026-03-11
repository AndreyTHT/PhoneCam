package com.phonecam;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 100;

    private TextView tvUrl, tvStatus, tvViewers, tvFps, tvQualityVal, tvResVal;
    private Button btnToggle, btnSwitchCam, btnHide;
    private SeekBar seekQuality;
    private Button btnResLow, btnResMid, btnResHigh;

    private boolean isRunning = false;
    private int currentFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int currentQuality = 50;
    private int currentRes = 1;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int viewers = intent.getIntExtra(CameraStreamService.EXTRA_VIEWERS, 0);
            int fps     = intent.getIntExtra(CameraStreamService.EXTRA_FPS, 0);
            boolean running = intent.getBooleanExtra(CameraStreamService.EXTRA_RUNNING, false);
            updateUi(running, viewers, fps);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvUrl        = findViewById(R.id.tvUrl);
        tvStatus     = findViewById(R.id.tvStatus);
        tvViewers    = findViewById(R.id.tvViewers);
        tvFps        = findViewById(R.id.tvFps);
        tvQualityVal = findViewById(R.id.tvQualityVal);
        tvResVal     = findViewById(R.id.tvResVal);
        btnToggle    = findViewById(R.id.btnToggle);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnHide      = findViewById(R.id.btnHide);
        seekQuality  = findViewById(R.id.seekQuality);
        btnResLow    = findViewById(R.id.btnResLow);
        btnResMid    = findViewById(R.id.btnResMid);
        btnResHigh   = findViewById(R.id.btnResHigh);

        tvUrl.setText("http://" + getLocalIp() + ":" + StreamServer.HTTP_PORT);

        seekQuality.setMax(85);
        seekQuality.setProgress(currentQuality - 10);
        tvQualityVal.setText(currentQuality + "%");
        seekQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                currentQuality = p + 10;
                tvQualityVal.setText(currentQuality + "%");
                if (isRunning) sendToService(CameraStreamService.ACTION_SET_QUALITY,
                    CameraStreamService.EXTRA_QUALITY, currentQuality);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnResLow.setOnClickListener(v  -> setResolution(0));
        btnResMid.setOnClickListener(v  -> setResolution(1));
        btnResHigh.setOnClickListener(v -> setResolution(2));
        highlightRes(currentRes);

        btnSwitchCam.setOnClickListener(v -> {
            currentFacing = (currentFacing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
            btnSwitchCam.setText(currentFacing == CameraCharacteristics.LENS_FACING_BACK
                ? "↺ BACK" : "↺ FRONT");
            if (isRunning) sendToService(CameraStreamService.ACTION_SWITCH_CAM,
                CameraStreamService.EXTRA_FACING, currentFacing);
        });

        btnHide.setOnClickListener(v -> showHideDialog());
        btnToggle.setOnClickListener(v -> {
            if (isRunning) stopSvc();
            else if (hasCameraPermission()) startSvc();
            else requestPermission();
        });

        updateUi(false, 0, 0);
    }

    // ── Скрыть иконку ────────────────────────────────────────────────────────

    private void showHideDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Скрыть иконку?")
            .setMessage(
                "Иконка исчезнет из лаунчера сразу.\n" +
                "В списке приложений (Настройки) она останется — это нормально.\n\n" +
                "Чтобы снова открыть:\n" +
                "adb shell am start -n com.phonecam/.MainActivity\n\n" +
                "Или переустанови APK — иконка вернётся.")
            .setPositiveButton("Скрыть", (d, w) -> hideIcon())
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void hideIcon() {
        // Отключаем alias .Launcher — иконка исчезает из лаунчера немедленно.
        // MainActivity и сервис продолжают работать.
        ComponentName alias = new ComponentName(getPackageName(), getPackageName() + ".Launcher");
        getPackageManager().setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
        Toast.makeText(this,
            "Иконка скрыта. Для возврата: adb shell am start -n com.phonecam/.MainActivity",
            Toast.LENGTH_LONG).show();
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    private void setResolution(int idx) {
        currentRes = idx;
        highlightRes(idx);
        int[] res = CameraStreamService.RESOLUTIONS[idx];
        tvResVal.setText(res[0] + "×" + res[1]);
        if (isRunning) sendToService(CameraStreamService.ACTION_SET_RES,
            CameraStreamService.EXTRA_RES, idx);
    }

    private void highlightRes(int idx) {
        int on      = ContextCompat.getColor(this, R.color.accent);
        int off     = ContextCompat.getColor(this, R.color.surface);
        int bgOn    = ContextCompat.getColor(this, R.color.bg);
        int textOff = ContextCompat.getColor(this, R.color.text);
        btnResLow.setBackgroundColor(idx == 0 ? on : off);
        btnResMid.setBackgroundColor(idx == 1 ? on : off);
        btnResHigh.setBackgroundColor(idx == 2 ? on : off);
        btnResLow.setTextColor(idx == 0 ? bgOn : textOff);
        btnResMid.setTextColor(idx == 1 ? bgOn : textOff);
        btnResHigh.setTextColor(idx == 2 ? bgOn : textOff);
    }

    private void sendToService(String action, String extra, int value) {
        Intent i = new Intent(this, CameraStreamService.class);
        i.setAction(action);
        i.putExtra(extra, value);
        startService(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(statusReceiver, new IntentFilter(CameraStreamService.BROADCAST_STATUS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private void startSvc() {
        Intent i = new Intent(this, CameraStreamService.class);
        i.setAction(CameraStreamService.ACTION_START);
        i.putExtra(CameraStreamService.EXTRA_FACING, currentFacing);
        i.putExtra(CameraStreamService.EXTRA_QUALITY, currentQuality);
        i.putExtra(CameraStreamService.EXTRA_RES, currentRes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        isRunning = true;
        updateUi(true, 0, 0);
    }

    private void stopSvc() {
        Intent i = new Intent(this, CameraStreamService.class);
        i.setAction(CameraStreamService.ACTION_STOP);
        startService(i);
        isRunning = false;
        updateUi(false, 0, 0);
    }

    private void updateUi(boolean running, int viewers, int fps) {
        isRunning = running;
        tvStatus.setText(running ? "LIVE" : "OFFLINE");
        tvStatus.setTextColor(getResources().getColor(running ? R.color.accent : R.color.muted));
        btnToggle.setText(running ? "STOP" : "START");
        btnToggle.setBackgroundColor(getResources().getColor(running ? R.color.btn_stop : R.color.btn_start));
        btnToggle.setTextColor(ContextCompat.getColor(this, R.color.bg));
        tvViewers.setText(String.valueOf(viewers));
        tvFps.setText(String.valueOf(fps));
    }

    private String getLocalIp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ — use ConnectivityManager / LinkProperties
                android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network net = cm.getActiveNetwork();
                    if (net != null) {
                        android.net.LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null) {
                            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                                java.net.InetAddress addr = la.getAddress();
                                if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                                    return addr.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
            // API < 31 — WifiManager still fine
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                @SuppressWarnings("deprecation")
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "Check WiFi";
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == PERM_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startSvc();
    }
}
