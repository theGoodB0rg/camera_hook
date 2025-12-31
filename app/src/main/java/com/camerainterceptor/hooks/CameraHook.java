// CameraHook.java 
package com.camerainterceptor.hooks;

import android.app.Activity;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the original Camera API (android.hardware.Camera)
 * This is the legacy Camera API, deprecated in API 21 but still used in many apps
 */
public class CameraHook {
    private static final String TAG = "CameraHook";
    private final HookDispatcher dispatcher;
    
    // Store references to ongoing camera operations
    private final Map<Camera, Activity> cameraToActivityMap = new HashMap<>();
    private final Map<Camera, PictureCallback> pendingCallbacks = new HashMap<>();
    
    public CameraHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }
    
    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing Camera API hooks");
            
            // Hook Camera.open() methods to track camera instances
            hookCameraOpen();
            
            // Hook Camera.startPreview() to know when preview starts
            hookStartPreview();
            
            // Hook Camera.takePicture() to intercept photo capture
            hookTakePicture();
            
            // Hook Camera.release() to clean up our tracking
            hookCameraRelease();
            
            Logger.i(TAG, "Camera API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize Camera API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Camera.open() methods to track camera instances
     */
    private void hookCameraOpen() {
        try {
            // Hook Camera.open()
            XposedHelpers.findAndHookMethod(Camera.class, "open", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Camera camera = (Camera) param.getResult();
                    if (camera != null) {
                        Logger.d(TAG, "Camera.open() called, instance: " + camera);
                        trackCameraInstance(camera);
                    }
                }
            });
            
            // Hook Camera.open(int)
            XposedHelpers.findAndHookMethod(Camera.class, "open", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Camera camera = (Camera) param.getResult();
                    int cameraId = (int) param.args[0];
                    if (camera != null) {
                        Logger.d(TAG, "Camera.open(" + cameraId + ") called, instance: " + camera);
                        trackCameraInstance(camera);
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.open: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Camera.startPreview() to know when preview starts
     */
    private void hookStartPreview() {
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "startPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Camera camera = (Camera) param.thisObject;
                    Logger.d(TAG, "Camera.startPreview() called, instance: " + camera);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.startPreview: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Camera.takePicture() to intercept photo capture
     */
    private void hookTakePicture() {
        try {
            // There are multiple overloaded takePicture methods, but they all call the most complete one
            Method takePictureMethod = XposedHelpers.findMethodExact(
                    Camera.class, "takePicture", 
                    Camera.ShutterCallback.class, 
                    Camera.PictureCallback.class, 
                    Camera.PictureCallback.class, 
                    Camera.PictureCallback.class);
            
            XposedBridge.hookMethod(takePictureMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Camera camera = (Camera) param.thisObject;
                    Logger.i(TAG, "Camera.takePicture() intercepted, instance: " + camera);
                    
                    // Get the JPEG callback (which is the last parameter)
                    PictureCallback jpegCallback = (PictureCallback) param.args[3];
                    
                    if (jpegCallback != null) {
                        // Store the callback for later use
                        pendingCallbacks.put(camera, jpegCallback);
                        
                        // Find the associated activity
                        Activity activity = findActivityForCamera(camera);
                        
                        if (activity != null) {
                            // Intercept the camera and show image picker instead
                            Logger.i(TAG, "Intercepting camera capture and launching gallery picker");
                            
                            // Prevent the original method from executing
                            param.setResult(null);
                            
                            // Launch gallery picker
                            interceptCameraCapture(camera, activity, jpegCallback);
                        } else {
                            Logger.w(TAG, "Could not find activity for camera instance, allowing original capture");
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.takePicture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Camera.release() to clean up our tracking
     */
    private void hookCameraRelease() {
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "release", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Camera camera = (Camera) param.thisObject;
                    Logger.d(TAG, "Camera.release() called, instance: " + camera);
                    
                    // Clean up our maps
                    cameraToActivityMap.remove(camera);
                    pendingCallbacks.remove(camera);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Camera.release: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Track camera instance by looking for its associated activity
     */
    private void trackCameraInstance(final Camera camera) {
        try {
            // Find the current activity that's using this camera
            // This is a best-effort approach and might not work in all cases
            Activity activity = dispatcher.runOnMainThreadAndWait(() -> {
                // Check all running activities to find the one that might be using this camera
                // This is a simplified version, in reality you'd need more sophisticated detection
                return null; // Placeholder, real implementation would be more complex
            });
            
            if (activity != null) {
                Logger.d(TAG, "Associated camera instance with activity: " + activity.getClass().getName());
                cameraToActivityMap.put(camera, activity);
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to track camera instance: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Find the activity associated with a camera instance
     */
    private Activity findActivityForCamera(Camera camera) {
        Activity activity = cameraToActivityMap.get(camera);
        
        if (activity == null) {
            // Try to find the current foreground activity as a fallback
            activity = dispatcher.runOnMainThreadAndWait(() -> {
                // Get current activity through reflection or other means
                // This would be implementation-specific
                return null; // Placeholder
            });
        }
        
        return activity;
    }
    
    /**
     * Intercept camera capture and launch gallery picker instead
     */
    private void interceptCameraCapture(final Camera camera, final Activity activity, 
                                         final PictureCallback originalCallback) {
        try {
            // Launch gallery picker through our dispatcher
            dispatcher.interceptCameraRequest(activity, new HookCallback() {
                @Override
                public void onImageSelected(byte[] imageData, ImageMetadata metadata) {
                    Logger.i(TAG, "User selected image from gallery, delivering to original callback");
                    
                    // Call the original callback with our image data
                    if (originalCallback != null) {
                        try {
                            originalCallback.onPictureTaken(imageData, camera);
                            Logger.i(TAG, "Successfully delivered gallery image to app");
                        } catch (Throwable t) {
                            Logger.e(TAG, "Error delivering image to app: " + t.getMessage());
                            Logger.logStackTrace(TAG, t);
                        }
                    }
                    
                    // Clean up
                    pendingCallbacks.remove(camera);
                }
                
                @Override
                public void onImageSelectionCancelled() {
                    Logger.i(TAG, "User cancelled image selection");
                    
                    // Clean up
                    pendingCallbacks.remove(camera);
                }
                
                @Override
                public void onError(Throwable throwable) {
                    Logger.e(TAG, "Error during image selection: " + throwable.getMessage());
                    Logger.logStackTrace(TAG, throwable);
                    
                    // Clean up
                    pendingCallbacks.remove(camera);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to intercept camera capture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
}