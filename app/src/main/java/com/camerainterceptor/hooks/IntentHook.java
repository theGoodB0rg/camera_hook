package com.camerainterceptor.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.interfaces.HookCallback;
import com.camerainterceptor.utils.ImageUtils;
import com.camerainterceptor.utils.Logger;
import com.camerainterceptor.utils.MediaPickerHelper;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for Activity intents that launch the camera
 * This handles the common intent-based camera usage
 */
public class IntentHook {
    private static final String TAG = "IntentHook";
    private final HookDispatcher dispatcher;
    
    // Store original camera intents for when activities receive results
    private final Map<Activity, Intent> originalIntents = new HashMap<>();
    private final Map<Activity, Uri> captureOutputUris = new HashMap<>();
    private final Map<Activity, Integer> captureRequestCodes = new HashMap<>();
    
    public IntentHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }
    
    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing camera intent hooks");
            
            // Hook startActivityForResult to intercept camera intents
            hookStartActivityForResult();
            
            // Hook startActivity to catch direct camera launches
            hookStartActivity();
            
            // Hook onActivityResult to handle returning results
            hookOnActivityResult();
            
            Logger.i(TAG, "Camera intent hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize camera intent hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Activity.startActivityForResult to intercept camera intents
     */
    private void hookStartActivityForResult() {
        try {
            // Hook various overloads of startActivityForResult
            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult", 
                    Intent.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = (Intent) param.args[0];
                    int requestCode = (int) param.args[1];
                    
                    handleCameraIntent(param, activity, intent, requestCode, null);
                }
            });
            
            // Activity.startActivityForResult(Intent, int, Bundle)
            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult", 
                    Intent.class, int.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = (Intent) param.args[0];
                    int requestCode = (int) param.args[1];
                    Bundle options = (Bundle) param.args[2];
                    
                    handleCameraIntent(param, activity, intent, requestCode, options);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook startActivityForResult: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Activity.startActivity to intercept direct camera launches
     */
    private void hookStartActivity() {
        try {
            // Hook Activity.startActivity(Intent)
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity", 
                    Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = (Intent) param.args[0];
                    
                    // Check if this is a camera intent without a result
                    // This is less common but still possible
                    if (isCameraIntent(intent)) {
                        Logger.i(TAG, "Detected direct camera launch without result: " + intent.getAction());
                        
                        // We can't easily intercept this without a requestCode, so just log it
                        Logger.w(TAG, "Cannot intercept direct camera launch without requestCode. " +
                                "App may launch camera directly.");
                    }
                }
            });
            
            // Hook Activity.startActivity(Intent, Bundle)
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity", 
                    Intent.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = (Intent) param.args[0];
                    
                    // Check if this is a camera intent without a result
                    if (isCameraIntent(intent)) {
                        Logger.i(TAG, "Detected direct camera launch without result (with bundle): " + intent.getAction());
                        
                        // Same limitation as above
                        Logger.w(TAG, "Cannot intercept direct camera launch without requestCode. " +
                                "App may launch camera directly.");
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook startActivity: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Hook Activity.onActivityResult to handle returning results from our gallery
     */
    private void hookOnActivityResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult", 
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    
                    // Check if this is a result for a camera intent we intercepted
                    Integer captureRequestCode = captureRequestCodes.get(activity);
                    
                    if (captureRequestCode != null && captureRequestCode == requestCode) {
                        Logger.i(TAG, "Intercepted activity result for camera request, " +
                                "requestCode: " + requestCode + ", resultCode: " + resultCode);
                        
                        // Handle this camera result
                        handleCameraResult(param, activity, requestCode, resultCode, data);
                    } else if (MediaPickerHelper.handleActivityResult(requestCode, resultCode, data)) {
                        // This was handled by our MediaPickerHelper, prevent original method
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook onActivityResult: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Handle a potential camera intent
     */
    private void handleCameraIntent(XC_MethodHook.MethodHookParam param, 
                                   Activity activity, Intent intent, int requestCode, Bundle options) {
        try {
            // Check if this is a camera intent
            if (isCameraIntent(intent)) {
                Logger.i(TAG, "Intercepted camera intent: " + intent.getAction() + ", requestCode: " + requestCode);
                
                // Store the original intent and request code
                originalIntents.put(activity, intent);
                captureRequestCodes.put(activity, requestCode);
                
                // Get the output URI if provided
                Uri outputUri = null;
                if (intent.getExtras() != null) {
                    outputUri = intent.getExtras().getParcelable(MediaStore.EXTRA_OUTPUT);
                }
                
                if (outputUri != null) {
                    Logger.d(TAG, "Camera capture will output to: " + outputUri);
                    captureOutputUris.put(activity, outputUri);
                }
                
                // Prevent the original intent from launching
                param.setResult(null);
                
                // Launch our image picker instead
                launchImagePicker(activity, intent, requestCode);
            }
        } catch (Throwable t) {
            Logger.e(TAG, "Error handling camera intent: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Check if an intent is for camera capture
     */
    private boolean isCameraIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        
        String action = intent.getAction();
        if (action == null) {
            return false;
        }
        
        // Check common camera intent actions
        return action.equals(MediaStore.ACTION_IMAGE_CAPTURE) ||
               action.equals(MediaStore.ACTION_IMAGE_CAPTURE_SECURE) ||
               (action.equals(Intent.ACTION_MAIN) && 
                   intent.hasCategory("android.intent.category.APP_GALLERY"));
    }
    
    /**
     * Launch our custom image picker instead of the camera
     */
    private void launchImagePicker(final Activity activity, final Intent originalIntent,
                                  final int requestCode) {
        try {
            // Launch gallery picker through our dispatcher
            dispatcher.interceptCameraRequest(activity, new HookCallback() {
                @Override
                public void onImageSelected(byte[] imageData, ImageUtils.ImageMetadata metadata) {
                    Logger.i(TAG, "User selected image from gallery, preparing result");
                    
                    try {
                        // Handle the selected image based on the original intent
                        handleSelectedImage(activity, originalIntent, requestCode, imageData, metadata);
                    } catch (Throwable t) {
                        Logger.e(TAG, "Error handling selected image: " + t.getMessage());
                        Logger.logStackTrace(TAG, t);
                        // Send a cancellation result
                        sendCancellationResult(activity, requestCode);
                    }
                }
                
                @Override
                public void onImageSelectionCancelled() {
                    Logger.i(TAG, "User cancelled image selection");
                    sendCancellationResult(activity, requestCode);
                }
                
                @Override
                public void onError(Throwable throwable) {
                    Logger.e(TAG, "Error during image selection: " + throwable.getMessage());
                    Logger.logStackTrace(TAG, throwable);
                    sendCancellationResult(activity, requestCode);
                }
            });
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to launch image picker: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            sendCancellationResult(activity, requestCode);
        }
    }
    
    /**
     * Handle a selected image for a camera intent
     */
    private void handleSelectedImage(Activity activity, Intent originalIntent, int requestCode,
                                    byte[] imageData, ImageUtils.ImageMetadata metadata) {
        try {
            // Get the output URI if one was specified
            Uri outputUri = captureOutputUris.get(activity);
            
            if (outputUri != null) {
                // Save the image data to the output URI
                Logger.d(TAG, "Writing image data to output URI: " + outputUri);
                saveImageToUri(activity, outputUri, imageData);
                
                // The result intent is typically null when EXTRA_OUTPUT is provided
                Intent resultIntent = new Intent();
                
                // Send the result back to the activity
                activity.setResult(Activity.RESULT_OK, resultIntent);
            } else {
                // No output URI, so we need to include a thumbnail in the result intent
                Logger.d(TAG, "Creating result intent with image thumbnail");
                
                Intent resultIntent = new Intent();
                
                // In a real implementation, you would create a small thumbnail
                // and include it as EXTRA_THUMBNAIL or in the data URI
                // For simplicity, we're skipping that step here
                
                // Set the result with our intent
                activity.setResult(Activity.RESULT_OK, resultIntent);
            }
            
            // Clean up
            originalIntents.remove(activity);
            captureOutputUris.remove(activity);
            captureRequestCodes.remove(activity);
            
            Logger.i(TAG, "Successfully handled selected image for camera intent");
        } catch (Throwable t) {
            Logger.e(TAG, "Error handling selected image: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
            
            // Send a cancellation result
            sendCancellationResult(activity, requestCode);
        }
    }
    
    /**
     * Save image data to a URI
     */
    private void saveImageToUri(Context context, Uri uri, byte[] imageData) throws Exception {
        OutputStream outputStream = null;
        try {
            outputStream = context.getContentResolver().openOutputStream(uri);
            if (outputStream != null) {
                outputStream.write(imageData);
                outputStream.flush();
                Logger.d(TAG, "Successfully wrote " + imageData.length + " bytes to URI: " + uri);
            } else {
                throw new Exception("Failed to open output stream for URI: " + uri);
            }
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception e) {
                    Logger.e(TAG, "Error closing output stream: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Send a cancellation result to the activity
     */
    private void sendCancellationResult(Activity activity, int requestCode) {
        try {
            // Send RESULT_CANCELED
            activity.setResult(Activity.RESULT_CANCELED, null);
            
            // Clean up
            originalIntents.remove(activity);
            captureOutputUris.remove(activity);
            captureRequestCodes.remove(activity);
            
            Logger.i(TAG, "Sent cancellation result to activity");
        } catch (Throwable t) {
            Logger.e(TAG, "Error sending cancellation result: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
    
    /**
     * Handle a camera result that was intercepted
     */
    private void handleCameraResult(XC_MethodHook.MethodHookParam param, 
                                   Activity activity, int requestCode, int resultCode, Intent data) {
        try {
            // We've already sent our own result, so prevent the original method from executing
            param.setResult(null);
            
            Logger.d(TAG, "Prevented original onActivityResult for intercepted camera request");
        } catch (Throwable t) {
            Logger.e(TAG, "Error handling camera result: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }
}