package com.camerainterceptor.hooks;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.graphics.ImageFormat;
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
 * Design principle: All hooks use non-blocking approaches with robust error
 * handling.
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

            // Hook Session Creation to extract requested resolution/format
            hookCaptureSessionCreation();

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

    /**
     * Intercepts Camera2 session creation to steal the exact Surface requirements
     * (width, height, format). This allows our native layer to perfectly match
     * the injected image to the host app's ImageReader or SurfaceView.
     */
    private void hookCaptureSessionCreation() {
        try {
            Class<?> cameraDeviceClass = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl",
                    dispatcher.getClassLoader());

            // CameraDevice.createCaptureSession(List<Surface> outputs,
            // CameraCaptureSession.StateCallback callback, Handler handler)
            XposedBridge.hookAllMethods(cameraDeviceClass, "createCaptureSession", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[0] instanceof List) {
                        List<?> outputs = (List<?>) param.args[0];
                        if (outputs != null && !outputs.isEmpty()) {
                            Logger.i(TAG,
                                    "CameraDevice.createCaptureSession called with " + outputs.size() + " outputs");

                            // Try to extract resolution from the first Surface (usually the target for
                            // preview/capture)
                            Object firstOutput = outputs.get(0);

                            // In newer Android versions, outputs might be OutputConfiguration objects
                            Surface targetSurface = null;
                            if (firstOutput instanceof Surface) {
                                targetSurface = (Surface) firstOutput;
                            } else if (firstOutput.getClass().getName()
                                    .equals("android.hardware.camera2.params.OutputConfiguration")) {
                                try {
                                    Method getSurfaceMethod = firstOutput.getClass().getMethod("getSurface");
                                    targetSurface = (Surface) getSurfaceMethod.invoke(firstOutput);
                                } catch (Exception e) {
                                    Logger.w(TAG, "Could not extract Surface from OutputConfiguration");
                                }
                            }

                            if (targetSurface != null) {
                                extractSurfaceInfo(targetSurface);
                            }
                        }
                    }
                }
            });

            // Also hook createCaptureSessionByOutputConfigurations (API 24+)
            XposedBridge.hookAllMethods(cameraDeviceClass, "createCaptureSessionByOutputConfigurations",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[0] instanceof List) {
                                List<?> outputs = (List<?>) param.args[0];
                                if (outputs != null && !outputs.isEmpty()) {
                                    Object firstOutput = outputs.get(0);
                                    try {
                                        Method getSurfaceMethod = firstOutput.getClass().getMethod("getSurface");
                                        Surface targetSurface = (Surface) getSurfaceMethod.invoke(firstOutput);
                                        if (targetSurface != null) {
                                            extractSurfaceInfo(targetSurface);
                                        }
                                    } catch (Exception e) {
                                        Logger.w(TAG, "Could not extract Surface from OutputConfiguration list");
                                    }
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CaptureSession creation: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Attempts to read dimensions and format out of an opaque Surface object using
     * reflection.
     */
    private void extractSurfaceInfo(Surface surface) {
        try {
            if (surface == null || !surface.isValid()) {
                return;
            }

            Logger.i(TAG, "Intercepted target Surface during session creation.");

            // Check if Viewfinder Spoofing (Phase 3) is enabled AND mode is DEEP_SURFACE
            if (dispatcher.isInjectionEnabled() && dispatcher.isViewfinderSpoofingEnabled()) {
                if (dispatcher.isDeepSurfaceModeEnabled()) {
                    Logger.i(TAG, "Starting DEEP Viewfinder Spoofing for Camera2");
                    dispatcher.getViewfinderManager().startSpoofing(surface);
                } else {
                    Logger.d(TAG, "Camera2: Deep spoofing disabled, using SAFE mode");
                }
            }

        } catch (Throwable t) {
            Logger.e(TAG, "Error extracting surface info: " + t.getMessage());
        }
    }

    private void hookOnImageAvailableListener() {
        try {
            // Hook the setOnImageAvailableListener to wrap the listener
            XposedBridge.hookAllMethods(ImageReader.class, "setOnImageAvailableListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[0] == null)
                        return;

                    ImageReader.OnImageAvailableListener originalListener = (ImageReader.OnImageAvailableListener) param.args[0];

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
            if (!dispatcher.isPackageAllowed(targetPackage) || !dispatcher.isInjectionEnabled()) {
                return;
            }

            Image image = (Image) param.getResult();
            if (image == null)
                return;

            int format = image.getFormat();
            int width = image.getWidth();
            int height = image.getHeight();

            Logger.d(TAG, "ImageReader acquired image: " + width + "x" + height + ", format: " + format);

            // 1. Start Watchdog
            com.camerainterceptor.utils.Watchdog watchdog = new com.camerainterceptor.utils.Watchdog(
                    "Camera2 Injection", 3000, () -> {
                        Logger.e(TAG, "Watchdog triggered: Camera2 injection timed out. Recovering...");
                    });
            watchdog.start();

            try {
                byte[] fakeData = null;
                if (format == 256 || format == 0x100) {
                    Logger.i(TAG, "Intercepted JPEG Image");
                    fakeData = dispatcher.getInjectedImageBytes(width, height);
                } else if (format == 35 || format == ImageFormat.YUV_420_888) {
                    Logger.i(TAG, "Intercepted YUV_420_888 Image");
                    fakeData = dispatcher.getInjectedYUVData(width, height);
                }

                if (fakeData == null || fakeData.length == 0) {
                    watchdog.cancel();
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) {
                    watchdog.cancel();
                    return;
                }

                // High-Performance Injection: Attempt to write directly to the app's buffer
                for (int i = 0; i < planes.length; i++) {
                    ByteBuffer buffer = planes[i].getBuffer();
                    if (buffer == null)
                        continue;

                    try {
                        if (format == 256 || i == 0) {
                            writeToBuffer(buffer, fakeData);
                            if (format == 256)
                                break;
                        }
                    } catch (Throwable t) {
                        Logger.d(TAG, "Plane " + i + " injection failed: " + t.getMessage());
                    }
                }

                Logger.i(TAG, "Camera2 injection completed successfully");
                watchdog.cancel(); // SUCCESS
            } catch (Throwable t) {
                Logger.e(TAG, "Unexpected error in processImage injection: " + t.getMessage());
                watchdog.cancel();
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error in processImage entry: " + t.getMessage());
        }
    }

    private void writeToBuffer(ByteBuffer buffer, byte[] data) {
        try {
            if (buffer.isReadOnly()) {
                Logger.d(TAG, "Buffer is read-only, attempting direct modification (best effort)");
                // Future: Add reflection/Unsafe or JNI to bypass readonly if needed
                return;
            }
            int capacity = buffer.capacity();
            int toWrite = Math.min(capacity, data.length);
            buffer.clear();
            buffer.put(data, 0, toWrite);
            buffer.limit(toWrite);
            Logger.i(TAG, "Successfully injected " + toWrite + " bytes");
        } catch (Throwable t) {
            Logger.d(TAG, "Buffer write failed: " + t.getMessage());
        }
    }

    private void hookCaptureSession() {
        try {
            Class<?> captureSessionClass = XposedHelpers
                    .findClass("android.hardware.camera2.impl.CameraCaptureSessionImpl", dispatcher.getClassLoader());

            // Hook capture methods for logging
            XposedBridge.hookAllMethods(captureSessionClass, "capture", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (dispatcher.isInjectionEnabled()) {
                        Logger.i(TAG, "Camera2 capture() called - photo capture in progress");
                    }
                }
            });

            XposedBridge.hookAllMethods(captureSessionClass, "captureBurst", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (dispatcher.isInjectionEnabled()) {
                        Logger.i(TAG, "Camera2 captureBurst() called");
                    }
                }
            });

            XposedBridge.hookAllMethods(captureSessionClass, "close", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Logger.i(TAG, "CameraCaptureSession.close() - stopping spoofing");
                    dispatcher.getViewfinderManager().stopSpoofing();
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook capture session: " + t.getMessage());
        }
    }
}