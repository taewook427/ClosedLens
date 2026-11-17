package com.example.k7closedlens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 플로팅 렌즈 오버레이 버튼 관리 서비스
 */
public class FloatingService extends Service {

    public static final String ACTION_STOP = "com.example.k7closedlens.STOP";
    private static FloatingService instance;

    private WindowManager wm;
    private ImageView button;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        setupNotification();
        setupButton();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    public static void setVisible(boolean visible) {
        if (instance != null && instance.button != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (instance != null && instance.button != null) {
                    instance.button.setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    private void setupButton() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        button = new ImageView(this);
        button.setImageResource(R.drawable.ic_lens);
        button.setBackgroundResource(R.drawable.floating_button_bg);

        float density = getResources().getDisplayMetrics().density;
        int size = (int) (56 * density);
        int pad = (int) (12 * density);
        button.setPadding(pad, pad, pad, pad);

        params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 300;

        wm.addView(button, params);

        button.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private long touchTime;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x; initY = params.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        touchTime = System.currentTimeMillis();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchX;
                        float dy = e.getRawY() - touchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        params.x = initX + (int) dx;
                        params.y = initY + (int) dy;
                        wm.updateViewLayout(button, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!moved && (System.currentTimeMillis() - touchTime) < 300) {
                            onClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onClick() {
        button.setVisibility(View.GONE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ScreenCaptureService.instance != null) {
                ScreenCaptureService.instance.captureScreen(bmp -> {
                    if (bmp != null) {
                        String path = saveCache(bmp);
                        if (path != null) {
                            Intent it = new Intent(FloatingService.this, CaptureResultActivity.class);
                            it.putExtra("image_path", path);
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it);
                            return;
                        }
                    }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (button != null) button.setVisibility(View.VISIBLE);
                    });
                });
            } else {
                button.setVisibility(View.VISIBLE);
            }
        }, 150);
    }

    private String saveCache(Bitmap bmp) {
        try {
            File f = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void setupNotification() {
        String chId = "closedlens_float";
        NotificationChannel ch = new NotificationChannel(chId, "Lens Overlay", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);

        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIt = new Intent(this, FloatingService.class);
        stopIt.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIt, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, chId)
                .setContentTitle("ClosedLens Active")
                .setContentText("Tap to open app | Tap lens to capture")
                .setSmallIcon(R.drawable.ic_lens)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .setOngoing(true)
                .build();

        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (button != null && wm != null) {
            try { wm.removeView(button); } catch (Exception ignored) {}
            button = null;
        }
        if (ScreenCaptureService.instance != null) {
            ScreenCaptureService.instance.stopSelf();
        }
        instance = null;
    }
}
