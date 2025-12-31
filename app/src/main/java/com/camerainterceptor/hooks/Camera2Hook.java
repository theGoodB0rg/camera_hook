package com.camerainterceptor.hooks;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.view.Surface;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the Camera2 API (android.hardware.camera2)
 * This is the modern Camera API introduced in API 21
 */
public class Camera2Hook {
    private static final String TAG = "Camera2Hook";
    private final HookDispatcher dispatcher;

    // Maps to track camera devices, sessions, and capture requests
    private final Map<CameraDevice, Activity> deviceToActivityMap = new HashMap<>();
    private final Map<CameraCaptureSession, CameraDevice> sessionToDeviceMap = new HashMap<>();
    private final Map<CaptureRequest, CameraCaptureSession> requestToSessionMap = new HashMap<>();

    public Camera2Hook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera2 API hooks");

            // Hook CameraManager.openCamera to track camera devices
            hookCameraOpen();

            // Hook CameraDevice.createCaptureSession to track sessions
            hookCreateCaptureSession();

            // Hook CameraCaptureSession.capture to intercept photo capture
            hookCapture();

            // Hook CameraDevice.close to clean up
            hookCameraClose();

            Logger.i(TAG, "Camera2 API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera2 API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Hook CameraManager.openCamera to track camera devices
     */
    private void hookCameraOpen() {
        try {
            // Find CameraManager class
            Class<?> cameraManagerClass = XposedHelpers.findClass(
                    "android.hardware.camera2.CameraManager", dispatcher.getClassLoader());

            // There are multiple overloaded openCamera methods in different API levels

            // API 21-22: openCamera(String, CameraDevice.StateCallback, Handler)
            Method openCameraMethod = XposedHelpers.findMethodExactIfExists(
                    cameraManagerClass, "openCamera",
                    String.class, CameraDevice.StateCallback.class, Handler.class);

            if (openCameraMethod != null) {
                XposedBridge.hookMethod(openCameraMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String cameraId = (String) param.args[0];
                        Logger.d(TAG, "CameraManager.openCamera called for camera: " + cameraId);

                        // Hook the state callback to track when camera is opened
                        CameraDevice.StateCallback originalCallback = (CameraDevice.StateCallback) param.args[1];

                        if (originalCallback != null) {
                            param.args[1] = createStateCallbackProxy(originalCallback);
                        }
                    }
                });
            }

            // API 23+: openCamera(String, Executor, CameraDevice.StateCallback)
            Method openCameraExecutorMethod = XposedHelpers.findMethodExactIfExists(
                    cameraManagerClass, "openCamera",
                    String.class, Executor.class, CameraDevice.StateCallback.class);

            if (openCameraExecutorMethod != null) {
                XposedBridge.hookMethod(openCameraExecutorMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String cameraId = (String) param.args[0];
                        Logger.d(TAG, "CameraManager.openCamera(Executor) called for camera: " + cameraId);

                        // Hook the state callback to track when camera is opened
                        CameraDevice.StateCallback originalCallback = (CameraDevice.StateCallback) param.args[2];

                        if (originalCallback != null) {
                            param.args[2] = createStateCallbackProxy(originalCallback);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CameraManager.openCamera: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Create a proxy for CameraDevice.StateCallback to intercept camera open events
     */
    private CameraDevice.StateCallback createStateCallbackProxy(final CameraDevice.StateCallback original) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Logger.d(TAG, "Camera device opened: " + camera.getId());

                // Track this camera device with the current activity
                trackCameraDevice(camera);

                // Call the original callback
                original.onOpened(camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Logger.d(TAG, "Camera device disconnected: " + camera.getId());
                deviceToActivityMap.remove(camera);
                original.onDisconnected(camera);
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Logger.d(TAG, "Camera device error: " + camera.getId() + ", code: " + error);
                deviceToActivityMap.remove(camera);
                original.onError(camera, error);
            }

            @Override
            public void onClosed(CameraDevice camera) {
                Logger.d(TAG, "Camera device closed: " + camera.getId());
                deviceToActivityMap.remove(camera);

                // Check if the original callback has onClosed method (added in API 23)
                try {
                    Method onClosedMethod = original.getClass().getDeclaredMethod("onClosed", CameraDevice.class);
                    onClosedMethod.invoke(original, camera);
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist in this API level, ignore
                } catch (Exception e) {
                    Logger.e(TAG, "Error calling original onClosed: " + e.getMessage());
                }
            }
        };
    }

    /**
     * Hook CameraDevice.createCaptureSession to track sessions
     */
    private void hookCreateCaptureSession() {
        try {
            // There are different createCaptureSession methods in different API levels

            // API 21-22: createCaptureSession(List<Surface>,
            // CameraCaptureSession.StateCallback, Handler)
            Method createSessionMethod = XposedHelpers.findMethodExactIfExists(
                    CameraDevice.class, "createCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class);

            if (createSessionMethod != null) {
                XposedBridge.hookMethod(createSessionMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        CameraDevice device = (CameraDevice) param.thisObject;
                        Logger.d(TAG, "CameraDevice.createCaptureSession called for device: " + device.getId());

                        // Hook the state callback to track when session is configured
                        CameraCaptureSession.StateCallback originalCallback = (CameraCaptureSession.StateCallback) param.args[1];

                        if (originalCallback != null) {
                            param.args[1] = createSessionStateCallbackProxy(originalCallback, device);
                        }
                    }
                });
            }

            // API 23+: createCaptureSessionByOutputConfigurations
            // API 28+: createCaptureSession with SessionConfiguration
            // These would be handled similarly in a complete implementation
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CameraDevice.createCaptureSession: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Create a proxy for CameraCaptureSession.StateCallback to track sessions
     */
    private CameraCaptureSession.StateCallback createSessionStateCallbackProxy(
            final CameraCaptureSession.StateCallback original, final CameraDevice device) {

        return new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                Logger.d(TAG, "Capture session configured: " + session);
                sessionToDeviceMap.put(session, device);
                original.onConfigured(session);
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                Logger.d(TAG, "Capture session configuration failed: " + session);
                original.onConfigureFailed(session);
            }

            @Override
            public void onClosed(CameraCaptureSession session) {
                Logger.d(TAG, "Capture session closed: " + session);
                sessionToDeviceMap.remove(session);

                // Check if the original callback has onClosed method (added in API 21)
                try {
                    Method onClosedMethod = original.getClass().getDeclaredMethod(
                            "onClosed", CameraCaptureSession.class);
                    onClosedMethod.invoke(original, session);
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist in this API level, ignore
                } catch (Exception e) {
                    Logger.e(TAG, "Error calling original onClosed: " + e.getMessage());
                }
            }

            // Handle other methods in the StateCallback interface if needed
        };
    }

    /**
     * Hook CameraCaptureSession.capture to intercept photo capture
     */
    private void hookCapture() {
        try {
            // Hook the main capture method that others delegate to:
            // capture(CaptureRequest, CameraCaptureSession.CaptureCallback, Handler)
            Method captureMethod = XposedHelpers.findMethodExactIfExists(
                    CameraCaptureSession.class, "capture",
                    CaptureRequest.class, CameraCaptureSession.CaptureCallback.class, Handler.class);

            if (captureMethod != null) {
                XposedBridge.hookMethod(captureMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        CameraCaptureSession session = (CameraCaptureSession) param.thisObject;
                        CaptureRequest request = (CaptureRequest) param.args[0];

                        // Store the request for later reference
                        requestToSessionMap.put(request, session);

                        // Check if this is a still capture request
                        if (isStillCaptureRequest(request)) {
                            Logger.i(TAG, "Detected still image capture request");

                            // Get the associated device and activity
                            CameraDevice device = sessionToDeviceMap.get(session);
                            if (device != null) {
                                Activity activity = deviceToActivityMap.get(device);

                                if (activity != null) {
                                    // Get the original callback to deliver our fake image later
                                    CameraCaptureSession.CaptureCallback originalCallback = (CameraCaptureSession.CaptureCallback) param.args[1];

                                    // Create a proxy callback
                                    param.args[1] = createCaptureCallbackProxy(originalCallback, request, session);

                                    // Intercept the camera and show image picker
                                    interceptCamera2Capture(activity, originalCallback, request, session);
                                } else {
                                    Logger.w(TAG,
                                            "Could not find activity for camera device, allowing original capture");
                                }
                            } else {
                                Logger.w(TAG, "Could not find device for session, allowing original capture");
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CameraCaptureSession.capture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Create a proxy for CameraCaptureSession.CaptureCallback
     */
    private CameraCaptureSession.CaptureCallback createCaptureCallbackProxy(
            final CameraCaptureSession.CaptureCallback original,
            final CaptureRequest request,
            final CameraCaptureSession session) {

        return new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp,
                    long frameNumber) {
                Logger.d(TAG, "Capture started");
                if (original != null) {
                    original.onCaptureStarted(session, request, timestamp, frameNumber);
                }
            }

            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                    TotalCaptureResult result) {
                Logger.d(TAG, "Capture completed");

                // We'll let our intercept method handle calling the original callback
                // when the user selects an image from the gallery
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Logger.d(TAG, "Capture failed");
                if (original != null) {
                    original.onCaptureFailed(session, request, failure);
                }
            }

            // Implement other methods from CaptureCallback if needed
        };
    }

    /**
     * Hook CameraDevice.close to clean up our tracking
     */
    private void hookCameraClose() {
        try {
            XposedHelpers.findAndHookMethod(CameraDevice.class, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    CameraDevice device = (CameraDevice) param.thisObject;
                    Logger.d(TAG, "CameraDevice.close() called, device: " + device.getId());

                    // Clean up our maps
                    deviceToActivityMap.remove(device);

                    // Clean up sessions associated with this device
                    for (Map.Entry<CameraCaptureSession, CameraDevice> entry : new HashMap<>(sessionToDeviceMap)
                            .entrySet()) {
                        if (entry.getValue() == device) {
                            sessionToDeviceMap.remove(entry.getKey());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CameraDevice.close: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Check if a capture request is for a still image (photo)
     */
    private boolean isStillCaptureRequest(CaptureRequest request) {
        try {
            // Check the capture intent
            Integer captureIntent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT);

            // CONTROL_CAPTURE_INTENT_STILL_CAPTURE = 2
            return captureIntent != null && captureIntent == 2;
        } catch (Throwable t) {
            Logger.e(TAG, "Error checking capture intent: " + t.getMessage());
            return false;
        }
    }

    /**
     * Track camera device by finding its associated activity
     */
    private void trackCameraDevice(final CameraDevice device) {
        try {
            // Try to find the current foreground activity
            Activity activity = dispatcher.runOnMainThreadAndWait(() -> {
                // Get current activity through reflection or other means
                // This would be implementation-specific
                return null; // Placeholder
            });

            if (activity != null) {
                Logger.d(TAG, "Associated camera device with activity: " + activity.getClass().getName());
                deviceToActivityMap.put(device, activity);
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to track camera device: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    /**
     * Intercept Camera2 capture and launch gallery picker instead
     */
    private void interceptCamera2Capture(final Activity activity,
            final CameraCaptureSession.CaptureCallback originalCallback,
            final CaptureRequest request,
            final CameraCaptureSession session) {
        try {
            // Launch gallery picker through our dispatcher
            dispatcher.interceptCameraRequest(activity, new HookCallback() {
                @Override
                public void onImageSelected(byte[] imageData, ImageMetadata metadata) {
                    Logger.i(TAG, "User selected image from gallery, creating fake Camera2 capture result");

                    try {
                        // Create a fake capture result
                        // In a real implementation, this would require more work to create
                        // a proper TotalCaptureResult that looks legitimate

                        // For now, we'll just log this. In a complete implementation,
                        // you would need to create a fake TotalCaptureResult using reflection
                        // and then call originalCallback.onCaptureCompleted()

                        Logger.i(TAG, "Would deliver a fake TotalCaptureResult here in a complete implementation");

                        // Clean up
                        requestToSessionMap.remove(request);
                    } catch (Throwable t) {
                        Logger.e(TAG, "Error delivering fake capture result: " + t.getMessage());
                        Logger.logStackTrace(TAG, t);
                    }
                }

                @Override
                public void onImageSelectionCancelled() {
                    Logger.i(TAG, "User cancelled image selection");

                    // Clean up
                    requestToSessionMap.remove(request);
                }

                @Override
                public void onError(Throwable throwable) {
                    Logger.e(TAG, "Error during image selection: " + throwable.getMessage());
                    Logger.logStackTrace(TAG, throwable);

                    // Clean up
                    requestToSessionMap.remove(request);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to intercept Camera2 capture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
}