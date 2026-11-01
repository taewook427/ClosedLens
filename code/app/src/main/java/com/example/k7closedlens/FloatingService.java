package com.example.k7closedlens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import java.io.IOException;

public class FloatingService extends Service {

    private static final String CHANNEL_ID = "closedlens_floating";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private ImageView floatingButton;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        createFloatingButton();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ClosedLens 플로팅 버튼",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("ClosedLens 플로팅 오버레이 서비스");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ClosedLens")
                .setContentText("플로팅 버튼이 활성화되어 있습니다")
                .setSmallIcon(R.drawable.ic_lens)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        floatingButton = new ImageView(this);
        floatingButton.setImageResource(R.drawable.ic_lens);
        floatingButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Create a circular background
        floatingButton.setBackgroundResource(R.drawable.floating_button_bg);
        int size = dpToPx(56);
        floatingButton.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                size,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 300;

        windowManager.addView(floatingButton, params);

        // Touch listener for drag and click
        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            moved = true;
                        }
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        windowManager.updateViewLayout(floatingButton, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        long duration = System.currentTimeMillis() - touchStartTime;
                        if (!moved && duration < 300) {
                            onFloatingButtonClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onFloatingButtonClick() {
        // Hide the floating button temporarily
        floatingButton.setVisibility(View.INVISIBLE);

        // Small delay to ensure button is hidden before capture
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Request screen capture from ScreenCaptureService
            if (ScreenCaptureService.instance != null) {
                ScreenCaptureService.instance.captureScreen(bitmap -> {
                    // Show button again
                    new Handler(Looper.getMainLooper()).post(() ->
                            floatingButton.setVisibility(View.VISIBLE));

                    if (bitmap != null) {
                        // Save bitmap to cache
                        String path = saveBitmapToCache(bitmap);
                        if (path != null) {
                            Intent intent = new Intent(FloatingService.this, CaptureResultActivity.class);
                            intent.putExtra("image_path", path);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    }
                });
            } else {
                floatingButton.setVisibility(View.VISIBLE);
            }
        }, 200);
    }

    private String saveBitmapToCache(Bitmap bitmap) {
        try {
            File file = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            return file.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Stop screen capture service too
        stopService(new Intent(this, ScreenCaptureService.class));
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
        }
    }
}
