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

            // We no longer strictly track Activities for UI launch
            // Instead, we hook ImageReader to intercept the data directly
            hookImageReader();

            // We can optionally hook CaptureSession to log requests
            hookCaptureSession();

            Logger.i(TAG, "Camera2 API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera2 API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
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
        if (!dispatcher.isInjectionEnabled())
            return;

        Image image = (Image) param.getResult();
        if (image == null)
            return;

        try {
            // Check if it's JPEG (format 0x100 = 256)
            // or YUV (0x23 = 35)
            int format = image.getFormat();

            // For now, we only support replacing JPEG images
            // YUV replacement requires complex conversion
            if (format == 256 || format == 0x100) {
                Logger.i(TAG, "Intercepted JPEG Image in ImageReader");

                byte[] fakeData = dispatcher.getPreSelectedImageBytes();
                if (fakeData != null) {
                    Image.Plane[] planes = image.getPlanes();
                    if (planes != null && planes.length > 0) {
                        ByteBuffer buffer = planes[0].getBuffer();

                        // We need to verify capacity.
                        // If the fake image is larger than the buffer, we can't write it easily
                        // without crashing or corruption, unless we can replace the buffer object
                        // itself
                        // (which is hard as it's often a direct buffer from native).
                        // However, usually capture buffers are large (max resolution).

                        if (buffer.capacity() >= fakeData.length) {
                            buffer.clear();
                            buffer.put(fakeData);
                            // Fill rest with 0? Or just leave it?
                            // buffer.limit(fakeData.length) might be safer if downstream respects it
                            buffer.limit(fakeData.length);
                            Logger.i(TAG, "Injected " + fakeData.length + " bytes into Image buffer");
                        } else {
                            Logger.e(TAG, "Buffer too small for injected image! Buffer: " + buffer.capacity()
                                    + ", Image: " + fakeData.length);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error processing Image: " + t.getMessage());
        }
    }

    private void hookCaptureSession() {
        try {
            // Hook capture just for logging
            Method captureMethod = XposedHelpers.findMethodExactIfExists(
                    CameraCaptureSession.class, "capture",
                    CaptureRequest.class, CameraCaptureSession.CaptureCallback.class, android.os.Handler.class);

            if (captureMethod != null) {
                XposedBridge.hookMethod(captureMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (dispatcher.isInjectionEnabled()) {
                            Logger.i(TAG,
                                    "Camera2 Capture Request detected (will be intercepted at ImageReader if possible)");
                            // We do NOT block it. We let the camera take a picture, then overwrite the
                            // data.
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook capture session: " + t.getMessage());
        }
    }
}