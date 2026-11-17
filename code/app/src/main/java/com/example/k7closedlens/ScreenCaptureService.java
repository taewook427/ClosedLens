package com.example.k7closedlens;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;

/**
 * On-demand persistent VirtualDisplay screen capture service.
 */
public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static ScreenCaptureService instance;

    public interface Callback {
        void onCaptured(Bitmap bitmap);
    }

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Callback pendingCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int width, height, density;
    private boolean ready = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        setupNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);

        if (resultCode == Activity.RESULT_CANCELED || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(2, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
            density = getResources().getConfiguration().densityDpi;
        }

        setupDisplay();
        ready = true;
        return START_NOT_STICKY;
    }

    private void setupDisplay() {
        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, handler);

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "ClosedLensCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null && pendingCallback != null) {
                        Callback cb = pendingCallback;
                        pendingCallback = null;
                        Bitmap bmp = toBitmap(img);
                        img.close();
                        img = null;
                        cb.onCaptured(bmp);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, handler);

        } catch (Exception ignored) {}
    }

    public void captureScreen(Callback cb) {
        if (!ready || imageReader == null) {
            if (cb != null) cb.onCaptured(null);
            return;
        }

        Image img = null;
        try {
            img = imageReader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = toBitmap(img);
                img.close();
                if (cb != null) cb.onCaptured(bmp);
                return;
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }

        pendingCallback = cb;
        handler.postDelayed(() -> {
            if (pendingCallback != null) {
                Callback c = pendingCallback;
                pendingCallback = null;
                c.onCaptured(null);
            }
        }, 1200);
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap raw = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, width, height);
        if (cropped != raw) raw.recycle();
        return cropped;
    }

    private void setupNotification() {
        String chId = "closedlens_capture";
        NotificationChannel ch = new NotificationChannel(chId, "Capture Service", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification createNotification() {
        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, "closedlens_capture")
                .setContentTitle("ClosedLens Capture")
                .setContentText("Ready for screen capture")
                .setSmallIcon(R.drawable.ic_lens)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ready = false;
        pendingCallback = null;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        instance = null;
    }
}
