package com.camerainterceptor.hooks;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.camerainterceptor.HookDispatcher;
import com.camerainterceptor.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks for file output operations to intercept when camera apps save images.
 * This is a more reliable approach than trying to modify ImageReader buffers.
 */
public class FileOutputHook {
    private static final String TAG = "FileOutputHook";
    private final HookDispatcher dispatcher;
    
    // Track if we're currently in an interception to avoid recursion
    private static final ThreadLocal<Boolean> isIntercepting = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };
    
    // Track URIs that are image saves (from ContentResolver.insert)
    private static final ThreadLocal<Uri> pendingImageUri = new ThreadLocal<>();

    public FileOutputHook(HookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        initHooks();
    }

    private void initHooks() {
        try {
            Logger.i(TAG, "Initializing file output hooks");

            // Hook FileOutputStream write methods for JPEG files
            hookFileOutputStream();
            
            // Hook ContentResolver for MediaStore saves (Google Camera, etc.)
            hookContentResolver();
            
            // Hook ContentResolver.openOutputStream for MediaStore writes
            hookContentResolverOpenOutputStream();
            
            // Hook Bitmap.compress which many apps use
            hookBitmapCompress();

            Logger.i(TAG, "File output hooks initialized successfully");
        } catch (Throwable t) {
            Logger.e(TAG, "Failed to initialize file output hooks: " + t.getMessage());
            Logger.logStackTrace(TAG, t);
        }
    }

    private void hookFileOutputStream() {
        try {
            // Hook the constructor that takes a File to track what file is being written
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            File file = (File) param.args[0];
                            if (file != null && isImageFile(file.getName())) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "targetFile", file);
                                Logger.d(TAG, "FileOutputStream opened for image: " + file.getAbsolutePath());
                            }
                        }
                    });

            XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String path = (String) param.args[0];
                            if (path != null && isImageFile(path)) {
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "targetFile", new File(path));
                                Logger.d(TAG, "FileOutputStream opened for image path: " + path);
                            }
                        }
                    });

            // Hook write(byte[]) to intercept full image writes
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            File targetFile = (File) XposedHelpers.getAdditionalInstanceField(param.thisObject, "targetFile");
                            if (targetFile == null) return;
                            
                            byte[] data = (byte[]) param.args[0];
                            if (data == null || data.length < 100) return;
                            
                            // Check if this looks like JPEG data
                            if (isJpegData(data)) {
                                tryInjectImage(param, data, targetFile, "write(byte[])");
                            }
                        }
                    });
            
            // Hook write(byte[], int, int) - the concrete method that most writes funnel through
            // This is critical for Pattern 2: ByteArrayOutputStream -> FileOutputStream.write()
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", 
                    byte[].class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            File targetFile = (File) XposedHelpers.getAdditionalInstanceField(param.thisObject, "targetFile");
                            if (targetFile == null) return;
                            
                            byte[] data = (byte[]) param.args[0];
                            int off = (int) param.args[1];
                            int len = (int) param.args[2];
                            
                            if (data == null || len < 100) return;
                            
                            // Check if this looks like JPEG data (at the offset)
                            if (isJpegDataAtOffset(data, off, len)) {
                                Logger.i(TAG, "Intercepting JPEG via write(byte[],int,int) off=" + off + " len=" + len + 
                                        " to: " + targetFile.getName());
                                tryInjectImageWithOffset(param, data, off, len, targetFile);
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook FileOutputStream: " + t.getMessage());
        }
    }
    
    /**
     * Check if JPEG data exists at a specific offset in the byte array
     */
    private boolean isJpegDataAtOffset(byte[] data, int off, int len) {
        if (data == null || len < 3 || off < 0 || off + 2 >= data.length) return false;
        // JPEG magic bytes: FF D8 FF
        return (data[off] & 0xFF) == 0xFF && 
               (data[off + 1] & 0xFF) == 0xD8 && 
               (data[off + 2] & 0xFF) == 0xFF;
    }
    
    /**
     * Inject image for write(byte[], int, int) calls
     */
    private void tryInjectImageWithOffset(XC_MethodHook.MethodHookParam param, byte[] originalData, 
            int off, int len, File targetFile) {
        String targetPackage = dispatcher.getLoadPackageParam().packageName;
        if (!dispatcher.isPackageAllowed(targetPackage)) {
            return;
        }
        
        if (!dispatcher.isInjectionEnabled()) {
            Logger.d(TAG, "FileOutputStream write: Injection disabled");
            return;
        }

        String filePath = targetFile != null ? targetFile.getAbsolutePath() : null;
        Logger.logHookTriggered("FOS.write(b[],off,len)", "FileOutputStream", "write", targetPackage,
                "File: " + (filePath != null ? filePath : "unknown") + ", Offset: " + off + ", Len: " + len);

        byte[] injectedData = dispatcher.getPreSelectedImageBytes();
        if (injectedData != null && injectedData.length > 0) {
            // Replace the arguments to write our injected data instead
            param.args[0] = injectedData;
            param.args[1] = 0;
            param.args[2] = injectedData.length;
            Logger.logInjectionSuccess("FOS.write(b[],off,len)", filePath, len, injectedData.length);
        } else {
            Logger.logInjectionFailure("FOS.write(b[],off,len)", "No injected image available", null);
        }
    }

    private void hookContentResolver() {
        try {
            // Hook ContentResolver.insert for MediaStore saves - track when an image entry is created
            XposedHelpers.findAndHookMethod(ContentResolver.class, "insert",
                    Uri.class, ContentValues.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri tableUri = (Uri) param.args[0];
                            Uri resultUri = (Uri) param.getResult();
                            
                            if (tableUri != null && resultUri != null) {
                                String uriStr = tableUri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media")) {
                                    ContentValues values = (ContentValues) param.args[1];
                                    String displayName = values != null ? 
                                            values.getAsString(MediaStore.Images.Media.DISPLAY_NAME) : "unknown";
                                    String mimeType = values != null ?
                                            values.getAsString(MediaStore.Images.Media.MIME_TYPE) : "unknown";
                                    
                                    Logger.i(TAG, "ContentResolver.insert created image entry: " + displayName + 
                                            " (mime: " + mimeType + ") -> " + resultUri);
                                    
                                    // Mark this URI as pending image save
                                    if (dispatcher.isInjectionEnabled()) {
                                        pendingImageUri.set(resultUri);
                                        Logger.i(TAG, "Marked URI for interception: " + resultUri);
                                    }
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver.insert: " + t.getMessage());
        }
    }
    
    private void hookContentResolverOpenOutputStream() {
        try {
            // Hook openOutputStream(Uri) - this is what Google Camera uses
            XposedHelpers.findAndHookMethod(ContentResolver.class, "openOutputStream",
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            OutputStream originalStream = (OutputStream) param.getResult();
                            
                            if (uri != null && originalStream != null && dispatcher.isInjectionEnabled()) {
                                String uriStr = uri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media") || 
                                    uriStr.contains("dcim") || uriStr.contains("camera")) {
                                    Logger.i(TAG, "ContentResolver.openOutputStream for image URI: " + uri);
                                    
                                    // Wrap the output stream to intercept writes
                                    OutputStream wrappedStream = new InterceptingOutputStream(originalStream, dispatcher, uri.toString());
                                    param.setResult(wrappedStream);
                                    Logger.i(TAG, "Wrapped OutputStream for image interception");
                                }
                            }
                        }
                    });
            
            // Also hook the version with mode parameter
            XposedHelpers.findAndHookMethod(ContentResolver.class, "openOutputStream",
                    Uri.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            OutputStream originalStream = (OutputStream) param.getResult();
                            
                            if (uri != null && originalStream != null && dispatcher.isInjectionEnabled()) {
                                String uriStr = uri.toString().toLowerCase();
                                if (uriStr.contains("images") || uriStr.contains("media") ||
                                    uriStr.contains("dcim") || uriStr.contains("camera")) {
                                    Logger.i(TAG, "ContentResolver.openOutputStream (with mode) for image URI: " + uri);
                                    
                                    // Wrap the output stream to intercept writes
                                    OutputStream wrappedStream = new InterceptingOutputStream(originalStream, dispatcher, uri.toString());
                                    param.setResult(wrappedStream);
                                    Logger.i(TAG, "Wrapped OutputStream for image interception");
                                }
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook ContentResolver.openOutputStream: " + t.getMessage());
        }
    }
    
    private void hookBitmapCompress() {
        try {
            // Hook Bitmap.compress - this is commonly used to save camera images
            XposedHelpers.findAndHookMethod(Bitmap.class, "compress",
                    Bitmap.CompressFormat.class, int.class, OutputStream.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Skip if we're in our own image loading code
                            if (Boolean.TRUE.equals(isIntercepting.get())) return;
                            if (HookDispatcher.isCurrentlyLoadingImage()) return;
                            
                            String targetPackage = dispatcher.getLoadPackageParam().packageName;
                            if (!dispatcher.isPackageAllowed(targetPackage)) {
                                return;
                            }
                            
                            if (!dispatcher.isInjectionEnabled()) {
                                return;
                            }

                            Bitmap.CompressFormat format = (Bitmap.CompressFormat) param.args[0];
                            OutputStream outputStream = (OutputStream) param.args[2];
                            
                            // Only intercept JPEG compression
                            if (format != Bitmap.CompressFormat.JPEG) {
                                return;
                            }

                            // Check if this is writing to a file
                            File targetFile = null;
                            String filePath = null;
                            if (outputStream instanceof FileOutputStream) {
                                targetFile = (File) XposedHelpers.getAdditionalInstanceField(outputStream, "targetFile");
                                filePath = targetFile != null ? targetFile.getAbsolutePath() : null;
                            }
                            
                            Logger.logHookTriggered("Bitmap.compress", "Bitmap", "compress",
                                    targetPackage, "Format: JPEG, File: " + (filePath != null ? filePath : "stream"));

                            byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                            if (injectedData != null && injectedData.length > 0) {
                                try {
                                    isIntercepting.set(true);
                                    outputStream.write(injectedData);
                                    param.setResult(true);
                                    Logger.logInjectionSuccess("Bitmap.compress", filePath, -1, injectedData.length);
                                } catch (Throwable t) {
                                    Logger.logInjectionFailure("Bitmap.compress", "Write failed", t);
                                } finally {
                                    isIntercepting.set(false);
                                }
                            } else {
                                Logger.logInjectionFailure("Bitmap.compress", "No injected data available", null);
                            }
                        }
                    });

        } catch (Throwable t) {
            Logger.e(TAG, "Failed to hook Bitmap.compress: " + t.getMessage());
        }
    }

    private void tryInjectImage(XC_MethodHook.MethodHookParam param, byte[] originalData, File targetFile, String hookSource) {
        String targetPackage = dispatcher.getLoadPackageParam().packageName;
        if (!dispatcher.isPackageAllowed(targetPackage)) {
            return;
        }
        
        if (!dispatcher.isInjectionEnabled()) {
            Logger.d(TAG, "FileOutputStream write: Injection disabled");
            return;
        }

        String filePath = targetFile != null ? targetFile.getAbsolutePath() : null;
        Logger.logHookTriggered(hookSource, "FileOutputStream", "write", targetPackage, 
                "File: " + (filePath != null ? filePath : "unknown") + ", Size: " + originalData.length + " bytes");

        byte[] injectedData = dispatcher.getPreSelectedImageBytes();
        if (injectedData != null && injectedData.length > 0) {
            param.args[0] = injectedData;
            Logger.logInjectionSuccess(hookSource, filePath, originalData.length, injectedData.length);
        } else {
            Logger.logInjectionFailure(hookSource, "No injected image available", null);
        }
    }

    private boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.contains("img_") || 
               lower.contains("photo") || lower.contains("camera") ||
               lower.contains("dcim");
    }

    /**
     * Check if byte array contains valid JPEG data
     * Validates JPEG magic bytes (FF D8 FF) at start
     */
    private boolean isJpegData(byte[] data) {
        if (data == null || data.length < 3) return false;
        // JPEG magic bytes: FF D8 FF
        return (data[0] & 0xFF) == 0xFF && 
               (data[1] & 0xFF) == 0xD8 && 
               (data[2] & 0xFF) == 0xFF;
    }
    
    /**
     * Enhanced JPEG validation - checks for valid JPEG structure
     * Beyond magic bytes, also validates JPEG has proper EOI marker
     */
    private boolean isValidJpegStructure(byte[] data) {
        if (!isJpegData(data)) return false;
        if (data.length < 10) return false;
        
        // Check for End Of Image marker (FF D9) near the end
        // Some JPEGs may have trailing data, so check last 10 bytes
        for (int i = data.length - 2; i >= data.length - 10 && i >= 0; i--) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                return true;
            }
        }
        
        // If no EOI found in last 10 bytes, still accept if magic bytes are valid
        // (some apps may not include proper EOI)
        return true;
    }
    
    /**
     * Custom OutputStream wrapper that intercepts image data written to MediaStore
     */
    private static class InterceptingOutputStream extends FilterOutputStream {
        private final HookDispatcher dispatcher;
        private final String uriString;
        private final ByteArrayOutputStream buffer;
        private boolean injected = false;
        private boolean firstWrite = true;
        
        public InterceptingOutputStream(OutputStream out, HookDispatcher dispatcher, String uriString) {
            super(out);
            this.dispatcher = dispatcher;
            this.uriString = uriString;
            this.buffer = new ByteArrayOutputStream();
            Logger.d(TAG, "InterceptingOutputStream created for: " + uriString);
        }
        
        @Override
        public void write(int b) throws IOException {
            if (!injected) {
                buffer.write(b);
                // Check if we have enough data to detect JPEG
                if (buffer.size() >= 3 && firstWrite) {
                    firstWrite = false;
                    byte[] data = buffer.toByteArray();
                    if (isJpegHeader(data)) {
                        Logger.d(TAG, "Detected JPEG stream write (byte-by-byte) to: " + uriString);
                    }
                }
            }
            super.write(b);
        }
        
        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (HookDispatcher.isCurrentlyLoadingImage()) {
                super.write(b, off, len);
                return;
            }
            
            if (!injected && b != null && len > 100) {
                // Check if this is JPEG data
                if (off == 0 && len >= 3 && isJpegHeader(b)) {
                    String targetPackage = dispatcher.getLoadPackageParam().packageName;
                    Logger.logHookTriggered("InterceptingOutputStream", "ContentResolver", "openOutputStream",
                            targetPackage, "URI: " + uriString + ", Size: " + len + " bytes");
                    
                    if (dispatcher.isInjectionEnabled()) {
                        byte[] injectedData = dispatcher.getPreSelectedImageBytes();
                        if (injectedData != null && injectedData.length > 0) {
                            // Write our injected data instead
                            super.write(injectedData, 0, injectedData.length);
                            injected = true;
                            Logger.logInjectionSuccess("InterceptingOutputStream", uriString, len, injectedData.length);
                            return; // Don't write original data
                        } else {
                            Logger.logInjectionFailure("InterceptingOutputStream", "No injected data available", null);
                        }
                    }
                }
            }
            
            // Pass through original data if not injected
            if (!injected) {
                super.write(b, off, len);
            }
        }
        
        @Override
        public void close() throws IOException {
            if (injected) {
                Logger.d(TAG, "InterceptingOutputStream closed (injection successful)");
            }
            super.close();
        }
        
        private static boolean isJpegHeader(byte[] data) {
            if (data == null || data.length < 3) return false;
            return (data[0] & 0xFF) == 0xFF && 
                   (data[1] & 0xFF) == 0xD8 && 
                   (data[2] & 0xFF) == 0xFF;
        }
    }
}
