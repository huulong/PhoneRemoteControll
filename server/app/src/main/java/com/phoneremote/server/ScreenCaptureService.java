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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
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
        super.onDestroy();
        instance = null;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void startForeground() {
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
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (imageReader != null && isCapturing.get()) {
                    captureScreen();
                }
            }
        }, 0, 200);  // Capture every 200ms (5fps) - adjust as needed for performance
    }
    
    private void captureScreen() {
        if (imageReader == null || !isCapturing.get()) {
            return;
        }
        
        Image image = null;
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
    
    private byte[] imageToByte(Image image) {
        // Try to compress the image to JPEG format
        if (image == null) return null;
        
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        
        // Create bitmap from buffer
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        
        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);
        
        // Crop bitmap to correct dimensions
        Bitmap croppedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, image.getWidth(), image.getHeight()
        );
        
        // Compress to JPEG with reduced quality
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
        
        // Clean up
        bitmap.recycle();
        croppedBitmap.recycle();
        
        return baos.toByteArray();
    }
}
