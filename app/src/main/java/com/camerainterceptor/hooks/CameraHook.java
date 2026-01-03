package com.camerainterceptor.hooks;

import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the original Camera API (android.hardware.Camera)
 * Legacy API support with silent injection and robust error handling.
 * 
 * Design principle: If injection fails for ANY reason, gracefully fall back
 * to letting the original capture proceed. Never crash or hang the app.
 * 
 * IMPORTANT: After injection, we must restart the camera preview so apps can
 * take another photo. Many apps expect the preview to resume after capture.
 */
public class CameraHook {
    private static final String TAG = "CameraHook";
    private static final long INJECTION_TIMEOUT_MS = 2000; // 2 second timeout
    private static final long PREVIEW_RESTART_DELAY_MS = 100; // Small delay before restarting preview
    
    private final HookDispatcher dispatcher;
    private final Handler mainHandler;

    public CameraHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera API hooks");
            hookTakePicture();
            Logger.i(TAG, "Camera API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Hook Camera.takePicture() to intercept photo capture
     * Uses robust error handling - if anything fails, original capture proceeds
     */
    private void hookTakePicture() {
        try {
            // There are multiple overloaded takePicture methods, but they all call the most
            // complete one
            Method takePictureMethod = XposedHelpers.findMethodExactIfExists(
                    Camera.class, "takePicture",
                    Camera.ShutterCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class);

            if (takePictureMethod == null)
                return;

            XposedBridge.hookMethod(takePictureMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String targetPackage = dispatcher.getLoadPackageParam().packageName;
                    if (!dispatcher.isPackageAllowed(targetPackage)) {
                        Logger.d(TAG, "Package not allowed for injection: " + targetPackage);
                        return;
                    }

                    if (dispatcher.isProfilingEnabled()) {
                        Logger.i(TAG, "[PROFILE] Camera.takePicture invoked. stack=" + buildStack());
                        return; // allow normal capture
                    }

                    if (!dispatcher.isInjectionEnabled()) {
                        Logger.d(TAG, "Injection disabled or no image available");
                        return;
                    }

                    Camera camera = (Camera) param.thisObject;
                    PictureCallback jpegCallback = (PictureCallback) param.args[3];

                    if (jpegCallback == null) {
                        Logger.d(TAG, "No JPEG callback provided, letting original proceed");
                        return;
                    }

                    Logger.i(TAG, "Camera.takePicture() intercepted, attempting injection...");

                    // Try to get injected image with timeout - NEVER block indefinitely
                    AtomicReference<byte[]> imageDataRef = new AtomicReference<>(null);
                    AtomicBoolean gotImage = new AtomicBoolean(false);
                    AtomicReference<Throwable> errorRef = new AtomicReference<>(null);
                    CountDownLatch latch = new CountDownLatch(1);

                    try {
                        dispatcher.getPreSelectedImage(new HookCallback() {
                            @Override
                            public void onImageSelected(byte[] imageData, ImageMetadata metadata) {
                                if (imageData != null && imageData.length > 0) {
                                    imageDataRef.set(imageData);
                                    gotImage.set(true);
                                }
                                latch.countDown();
                            }

                            @Override
                            public void onImageSelectionCancelled() {
                                Logger.w(TAG, "Image selection cancelled");
                                latch.countDown();
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Logger.e(TAG, "Error getting image: " + throwable.getMessage());
                                errorRef.set(throwable);
                                latch.countDown();
                            }
                        });

                        // Wait with timeout - never block forever
                        boolean completed = latch.await(INJECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                        if (!completed) {
                            Logger.w(TAG, "Injection timed out after " + INJECTION_TIMEOUT_MS + "ms, letting original capture proceed");
                            return; // Let original takePicture run
                        }

                        if (!gotImage.get() || imageDataRef.get() == null) {
                            Logger.w(TAG, "Failed to get injected image, letting original capture proceed");
                            return; // Let original takePicture run
                        }

                        // SUCCESS - we have valid image data, now block original and inject
                        final byte[] imageData = imageDataRef.get();
                        
                        // Get all callbacks for proper simulation
                        final ShutterCallback shutterCallback = (ShutterCallback) param.args[0];
                        final PictureCallback rawCallback = (PictureCallback) param.args[1];
                        final PictureCallback postviewCallback = (PictureCallback) param.args[2];
                        
                        // Block original method ONLY after we confirm we have valid data
                        param.setResult(null);
                        Logger.i(TAG, "Blocking original capture, injecting " + imageData.length + " bytes");

                        // Deliver callbacks in proper order with error handling
                        // The sequence should be: shutter -> raw -> postview -> jpeg -> restart preview
                        Runnable deliverCallbacks = () -> {
                            try {
                                // 1. Shutter callback (simulates the click sound moment)
                                if (shutterCallback != null) {
                                    try {
                                        shutterCallback.onShutter();
                                        Logger.d(TAG, "Delivered shutter callback");
                                    } catch (Throwable t) {
                                        Logger.w(TAG, "Shutter callback error (non-fatal): " + t.getMessage());
                                    }
                                }
                                
                                // 2. Raw callback (usually null, but call if provided)
                                if (rawCallback != null) {
                                    try {
                                        rawCallback.onPictureTaken(null, camera);
                                        Logger.d(TAG, "Delivered raw callback");
                                    } catch (Throwable t) {
                                        Logger.w(TAG, "Raw callback error (non-fatal): " + t.getMessage());
                                    }
                                }
                                
                                // 3. Postview callback (usually null, but call if provided)
                                if (postviewCallback != null) {
                                    try {
                                        postviewCallback.onPictureTaken(null, camera);
                                        Logger.d(TAG, "Delivered postview callback");
                                    } catch (Throwable t) {
                                        Logger.w(TAG, "Postview callback error (non-fatal): " + t.getMessage());
                                    }
                                }
                                
                                // 4. JPEG callback with injected data (the main one)
                                jpegCallback.onPictureTaken(imageData, camera);
                                Logger.i(TAG, "Successfully delivered injected image to app");
                                
                                // 5. Restart preview after a small delay
                                // This is CRITICAL - many apps expect preview to restart after capture
                                // Without this, the shutter button may not re-enable
                                mainHandler.postDelayed(() -> {
                                    try {
                                        camera.startPreview();
                                        Logger.d(TAG, "Restarted camera preview after injection");
                                    } catch (Throwable t) {
                                        // Preview restart failed - this is OK, app may handle it
                                        Logger.w(TAG, "Preview restart failed (app may handle): " + t.getMessage());
                                    }
                                }, PREVIEW_RESTART_DELAY_MS);
                                
                            } catch (Throwable t) {
                                Logger.e(TAG, "Error delivering image to app: " + t.getMessage());
                                // Try to restart preview even on error
                                try {
                                    camera.startPreview();
                                } catch (Throwable ignored) {}
                            }
                        };
                        
                        // Execute on main thread if not already there
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            deliverCallbacks.run();
                        } else {
                            mainHandler.post(deliverCallbacks);
                        }

                    } catch (InterruptedException e) {
                        Logger.w(TAG, "Injection interrupted, letting original capture proceed");
                        Thread.currentThread().interrupt();
                        return; // Let original takePicture run
                    } catch (Throwable t) {
                        Logger.e(TAG, "Unexpected error during injection: " + t.getMessage());
                        Logger.logStackTrace(TAG, t);
                        return; // Let original takePicture run
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.takePicture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private String buildStack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int count = 0;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.contains("CameraHook")) {
                continue;
            }
            if (count > 6) break;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(el.getClassName()).append("#").append(el.getMethodName()).append(":").append(el.getLineNumber());
            count++;
        }
        return sb.toString();
    }
}