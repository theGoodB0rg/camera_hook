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

            // Hook ImageReader methods
            hookImageReader();
            
            // Hook OnImageAvailableListener
            hookOnImageAvailableListener();

            // Log CaptureSession activity
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
                    
                    // Wrap the listener
                    ImageReader.OnImageAvailableListener wrappedListener = reader -> {
                        String targetPackage = dispatcher.getLoadPackageParam().packageName;
                        if (dispatcher.isPackageAllowed(targetPackage) && dispatcher.isInjectionEnabled()) {
                            Logger.i(TAG, "OnImageAvailableListener triggered - image capture detected");
                        }
                        // Always call original - we'll intercept at save time
                        originalListener.onImageAvailable(reader);
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

        try {
            int format = image.getFormat();
            Logger.d(TAG, "ImageReader acquired image, format: " + format + 
                    " (JPEG=256, YUV_420_888=35)");

            // For JPEG images, try to replace the data
            if (format == 256 || format == 0x100) {
                Logger.i(TAG, "Intercepted JPEG Image in ImageReader");

                byte[] fakeData = dispatcher.getPreSelectedImageBytes();
                if (fakeData == null) {
                    Logger.w(TAG, "No fake image bytes available to inject");
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) {
                    Logger.w(TAG, "ImageReader planes empty; cannot inject");
                    return;
                }

                ByteBuffer buffer = planes[0].getBuffer();
                if (buffer == null) {
                    Logger.w(TAG, "ImageReader buffer null; cannot inject");
                    return;
                }

                try {
                    if (!buffer.isReadOnly()) {
                        int capacity = buffer.capacity();
                        if (capacity >= fakeData.length) {
                            buffer.clear();
                            buffer.put(fakeData);
                            buffer.limit(fakeData.length);
                            Logger.i(TAG, "Injected " + fakeData.length + " bytes into Image buffer");
                        } else {
                            Logger.w(TAG, "Buffer too small: " + capacity + " < " + fakeData.length);
                        }
                    } else {
                        Logger.d(TAG, "Buffer is read-only, will intercept at file save level");
                    }
                } catch (Throwable t) {
                    Logger.d(TAG, "Buffer modification failed (expected): " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error processing Image: " + t.getMessage());
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