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
            // CameraDevice.createCaptureSession(List<Surface> outputs,
            // CameraCaptureSession.StateCallback callback, Handler handler)
            XposedBridge.hookAllMethods(CameraDevice.class, "createCaptureSession", new XC_MethodHook() {
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
            XposedBridge.hookAllMethods(CameraDevice.class, "createCaptureSessionByOutputConfigurations",
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
            // Android Surfaces have native pointers. We can try to read the
            // dimensions/format
            // via hidden/internal methods if accessible, or just log that we grabbed the
            // surface.
            // Since Surface inspection pure Java is heavily restricted by Google,
            // a full implementation requires our Native library to sniff the ANativeWindow.
            // For now, we log the acquisition and prepare HookState.

            Logger.i(TAG, "Successfully intercepted target Surface during session creation.");

            // TODO: In Phase 2, we will pass this Surface object down to our C++ library
            // via JNI (ANativeWindow_fromSurface) to precisely read its configured
            // width/height/format.
            // For now, we flag that we have a target surface available.

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
                            Logger.d(TAG, "Buffer too small (" + capacity + " < " + fakeData.length
                                    + "), will intercept at file save level");
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