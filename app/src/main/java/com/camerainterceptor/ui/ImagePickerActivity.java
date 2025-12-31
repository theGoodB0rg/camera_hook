// ImagePickerActivity.java 
package com.camerainterceptor.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;
import com.camerainterceptor.utils.MediaPickerHelper;
import com.camerainterceptor.utils.MediaPickerHelper.ImageSelectedCallback;

/**
 * Custom image picker activity that allows the user to select an image from the
 * gallery
 * This is an optional UI that can be used instead of the direct gallery picker
 */
public class ImagePickerActivity extends Activity {
    private static final String TAG = "ImagePickerActivity";
    private static final int REQUEST_CODE_PICK_IMAGE = 2001;

    // Intent extra keys
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_RESULT_URI = "result_uri";

    private String interceptedAppName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        try {
            // Get the name of the app being intercepted
            interceptedAppName = getIntent().getStringExtra(EXTRA_APP_NAME);
            if (interceptedAppName == null || interceptedAppName.isEmpty()) {
                interceptedAppName = "An app";
            }

            // Set up the UI text
            TextView infoTextView = findViewById(R.id.text_info);
            infoTextView.setText(interceptedAppName + " is trying to access your camera. " +
                    "Instead, you can select an image from your gallery.");

            // Set up the buttons
            Button galleryButton = findViewById(R.id.button_gallery);
            galleryButton.setOnClickListener(v -> openGallery());

            Button cancelButton = findViewById(R.id.button_cancel);
            cancelButton.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });

            Logger.d(TAG, "ImagePickerActivity created for app: " + interceptedAppName);
        } catch (Exception e) {
            Logger.e(TAG, "Error in onCreate: " + e.getMessage());
            Logger.logStackTrace(TAG, e);

            // Show an error message and close the activity
            Toast.makeText(this, "Error initializing image picker", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * Open the gallery picker
     */
    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        } catch (Exception e) {
            Logger.e(TAG, "Error opening gallery: " + e.getMessage());
            Toast.makeText(this, "Error opening gallery", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    Uri selectedImageUri = data.getData();
                    if (selectedImageUri != null) {
                        Logger.d(TAG, "Image selected: " + selectedImageUri);

                        // Return the selected image URI
                        Intent resultIntent = new Intent();
                        resultIntent.putExtra(EXTRA_RESULT_URI, selectedImageUri.toString());
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    } else {
                        Logger.w(TAG, "No image URI in result");
                        Toast.makeText(this, "Failed to get image", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "Error processing gallery result: " + e.getMessage());
                    Toast.makeText(this, "Error processing selected image", Toast.LENGTH_SHORT).show();
                }
            } else {
                Logger.d(TAG, "Gallery selection cancelled or failed");
            }
        }
    }
}