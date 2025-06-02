package com.phoneremote.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenCaptureService extends Service {
    private static final int MAX_FRAME_RATE = 30;
    private static final int MIN_FRAME_RATE = 5;
    private static final int INITIAL_FRAME_RATE = 15;
    private static final int QUALITY_HIGH = 100;
    private static final int QUALITY_LOW = 60;
    
    private final AtomicInteger currentFrameRate = new AtomicInteger(INITIAL_FRAME_RATE);
    private final AtomicInteger currentQuality = new AtomicInteger(QUALITY_HIGH);
    private final ExecutorService imageProcessingExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private byte[] previousFrame;
    private long lastFrameTime;
    private int droppedFrames;
    private static final int MAX_DROPPED_FRAMES = 30;
    
    private Runtime runtime;
    private static final double MEMORY_THRESHOLD = 0.8; // 80% memory usage threshold
    private static final String TAG = "ScreenCaptureService";
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "ScreenCaptureChannel";
    
    private static final int SCREEN_CAPTURE_WIDTH = 720;  // Reduced resolution for streaming
    private static final int SCREEN_CAPTURE_HEIGHT = 1280;
    private static final int SCREEN_DENSITY = DisplayMetrics.DENSITY_DEFAULT;
    
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private Timer timer;
    private AtomicBoolean isCapturing = new AtomicBoolean(false);
    
    // Singleton instance for easy access from outside
    private static ScreenCaptureService instance;
    
    // Callback interface for when new screenshots are available
    public interface OnScreenCaptureListener {
        void onScreenCaptureAvailable(byte[] jpegData);
    }
    
    private OnScreenCaptureListener captureListener;
    
    public void setOnScreenCaptureListener(OnScreenCaptureListener listener) {
        this.captureListener = listener;
    }
    
    public static ScreenCaptureService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            startForeground();
            startCapture(resultCode, data);
        }
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        stopCapture();
        imageProcessingExecutor.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        previousFrame = null;
        super.onDestroy();
        instance = null;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void startForeground() {

        Notification 0.3s
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Sharing")
                .setContentText("Your screen is being shared")
                .setSmallIcon(R.drawable.ic_notification)
                .build();
        
        startForeground(NOTIFICATION_ID, notification);
    }
    
    public void startCapture(int resultCode, Intent data) {
        if (isCapturing.get()) {
            return;
        }
        
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get media projection");
            return;
        }
        
        // Create virtual display with reduced resolution for better performance
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        // Scale down proportionally based on the device's aspect ratio
        int width = SCREEN_CAPTURE_WIDTH;
        int height = SCREEN_CAPTURE_HEIGHT;
        
        if (metrics.widthPixels > metrics.heightPixels) {
            // Landscape
            width = SCREEN_CAPTURE_HEIGHT;
            height = SCREEN_CAPTURE_WIDTH;
        }
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, SCREEN_DENSITY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler
        );
        
        // Start the capture timer
        startCaptureTimer();
        isCapturing.set(true);
    }
    
    public void stopCapture() {
        isCapturing.set(false);
        
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }
    
    private void startCaptureTimer() {
        runtime = Runtime.getRuntime();
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (imageReader != null && isCapturing.get()) {
                    long now = System.currentTimeMillis();
                    if (now - lastFrameTime < 1000 / currentFrameRate.get()) {
                        return; // Skip frame if too soon
                    }
                    
                    // Check memory usage
                    double memoryUsage = (runtime.totalMemory() - runtime.freeMemory()) / 
                            (double) runtime.maxMemory();
                    
                    if (memoryUsage > MEMORY_THRESHOLD) {
                        // Reduce quality and frame rate under memory pressure
                        adjustPerformance(true);
                    } else if (droppedFrames < MAX_DROPPED_FRAMES / 2) {
                        // Increase quality and frame rate if performance is good
                        adjustPerformance(false);
                    }
                    
                    captureScreen();
                    lastFrameTime = now;
                }
            }
        }, 0, 1000 / INITIAL_FRAME_RATE);
    }
    
    private void adjustPerformance(boolean reduce) {
        if (reduce) {
            int newFrameRate = Math.max(MIN_FRAME_RATE, currentFrameRate.get() - 5);
            int newQuality = Math.max(QUALITY_LOW, currentQuality.get() - 10);
            currentFrameRate.set(newFrameRate);
            currentQuality.set(newQuality);
            droppedFrames = 0;
        } else {
            int newFrameRate = Math.min(MAX_FRAME_RATE, currentFrameRate.get() + 1);
            int newQuality = Math.min(QUALITY_HIGH, currentQuality.get() + 5);
            currentFrameRate.set(newFrameRate);
            currentQuality.set(newQuality);
        }
        
        // Update timer interval
        if (timer != null) {
            timer.cancel();
            timer = null;
            startCaptureTimer();
        }
    }
    
    private void captureScreen() {
        if (imageReader == null || !isCapturing.get()) {
            return;
        }
        
        Image image = null;
        droppedFrames++;
        try {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                // Convert the image to JPEG bytes
                byte[] jpegData = imageToByte(image);
                
                // Call the listener on the main thread
                if (captureListener != null && jpegData != null) {
                    final byte[] finalJpegData = jpegData;
                    handler.post(() -> captureListener.onScreenCaptureAvailable(finalJpegData));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing screen", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
}
