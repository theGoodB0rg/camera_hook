package com.camerainterceptor.hooks;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the Camera2 API (android.hardware.camera2) and ImageReader
 * 
 * Design principle: All hooks use non-blocking approaches with robust error handling.
 * If anything fails, the original camera flow continues uninterrupted.
 */
public class Camera2Hook {
    private static final String TAG = "Camera2Hook";
    private final HookDispatcher dispatcher;

    public Camera2Hook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera2 API hooks");

            // Hook ImageReader methods - safe, non-blocking
            hookImageReader();
            
            // Hook OnImageAvailableListener - safe wrapper with error handling
            hookOnImageAvailableListener();

            // Log CaptureSession activity - logging only, never blocks
            hookCaptureSession();

            Logger.i(TAG, "Camera2 API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera2 API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    private void hookOnImageAvailableListener() {
        try {
            // Hook the setOnImageAvailableListener to wrap the listener
            XposedBridge.hookAllMethods(ImageReader.class, "setOnImageAvailableListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[0] == null) return;
                    
                    ImageReader.OnImageAvailableListener originalListener = 
                            (ImageReader.OnImageAvailableListener) param.args[0];
                    
                    // Wrap the listener with robust error handling
                    ImageReader.OnImageAvailableListener wrappedListener = reader -> {
                        try {
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (dispatcher.isPackageAllowed(targetPackage) && dispatcher.isInjectionEnabled()) {
                                Logger.i(TAG, "OnImageAvailableListener triggered - image capture detected");
                            }
                        } catch (Throwable t) {
                            // Log but don't crash - just continue to original
                            Logger.e(TAG, "Error in wrapped listener logging: " + t.getMessage());
                        }
                        
                        // ALWAYS call original - never break the app's camera flow
                        try {
                            originalListener.onImageAvailable(reader);
                        } catch (Throwable t) {
                            // Re-throw app's own exceptions - don't swallow them
                            Logger.e(TAG, "Original listener threw exception: " + t.getMessage());
                            throw t;
                        }
                    };
                    
                    param.args[0] = wrappedListener;
                    Logger.d(TAG, "Wrapped OnImageAvailableListener");
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook OnImageAvailableListener: " + t.getMessage());
        }
    }

    private void hookImageReader() {
        try {
            // Hook acquireLatestImage
            XposedBridge.hookAllMethods(ImageReader.class, "acquireLatestImage", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processImage(param);
                }
            });

            // Hook acquireNextImage
            XposedBridge.hookAllMethods(ImageReader.class, "acquireNextImage", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processImage(param);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ImageReader: " + t.getMessage());
        }
    }

    private void processImage(XC_MethodHook.MethodHookParam param) {
        try {
            String targetPackage = dispatcher.getLoadPackageParam().packageName;
            if (!dispatcher.isPackageAllowed(targetPackage)) {
                return;
            }

            if (!dispatcher.isInjectionEnabled()) {
                return;
            }

            Image image = (Image) param.getResult();
            if (image == null)
                return;

            int format = image.getFormat();
            Logger.d(TAG, "ImageReader acquired image, format: " + format + 
                    " (JPEG=256, YUV_420_888=35)");

            // For JPEG images, try to replace the data (best effort, non-blocking)
            if (format == 256 || format == 0x100) {
                Logger.i(TAG, "Intercepted JPEG Image in ImageReader");

                byte[] fakeData = null;
                try {
                    fakeData = dispatcher.getPreSelectedImageBytes();
                } catch (Throwable t) {
                    Logger.w(TAG, "Failed to get injected image bytes: " + t.getMessage());
                    return; // Let original image through
                }
                
                if (fakeData == null || fakeData.length == 0) {
                    Logger.w(TAG, "No fake image bytes available to inject");
                    return; // Let original image through
                }

                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) {
                    Logger.w(TAG, "ImageReader planes empty; will intercept at file save level");
                    return;
                }

                ByteBuffer buffer = planes[0].getBuffer();
                if (buffer == null) {
                    Logger.w(TAG, "ImageReader buffer null; will intercept at file save level");
                    return;
                }

                // Try buffer modification - this often fails (read-only) and that's OK
                try {
                    if (!buffer.isReadOnly()) {
                        int capacity = buffer.capacity();
                        if (capacity >= fakeData.length) {
                            buffer.clear();
                            buffer.put(fakeData);
                            buffer.limit(fakeData.length);
                            Logger.i(TAG, "Injected " + fakeData.length + " bytes into Image buffer");
                        } else {
                            Logger.d(TAG, "Buffer too small (" + capacity + " < " + fakeData.length + "), will intercept at file save level");
                        }
                    } else {
                        Logger.d(TAG, "Buffer is read-only, will intercept at file save level");
                    }
                } catch (Throwable t) {
                    // This is expected for many devices/apps - not an error
                    Logger.d(TAG, "Buffer modification not possible (expected): " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            // Catch-all: never crash the app, just log and let original flow continue
            Logger.e(TAG, "Error processing Image (non-fatal): " + t.getMessage());
        }
    }

    private void hookCaptureSession() {
        try {
            // Hook capture methods for logging
            XposedBridge.hookAllMethods(CameraCaptureSession.class, "capture", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (dispatcher.isInjectionEnabled()) {
                        Logger.i(TAG, "Camera2 capture() called - photo capture in progress");
                    }
                }
            });
            
            XposedBridge.hookAllMethods(CameraCaptureSession.class, "captureBurst", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (dispatcher.isInjectionEnabled()) {
                        Logger.i(TAG, "Camera2 captureBurst() called");
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook capture session: " + t.getMessage());
        }
    }
}