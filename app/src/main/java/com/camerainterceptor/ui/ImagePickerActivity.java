package com.camerainterceptor.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ImagePickerActivity extends Activity {
    private static final String TAG = "ImagePickerActivity";
    private static final int PICK_IMAGE_REQUEST = 1;
    public static final String SHARED_PREFS_NAME = "CameraInterceptorPrefs";
    public static final String PREF_IMAGE_PATH = "injected_image_path";
    public static final String PREF_ENABLED = "interceptor_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        TextView infoTextView = findViewById(R.id.text_info);
        infoTextView.setText("Select an image to be injected into camera apps.");

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
        makePrefsReadable();
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
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show();
                return;
            }

            File destFile = new File(getFilesDir(), "injected_image.jpg");
            OutputStream outputStream = new FileOutputStream(destFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            outputStream.close();

            // Critical: make file readable by other apps (the Xposed module running in
            // target app)
            // This is deprecated but often necessary for simple Xposed modules without a
            // content provider
            destFile.setReadable(true, false);

            // Save path to SharedPreferences
            SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_IMAGE_PATH, destFile.getAbsolutePath()).apply();

            makePrefsReadable();

            Toast.makeText(this, "Image saved & ready for injection!", Toast.LENGTH_LONG).show();
            Logger.i(TAG, "Saved injected image to: " + destFile.getAbsolutePath());

        } catch (Exception e) {
            Logger.e(TAG, "Error saving image: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            Toast.makeText(this, "Error saving image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings({ "ResultOfMethodCallIgnored", "deprecation" })
    private void makePrefsReadable() {
        try {
            File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, SHARED_PREFS_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            Logger.w(TAG, "Failed to make prefs readable: " + e.getMessage());
        }
    }
}