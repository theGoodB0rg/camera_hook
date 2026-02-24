package com.camerainterceptor.processor;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the "Live Preview" spoofing thread.
 * It intercepts an app's Surface and draws injected images at ~30 FPS
 * to convince the user (and the app) that the camera is seeing the fake image.
 */
public class ViewfinderManager {
    private static final String TAG = "ViewfinderManager";
    private static final long FRAME_DELAY_MS = 33; // ~30 FPS

    private final HookDispatcher dispatcher;
    private final HandlerThread spoofThread;
    private final Handler spoofHandler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Surface targetSurface;
    private Bitmap currentFrame;

    public ViewfinderManager(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.spoofThread = new HandlerThread("ViewfinderSpoofer");
        this.spoofThread.start();
        this.spoofHandler = new Handler(this.spoofThread.getLooper());
    }

    /**
     * Starts spoofing frames to the provided Surface.
     */
    public synchronized void startSpoofing(Surface surface) {
        if (surface == null || !surface.isValid()) {
            Logger.w(TAG, "Cannot start spoofing: Surface is null or invalid");
            return;
        }

        this.targetSurface = surface;
        if (isRunning.compareAndSet(false, true)) {
            Logger.i(TAG, "Starting Viewfinder Spoofing loop");
            spoofHandler.post(this::renderLoop);
        }
    }

    /**
     * Stops the spoofing loop.
     */
    public synchronized void stopSpoofing() {
        if (isRunning.compareAndSet(true, false)) {
            Logger.i(TAG, "Stopping Viewfinder Spoofing loop");
            spoofHandler.removeCallbacksAndMessages(null);
            this.targetSurface = null;
        }
    }

    private void renderLoop() {
        if (!isRunning.get() || targetSurface == null || !targetSurface.isValid()) {
            isRunning.set(false);
            return;
        }

        try {
            // 1. Get current selected image from dispatcher
            Bitmap frame = dispatcher.getPreSelectedBitmap();

            if (frame != null) {
                // 2. Inject frame directly via Native JNI
                NativeImageProcessor.injectFrameToSurface(frame, targetSurface);
            }

        } catch (Throwable t) {
            Logger.e(TAG, "Error in render loop: " + t.getMessage());
        }

        // Schedule next frame
        if (isRunning.get()) {
            spoofHandler.postDelayed(this::renderLoop, FRAME_DELAY_MS);
        }
    }

    /**
     * Cleanup resources when the object is destroyed.
     */
    public void release() {
        stopSpoofing();
        spoofThread.quitSafely();
    }
}
