// HookCallback.java 
package com.camerainterceptor.interfaces;

import com.camerainterceptor.utils.ImageUtils.ImageMetadata;

/**
 * Callback interface for hook operations related to camera interception
 */
public interface HookCallback {
    
    /**
     * Called when a user selects an image from the gallery
     * 
     * @param imageData The processed image data (usually JPEG bytes)
     * @param metadata The fake metadata created for the image
     */
    void onImageSelected(byte[] imageData, ImageMetadata metadata);
    
    /**
     * Called when a user cancels the image selection process
     */
    void onImageSelectionCancelled();
    
    /**
     * Called when an error occurs during the hook process
     * 
     * @param throwable The error that occurred
     */
    void onError(Throwable throwable);
}