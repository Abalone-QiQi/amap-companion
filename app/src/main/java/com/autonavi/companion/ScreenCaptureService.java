package com.autonavi.companion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

public class ScreenCaptureService extends Service {
    private static final String TAG = "AmapCompanion";
    private static final String CHANNEL_ID = "amap_companion_capture";
    private static final int NOTIFICATION_ID = 2;
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";
    private static final String EXTRA_DISPLAY_ID = "display_id";

    public static final String ACTION_CAPTURE_STARTED = "com.autonavi.companion.CAPTURE_STARTED";
    public static final String ACTION_CAPTURE_STOPPED = "com.autonavi.companion.CAPTURE_STOPPED";
    public static final String ACTION_RESOLUTION_CHANGED = "com.autonavi.companion.RESOLUTION_CHANGED";

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface targetSurface;
    private android.view.ViewGroup clusterStage;

    private int secondaryDisplayId = -1;
    private int secondaryWidth = 0;
    private int secondaryHeight = 0;
    private int mainWidth = 0;
    private int mainHeight = 0;

    private int captureWidth = 0;
    private int captureHeight = 0;
    private float aspectRatio = 1.777f;

    private boolean isAutoAdaptEnabled = true;
    private int manualWidth = 0;
    private int manualHeight = 0;

    private int captureQualityDpi = 320;
    private boolean autoRotateEnabled = true;
    private boolean isSecondaryPortrait = false;
    private boolean isMainPortrait = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface CaptureCallback {
        void onStarted(int width, int height);
        void onStopped();
        void onResolutionChanged(int width, int height);
        void onError(String error);
    }

    private CaptureCallback callback;

    public void setCallback(CaptureCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        initMainDisplaySize();
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.d(TAG, "ScreenCaptureService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CAPTURE_STARTED.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            int displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1);
            clusterStage = (android.view.ViewGroup) intent.getParcelableExtra("cluster_stage");
            startCapture(resultCode, resultData, displayId);
        } else if (ACTION_CAPTURE_STOPPED.equals(action)) {
            stopCapture();
        } else if ("SET_SURFACE".equals(action)) {
            Surface surface = intent.getParcelableExtra("surface");
            setTargetSurface(surface);
        } else if ("SET_AUTO_ADAPT".equals(action)) {
            setAutoAdaptEnabled(intent.getBooleanExtra("enabled", true));
        } else if ("SET_MANUAL_SIZE".equals(action)) {
            setManualSize(intent.getIntExtra("width", 0), intent.getIntExtra("height", 0));
        } else if ("REFRESH_DISPLAY".equals(action)) {
            refreshDisplayInfo();
            if (virtualDisplay != null) {
                resizeVirtualDisplay();
            }
        } else if ("SET_QUALITY".equals(action)) {
            String quality = intent.getStringExtra("quality");
            if (quality != null) {
                setQuality(quality);
            }
        } else if ("SET_AUTO_ROTATE".equals(action)) {
            setAutoRotateEnabled(intent.getBooleanExtra("enabled", true));
        } else if ("SET_POSITION".equals(action)) {
            int x = intent.getIntExtra("x", 0);
            int y = intent.getIntExtra("y", 0);
            setCapturePosition(x, y);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
        Log.d(TAG, "ScreenCaptureService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initMainDisplaySize() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        Display mainDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (mainDisplay != null) {
            Point size = new Point();
            mainDisplay.getRealSize(size);
            mainWidth = size.x;
            mainHeight = size.y;
            aspectRatio = mainWidth > 0 ? (float) mainWidth / mainHeight : 1.777f;
            Log.d(TAG, "main display: " + mainWidth + "x" + mainHeight + ", ratio=" + aspectRatio);
        }
    }

    public void refreshDisplayInfo() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }

        Display[] displays = dm.getDisplays();
        for (Display display : displays) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            Point size = new Point();
            display.getRealSize(size);
            secondaryDisplayId = display.getDisplayId();
            secondaryWidth = size.x;
            secondaryHeight = size.y;
            isSecondaryPortrait = secondaryHeight > secondaryWidth;
            Log.d(TAG, "secondary display " + secondaryDisplayId + ": " + secondaryWidth + "x" + secondaryHeight + 
                    (isSecondaryPortrait ? " (portrait)" : " (landscape)"));

            if (isAutoAdaptEnabled) {
                calculateCaptureSize();
                if (virtualDisplay != null) {
                    resizeVirtualDisplay();
                    notifyCallbackResolutionChanged();
                }
            }
            break;
        }

        Display mainDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (mainDisplay != null) {
            Point size = new Point();
            mainDisplay.getRealSize(size);
            mainWidth = size.x;
            mainHeight = size.y;
            isMainPortrait = mainHeight > mainWidth;
            aspectRatio = mainWidth > 0 ? (float) mainWidth / mainHeight : aspectRatio;
        }
    }

    public void calculateCaptureSize() {
        if (secondaryWidth <= 0 || secondaryHeight <= 0) {
            captureWidth = mainWidth;
            captureHeight = mainHeight;
            return;
        }

        float secondaryRatio = (float) secondaryWidth / secondaryHeight;
        float diff = Math.abs(secondaryRatio - aspectRatio);

        if (diff < 0.05f) {
            captureWidth = secondaryWidth;
            captureHeight = secondaryHeight;
        } else {
            if (secondaryRatio > aspectRatio) {
                captureWidth = (int) (secondaryHeight * aspectRatio);
                captureHeight = secondaryHeight;
            } else {
                captureWidth = secondaryWidth;
                captureHeight = (int) (secondaryWidth / aspectRatio);
            }
        }

        Log.d(TAG, "calculated capture size: " + captureWidth + "x" + captureHeight);
    }

    private void calculateCaptureSizeFromSecondary() {
        if (secondaryWidth <= 0 || secondaryHeight <= 0) {
            calculateCaptureSize();
            return;
        }

        float mainRatio = mainWidth > 0 ? (float) mainWidth / mainHeight : aspectRatio;
        float secondaryRatio = (float) secondaryWidth / secondaryHeight;

        if (autoRotateEnabled) {
            if ((isMainPortrait && !isSecondaryPortrait) || (!isMainPortrait && isSecondaryPortrait)) {
                mainRatio = 1.0f / mainRatio;
                Log.d(TAG, "rotation compensation applied, adjusted ratio=" + mainRatio);
            }
        }

        if (Math.abs(secondaryRatio - mainRatio) < 0.05f) {
            captureWidth = secondaryWidth;
            captureHeight = secondaryHeight;
        } else if (secondaryRatio > mainRatio) {
            captureWidth = (int) (secondaryHeight * mainRatio);
            captureHeight = secondaryHeight;
        } else {
            captureWidth = secondaryWidth;
            captureHeight = (int) (secondaryWidth / mainRatio);
        }

        Log.d(TAG, "calculated capture size (secondary): " + captureWidth + "x" + captureHeight);
    }

    private void startCapture(int resultCode, Intent resultData, int displayId) {
        if (resultCode <= 0 || resultData == null) {
            Log.e(TAG, "invalid media projection result");
            notifyCallbackError("无效的投屏授权");
            return;
        }

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null");
                notifyCallbackError("获取MediaProjection失败");
                return;
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    notifyCallbackStopped();
                }
            }, mainHandler);

            refreshDisplayInfo();

            if (displayId >= 0) {
                secondaryDisplayId = displayId;
            }

            if (isAutoAdaptEnabled) {
                calculateCaptureSizeFromSecondary();
            } else if (manualWidth > 0 && manualHeight > 0) {
                captureWidth = manualWidth;
                captureHeight = manualHeight;
            } else {
                captureWidth = mainWidth;
                captureHeight = mainHeight;
            }

            if (captureWidth <= 0 || captureHeight <= 0) {
                captureWidth = mainWidth;
                captureHeight = mainHeight;
            }

            createVirtualDisplay();

            Log.d(TAG, "capture started: " + captureWidth + "x" + captureHeight);
            notifyCallbackStarted(captureWidth, captureHeight);
        } catch (Exception e) {
            Log.e(TAG, "startCapture error", e);
            notifyCallbackError("投屏启动失败: " + e.getMessage());
        }
    }

    private void createVirtualDisplay() {
        if (mediaProjection == null) {
            Log.e(TAG, "mediaProjection is null");
            return;
        }

        if (captureWidth <= 0 || captureHeight <= 0) {
            Log.e(TAG, "invalid capture size: " + captureWidth + "x" + captureHeight);
            return;
        }

        int density = captureQualityDpi;

        if (targetSurface != null && targetSurface.isValid()) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AmapCompanionCapture",
                    captureWidth,
                    captureHeight,
                    density,
                    VirtualDisplay.FLAG_AUTO_MIRROR,
                    targetSurface,
                    null);
        } else {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AmapCompanionCapture",
                    captureWidth,
                    captureHeight,
                    density,
                    VirtualDisplay.FLAG_AUTO_MIRROR | VirtualDisplay.FLAG_OWN_CONTENT_ONLY,
                    null,
                    null);
        }

        Log.d(TAG, "virtualDisplay created: " + captureWidth + "x" + captureHeight + " @ " + density + "dpi");
    }

    private void resizeVirtualDisplay() {
        if (virtualDisplay == null) {
            return;
        }

        if (isAutoAdaptEnabled) {
            calculateCaptureSizeFromSecondary();
        } else if (manualWidth > 0 && manualHeight > 0) {
            captureWidth = manualWidth;
            captureHeight = manualHeight;
        }

        if (captureWidth <= 0 || captureHeight <= 0) {
            Log.e(TAG, "cannot resize: invalid size");
            return;
        }

        int density = captureQualityDpi;

        try {
            virtualDisplay.resize(captureWidth, captureHeight, density);
            Log.d(TAG, "virtualDisplay resized to: " + captureWidth + "x" + captureHeight + " @ " + density + "dpi");
            notifyCallbackResolutionChanged();
        } catch (Exception e) {
            Log.e(TAG, "resizeVirtualDisplay error", e);
        }
    }

    public void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        Log.d(TAG, "capture stopped");
        notifyCallbackStopped();
    }

    public void setTargetSurface(Surface surface) {
        this.targetSurface = surface;
        if (virtualDisplay != null && surface != null && surface.isValid()) {
            try {
                virtualDisplay.setSurface(surface);
                Log.d(TAG, "virtualDisplay surface set");
            } catch (Exception e) {
                Log.e(TAG, "setSurface error", e);
            }
        }
    }

    public void setAutoAdaptEnabled(boolean enabled) {
        this.isAutoAdaptEnabled = enabled;
        if (enabled && virtualDisplay != null) {
            resizeVirtualDisplay();
        }
        Log.d(TAG, "auto adapt enabled: " + enabled);
    }

    public void setManualSize(int width, int height) {
        this.manualWidth = width;
        this.manualHeight = height;
        if (!isAutoAdaptEnabled && virtualDisplay != null) {
            resizeVirtualDisplay();
        }
    }

    public int getSecondaryDisplayId() {
        return secondaryDisplayId;
    }

    public int getSecondaryWidth() {
        return secondaryWidth;
    }

    public int getSecondaryHeight() {
        return secondaryHeight;
    }

    public int getCaptureWidth() {
        return captureWidth;
    }

    public int getCaptureHeight() {
        return captureHeight;
    }

    public boolean isAutoAdaptEnabled() {
        return isAutoAdaptEnabled;
    }

    public boolean isCapturing() {
        return virtualDisplay != null;
    }

    public float getAspectRatio() {
        return aspectRatio;
    }

    public void setQuality(String quality) {
        switch (quality) {
            case "low":
                captureQualityDpi = 160;
                break;
            case "medium":
                captureQualityDpi = 240;
                break;
            case "high":
                captureQualityDpi = 320;
                break;
            case "ultra":
                captureQualityDpi = 480;
                break;
            default:
                captureQualityDpi = 320;
                break;
        }
        Log.d(TAG, "quality set to: " + quality + " (" + captureQualityDpi + "dpi)");
        if (virtualDisplay != null) {
            resizeVirtualDisplay();
        }
    }

    public void setAutoRotateEnabled(boolean enabled) {
        this.autoRotateEnabled = enabled;
        if (virtualDisplay != null && isAutoAdaptEnabled) {
            resizeVirtualDisplay();
        }
        Log.d(TAG, "auto rotate enabled: " + enabled);
    }

    public boolean isAutoRotateEnabled() {
        return autoRotateEnabled;
    }

    public int getCaptureQualityDpi() {
        return captureQualityDpi;
    }

    public void setCapturePosition(int x, int y) {
        if (clusterStage != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clusterStage.getLayoutParams();
                    if (params != null) {
                        params.leftMargin = x;
                        params.topMargin = y;
                        clusterStage.setLayoutParams(params);
                    }
                }
            });
        }
    }

    private void notifyCallbackStarted(final int width, final int height) {
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onStarted(width, height);
                    }
                }
            });
        }
    }

    private void notifyCallbackStopped() {
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onStopped();
                    }
                }
            });
        }
    }

    private void notifyCallbackResolutionChanged() {
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onResolutionChanged(captureWidth, captureHeight);
                    }
                }
            });
        }
    }

    private void notifyCallbackError(final String error) {
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onError(error);
                    }
                }
            });
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                ensureNotificationChannel(nm);
            }
            builder = createNotificationBuilderWithChannel();
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("AMap Companion")
                .setContentText("屏幕捕获服务运行中")
                .setOngoing(true)
                .build();
    }

    private void ensureNotificationChannel(NotificationManager notificationManager) {
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            java.lang.reflect.Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
            Object channel = ctor.newInstance(CHANNEL_ID, "AMap Companion Capture", 2);
            notificationManager.getClass()
                    .getMethod("createNotificationChannel", channelClass)
                    .invoke(notificationManager, channel);
        } catch (Throwable ignored) {
        }
    }

    private Notification.Builder createNotificationBuilderWithChannel() {
        try {
            java.lang.reflect.Constructor<Notification.Builder> ctor =
                    Notification.Builder.class.getConstructor(Context.class, String.class);
            return ctor.newInstance(this, CHANNEL_ID);
        } catch (Throwable ignored) {
            return new Notification.Builder(this);
        }
    }
}
