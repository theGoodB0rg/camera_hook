// CameraxHook.java 
package com.camerainterceptor.hooks;

import android.app.Activity;
import android.content.Context;
import android.util.Size;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils.ImageMetadata;
import com.camerainterceptor.utils.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for the CameraX API (androidx.camera)
 * This is the latest camera API provided by Android Jetpack
 */
public class CameraxHook {
    private static final String TAG = "CameraXHook";
    private final HookDispatcher dispatcher;
    
    // Maps to track CameraX components
    private final Map<Object, Activity> cameraProviderToActivityMap = new HashMap<>();
    
    public CameraxHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }
    
    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing CameraX API hooks");
            
            // Hook ProcessCameraProvider initialization
            hookCameraProvider();
            
            // Hook ImageCapture to intercept photo requests
            hookImageCapture();
            
            Logger.i(TAG, "CameraX API hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize CameraX API hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook the ProcessCameraProvider (or older CameraX.Provider) initialization
     */
    private void hookCameraProvider() {
        try {
            // Try to find ProcessCameraProvider class (newer CameraX)
            Class<?> providerClass = XposedHelpers.findClassIfExists(
                    "androidx.camera.lifecycle.ProcessCameraProvider", dispatcher.getClassLoader());
            
            if (providerClass == null) {
                // Try the older CameraX provider
                providerClass = XposedHelpers.findClassIfExists(
                        "androidx.camera.core.CameraX$Provider", dispatcher.getClassLoader());
            }
            
            if (providerClass != null) {
                // Try to hook getInstance method
                Method getInstanceMethod = XposedHelpers.findMethodExactIfExists(
                        providerClass, "getInstance", Context.class);
                
                if (getInstanceMethod != null) {
                    XposedBridge.hookMethod(getInstanceMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Context context = (Context) param.args[0];
                            Logger.d(TAG, "CameraX Provider.getInstance called with context: " + context);
                            
                            // Try to find associated activity
                            if (context instanceof Activity) {
                                Logger.d(TAG, "Context is an Activity: " + context.getClass().getName());
                            }
                        }
                        
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object provider = param.getResult();
                            if (provider != null) {
                                Logger.d(TAG, "Got CameraX provider: " + provider);
                                
                                // If the context is an activity, track this provider
                                Context context = (Context) param.args[0];
                                if (context instanceof Activity) {
                                    cameraProviderToActivityMap.put(provider, (Activity) context);
                                }
                            }
                        }
                    });
                } else {
                    Logger.w(TAG, "Could not find getInstance method for CameraX provider");
                }
            } else {
                Logger.w(TAG, "Could not find CameraX provider class");
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook CameraX provider: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook the ImageCapture class to intercept photo capture requests
     */
    private void hookImageCapture() {
        try {
            // Find the ImageCapture class
            Class<?> imageCaptureClass = XposedHelpers.findClassIfExists(
                    "androidx.camera.core.ImageCapture", dispatcher.getClassLoader());
            
            if (imageCaptureClass != null) {
                Logger.d(TAG, "Found ImageCapture class");
                
                // Hook takePicture method
                // The method signature changed across CameraX versions, so try both
                
                // Newer: takePicture(Executor, ImageCapture.OnImageCapturedCallback)
                Method takePictureCallbackMethod = XposedHelpers.findMethodExactIfExists(
                        imageCaptureClass, "takePicture",
                        Executor.class, XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OnImageCapturedCallback",
                                dispatcher.getClassLoader()));
                
                if (takePictureCallbackMethod != null) {
                    XposedBridge.hookMethod(takePictureCallbackMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Logger.i(TAG, "ImageCapture.takePicture(Callback) intercepted");
                            interceptCameraXCapture(param);
                        }
                    });
                }
                
                // Newer: takePicture(OutputFileOptions, Executor, ImageCapture.OnImageSavedCallback)
                Method takePictureSavedMethod = XposedHelpers.findMethodExactIfExists(
                        imageCaptureClass, "takePicture",
                        XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OutputFileOptions",
                                dispatcher.getClassLoader()),
                        Executor.class,
                        XposedHelpers.findClassIfExists(
                                "androidx.camera.core.ImageCapture$OnImageSavedCallback",
                                dispatcher.getClassLoader()));
                
                if (takePictureSavedMethod != null) {
                    XposedBridge.hookMethod(takePictureSavedMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Logger.i(TAG, "ImageCapture.takePicture(OutputFileOptions) intercepted");
                            interceptCameraXCapture(param);
                        }
                    });
                }
                
                // Additional hooks for older CameraX versions would go here
            } else {
                Logger.w(TAG, "Could not find ImageCapture class");
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ImageCapture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Intercept CameraX capture request
     */
    private void interceptCameraXCapture(XC_MethodHook.MethodHookParam param) {
        try {
            // Try to find an activity to use for the image picker
            Activity activity = findActivityForCameraX(param.thisObject);
            
            if (activity != null) {
                Logger.i(TAG, "Found activity for CameraX: " + activity.getClass().getName());
                
                // Prevent the original method from executing
                param.setResult(null);
                
                // Extract the callback - the position depends on the method signature
                // For takePicture(Executor, Callback), it's the second parameter
                // For takePicture(OutputFileOptions, Executor, Callback), it's the third parameter
                final Object callback;
                if (param.args.length == 2) {
                    callback = param.args[1]; // For OnImageCapturedCallback
                } else if (param.args.length == 3) {
                    callback = param.args[2]; // For OnImageSavedCallback
                } else {
                    Logger.w(TAG, "Unexpected parameter count: " + param.args.length);
                    return;
                }
                
                // Launch gallery picker
                interceptCameraXCapture(activity, param.thisObject, callback);
            } else {
                Logger.w(TAG, "Could not find activity for CameraX, allowing original capture");
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to intercept CameraX capture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Find an activity for a CameraX component
     */
    private Activity findActivityForCameraX(Object cameraxComponent) {
        try {
            // Try to find in our maps
            for (Object provider : cameraProviderToActivityMap.keySet()) {
                Activity activity = cameraProviderToActivityMap.get(provider);
                if (activity != null) {
                    return activity;
                }
            }
            
            // Fallback to current foreground activity
            return dispatcher.runOnMainThreadAndWait(() -> {
                // Get current activity through reflection or other means
                // This would be implementation-specific
                return null; // Placeholder
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to find activity for CameraX: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * Intercept CameraX capture and launch gallery picker instead
     */
    private void interceptCameraXCapture(final Activity activity,
                                        final Object imageCapture,
                                        final Object originalCallback) {
        try {
            // Launch gallery picker through our dispatcher
            dispatcher.interceptCameraRequest(activity, new HookCallback() {
                @Override
                public void onImageSelected(byte[] imageData, ImageMetadata metadata) {
                    Logger.i(TAG, "User selected image from gallery, creating fake CameraX image");
                    
                    try {
                        // This would require creating a fake CameraX image result
                        // The implementation depends on whether we're dealing with
                        // OnImageCapturedCallback or OnImageSavedCallback
                        
                        if (originalCallback != null) {
                            Class<?> callbackClass = originalCallback.getClass();
                            
                            // Check if it's an OnImageCapturedCallback
                            Class<?> capturedCallbackClass = XposedHelpers.findClassIfExists(
                                    "androidx.camera.core.ImageCapture$OnImageCapturedCallback",
                                    dispatcher.getClassLoader());
                            
                            if (capturedCallbackClass != null && 
                                    capturedCallbackClass.isAssignableFrom(callbackClass)) {
                                Logger.d(TAG, "Handling OnImageCapturedCallback");
                                
                                // This would need to create a fake ImageProxy
                                // In a complete implementation, this is where you'd create
                                // a synthetic ImageProxy to pass to onCaptureSuccess
                                
                                Logger.i(TAG, "Would deliver fake ImageProxy here in complete implementation");
                            }
                            
                            // Check if it's an OnImageSavedCallback
                            Class<?> savedCallbackClass = XposedHelpers.findClassIfExists(
                                    "androidx.camera.core.ImageCapture$OnImageSavedCallback",
                                    dispatcher.getClassLoader());
                            
                            if (savedCallbackClass != null && 
                                    savedCallbackClass.isAssignableFrom(callbackClass)) {
                                Logger.d(TAG, "Handling OnImageSavedCallback");
                                
                                // For OnImageSavedCallback, we would need to create a fake OutputFileResults
                                // and save the image data to the output location
                                
                                Logger.i(TAG, "Would save image to output file in complete implementation");
                            }
                        }
                    } catch (Throwable t) {
                        Logger.e(TAG, "Error delivering image to CameraX callback: " + t.getMessage());
                        Logger.logStackTrace(TAG, t);
                    }
                }
                
                @Override
                public void onImageSelectionCancelled() {
                    Logger.i(TAG, "User cancelled image selection");
                }
                
                @Override
                public void onError(Throwable throwable) {
                    Logger.e(TAG, "Error during image selection: " + throwable.getMessage());
                    Logger.logStackTrace(TAG, throwable);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to intercept CameraX capture: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
}