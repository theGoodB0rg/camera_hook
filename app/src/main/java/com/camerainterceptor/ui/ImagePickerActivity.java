package com.camerainterceptor.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ImagePickerActivity extends Activity {
    private static final String TAG = "ImagePickerActivity";
    private static final int PICK_IMAGE_REQUEST = 1;
    public static final String SHARED_PREFS_NAME = "CameraInterceptorPrefs";
    public static final String PREF_IMAGE_PATH = "injected_image_path";
    public static final String PREF_ENABLED = "interceptor_enabled";
    
    // World-readable path in external storage
    private static final String EXTERNAL_IMAGE_DIR = "/sdcard/.camerainterceptor";
    private static final String EXTERNAL_IMAGE_NAME = "injected_image.jpg";

    private ImageView imagePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        TextView infoTextView = findViewById(R.id.text_info);
        infoTextView.setText("Select an image to be injected into camera apps.");

        imagePreview = findViewById(R.id.image_preview);
        loadSavedPreview();

        Button galleryButton = findViewById(R.id.button_gallery);
        galleryButton.setText("Select Image");
        galleryButton.setOnClickListener(v -> openGallery());

        Button cancelButton = findViewById(R.id.button_cancel);
        cancelButton.setText("Clear Selection");
        cancelButton.setOnClickListener(v -> clearSelection());
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void clearSelection() {
        // Use standard mode first, we will try to make file readable explicitly
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(PREF_IMAGE_PATH).apply();
        SharedPreferences dpPrefs = getDeviceProtectedPrefs();
        if (dpPrefs != null) {
            dpPrefs.edit().remove(PREF_IMAGE_PATH).apply();
        }
        makePrefsReadable();

        // Remove external storage file
        File externalFile = new File(EXTERNAL_IMAGE_DIR, EXTERNAL_IMAGE_NAME);
        if (externalFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            externalFile.delete();
        }

        // Remove the internal stored file if it exists
        File destFile = new File(getFilesDir(), "injected_image.jpg");
        if (destFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destFile.delete();
        }

        File dpFile = getDeviceProtectedFile();
        if (dpFile != null && dpFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dpFile.delete();
        }

        hidePreview();
        Toast.makeText(this, "Injection disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            saveImageLocally(uri);
        }
    }

    private void saveImageLocally(Uri uri) {
        try {
            // Primary: Save to external storage that all apps can access
            File externalDir = new File(EXTERNAL_IMAGE_DIR);
            if (!externalDir.exists()) {
                externalDir.mkdirs();
            }
            File destFile = new File(externalDir, EXTERNAL_IMAGE_NAME);
            
            // Also keep a copy in internal storage as backup
            File internalFile = new File(getFilesDir(), "injected_image.jpg");
            File dpFile = getDeviceProtectedFile();

            try (InputStream inputStream = getContentResolver().openInputStream(uri);
                    OutputStream outputStream = new FileOutputStream(destFile)) {

                if (inputStream == null) {
                    Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show();
                    return;
                }

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            
            // Make external file world-readable
            destFile.setReadable(true, false);
            externalDir.setReadable(true, false);
            externalDir.setExecutable(true, false);
            
            Logger.i(TAG, "Saved image to external storage: " + destFile.getAbsolutePath());

            // Copy to internal storage as well
            try (InputStream in2 = new FileInputStream(destFile);
                    OutputStream out2 = new FileOutputStream(internalFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in2.read(buffer)) != -1) {
                    out2.write(buffer, 0, bytesRead);
                }
            } catch (Exception copyErr) {
                Logger.w(TAG, "Failed to copy to internal storage: " + copyErr.getMessage());
            }

            // Also copy to device-protected storage
            if (dpFile != null) {
                try (InputStream in2 = new FileInputStream(destFile);
                        OutputStream out2 = new FileOutputStream(dpFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in2.read(buffer)) != -1) {
                        out2.write(buffer, 0, bytesRead);
                    }
                } catch (Exception copyErr) {
                    Logger.w(TAG, "Failed to copy image to DP storage: " + copyErr.getMessage());
                }
            }

            // Critical: make file readable by other apps (the Xposed module running in
            // target app)
            // This is deprecated but often necessary for simple Xposed modules without a
            // content provider
            destFile.setReadable(true, false);

            // Make the directory readable/executable so other apps can access the file
            File filesDir = destFile.getParentFile();
            if (filesDir != null) {
                filesDir.setExecutable(true, false);
                filesDir.setReadable(true, false);
            }

            if (dpFile != null) {
                dpFile.setReadable(true, false);
                File dpParent = dpFile.getParentFile();
                if (dpParent != null) {
                    dpParent.setExecutable(true, false);
                    dpParent.setReadable(true, false);
                }
            }

            // Save path to SharedPreferences - use external path as primary
            SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_IMAGE_PATH, destFile.getAbsolutePath()).apply();

            SharedPreferences dpPrefs = getDeviceProtectedPrefs();
            if (dpPrefs != null) {
                // Also save external path to device-protected prefs
                dpPrefs.edit().putString(PREF_IMAGE_PATH, destFile.getAbsolutePath()).apply();
            }

            makePrefsReadable();

            showPreview(destFile);

            Toast.makeText(this, "Image saved & ready for injection!", Toast.LENGTH_LONG).show();
            Logger.i(TAG, "Saved injected image to external storage: " + destFile.getAbsolutePath());

        } catch (Exception e) {
            Logger.e(TAG, "Error saving image: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            Toast.makeText(this, "Error saving image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSavedPreview() {
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        String existingPath = prefs.getString(PREF_IMAGE_PATH, null);
        if (existingPath == null) {
            SharedPreferences dpPrefs = getDeviceProtectedPrefs();
            if (dpPrefs != null) {
                existingPath = dpPrefs.getString(PREF_IMAGE_PATH, null);
            }
        }
        if (existingPath == null) {
            hidePreview();
            return;
        }

        File file = new File(existingPath);
        showPreview(file);
    }

    private void hidePreview() {
        if (imagePreview != null) {
            imagePreview.setImageDrawable(null);
            imagePreview.setVisibility(View.GONE);
        }
    }

    private void showPreview(File imageFile) {
        if (imagePreview == null) {
            return;
        }

        if (imageFile == null || !imageFile.exists()) {
            hidePreview();
            return;
        }

        Bitmap bitmap = decodeScaledBitmap(imageFile);
        if (bitmap != null) {
            imagePreview.setImageBitmap(bitmap);
            imagePreview.setVisibility(View.VISIBLE);
        } else {
            hidePreview();
        }
    }

    private Bitmap decodeScaledBitmap(File imageFile) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);

            int maxSize = 800;
            int sampleSize = 1;
            while ((bounds.outHeight / sampleSize) > maxSize || (bounds.outWidth / sampleSize) > maxSize) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
        } catch (Exception e) {
            Logger.w(TAG, "Failed to decode preview: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({ "ResultOfMethodCallIgnored", "deprecation" })
    private void makePrefsReadable() {
        try {
            File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }

            File prefsFile = new File(prefsDir, SHARED_PREFS_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }

            // Device-protected storage (used by XSharedPreferences on newer Android)
            Context dp = getDeviceProtectedContext();
            if (dp != null) {
                File dpPrefsDir = new File(dp.getDataDir(), "shared_prefs");
                if (dpPrefsDir.exists()) {
                    dpPrefsDir.setExecutable(true, false);
                    dpPrefsDir.setReadable(true, false);
                }

                File dpPrefsFile = new File(dpPrefsDir, SHARED_PREFS_NAME + ".xml");
                if (dpPrefsFile.exists()) {
                    dpPrefsFile.setReadable(true, false);
                }
            }
        } catch (Exception e) {
            Logger.w(TAG, "Failed to make prefs readable: " + e.getMessage());
        }
    }

    private Context getDeviceProtectedContext() {
        try {
            return getApplicationContext().createDeviceProtectedStorageContext();
        } catch (Exception e) {
            Logger.w(TAG, "Device protected context unavailable: " + e.getMessage());
            return null;
        }
    }

    private SharedPreferences getDeviceProtectedPrefs() {
        Context dp = getDeviceProtectedContext();
        if (dp == null) return null;
        return dp.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File getDeviceProtectedFile() {
        Context dp = getDeviceProtectedContext();
        if (dp == null) return null;
        return new File(dp.getFilesDir(), "injected_image.jpg");
    }
}