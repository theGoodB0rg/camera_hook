// MediaPickerHelper.java 
package com.camerainterceptor.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class for handling media selection from the gallery
 */
public class MediaPickerHelper {
    private static final String TAG = "MediaPickerHelper";
    private static final int REQUEST_CODE_PICK_IMAGE = 1001;
    
    // Store callbacks for image picker results
    private static final ConcurrentHashMap<Integer, ImageSelectedCallback> callbacks = new ConcurrentHashMap<>();
    
    /**
     * Callback interface for image selection
     */
    public interface ImageSelectedCallback {
        void onImageSelected(Uri uri);
    }
    
    /**
     * Launch the gallery picker to select an image
     * 
     * @param activity The activity to launch the picker from
     * @param callback Callback to receive the selected image URI
     */
    public static void launchGalleryPicker(Activity activity, ImageSelectedCallback callback) {
        try {
            Logger.d(TAG, "Launching gallery picker");
            
            // Create picker intent
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            
            // Allow multiple apps to handle this intent
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            
            // Store the callback
            callbacks.put(REQUEST_CODE_PICK_IMAGE, callback);
            
            // Start the picker activity
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
            
            Logger.d(TAG, "Gallery picker launched successfully");
        } catch (Exception e) {
            Logger.e(TAG, "Error launching gallery picker: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            
            // Call the callback with null to indicate failure
            if (callback != null) {
                callback.onImageSelected(null);
            }
        }
    }
    
    /**
     * Handle activity result from the gallery picker
     * 
     * @param requestCode The request code from onActivityResult
     * @param resultCode The result code from onActivityResult
     * @param data The intent data from onActivityResult
     * @return true if this was a gallery picker result, false otherwise
     */
    public static boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_IMAGE) {
            ImageSelectedCallback callback = callbacks.remove(REQUEST_CODE_PICK_IMAGE);
            
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    Uri selectedImageUri = null;
                    
                    // Check if we got a direct URI
                    selectedImageUri = data.getData();
                    
                    // If not, check if we got a clip data with URIs
                    if (selectedImageUri == null && data.getClipData() != null) {
                        ClipData clipData = data.getClipData();
                        if (clipData.getItemCount() > 0) {
                            selectedImageUri = clipData.getItemAt(0).getUri();
                        }
                    }
                    
                    Logger.d(TAG, "Selected image URI: " + selectedImageUri);
                    
                    // Call the callback with the URI
                    if (callback != null) {
                        callback.onImageSelected(selectedImageUri);
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "Error processing gallery result: " + e.getMessage());
                    Logger.logStackTrace(TAG, e);
                    
                    if (callback != null) {
                        callback.onImageSelected(null);
                    }
                }
            } else {
                Logger.d(TAG, "Gallery selection cancelled or failed, resultCode: " + resultCode);
                
                if (callback != null) {
                    callback.onImageSelected(null);
                }
            }
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if the content URI has permission for long-term access
     * 
     * @param activity The activity context
     * @param uri The content URI to check
     * @return true if the URI is persistable
     */
    public static boolean isUriPersistable(Activity activity, Uri uri) {
        if (uri == null) {
            return false;
        }
        
        // Check if the URI is a content URI
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return false;
        }
        
        // For API level 19+, check if we can take persist permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                activity.getContentResolver().takePersistableUriPermission(uri, takeFlags);
                return true;
            } catch (SecurityException e) {
                Logger.w(TAG, "Cannot take persistable permission for URI: " + uri);
                return false;
            }
        }
        
        return false;
    }
}