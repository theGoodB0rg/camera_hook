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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Image Picker as a Material Design Bottom Sheet
 * Allows users to select an image for camera injection
 */
public class ImagePickerFragment extends BottomSheetDialogFragment {
    private static final String TAG = "ImagePickerFragment";
    
    public static final String SHARED_PREFS_NAME = "CameraInterceptorPrefs";
    public static final String PREF_IMAGE_PATH = "injected_image_path";
    
    // World-readable paths for cross-app access
    private static final String EXTERNAL_IMAGE_DIR = "/sdcard/.camerainterceptor";
    private static final String EXTERNAL_IMAGE_NAME = "injected_image.jpg";
    private static final String TMP_IMAGE_PATH = "/data/local/tmp/camerainterceptor_image.jpg";

    // Views
    private ShapeableImageView imagePreview;
    private FrameLayout placeholderContainer;
    private MaterialButton selectButton;
    private MaterialButton clearButton;
    private CircularProgressIndicator progressIndicator;
    private TextView statusText;

    // Activity Result Launcher for image picker
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    public static ImagePickerFragment newInstance() {
        return new ImagePickerFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Register activity result launcher
        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        saveImageLocally(uri);
                    }
                }
            }
        );
    }

    @Override
    public int getTheme() {
        return R.style.Theme_CameraInterceptor_BottomSheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_image_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Initialize views
        imagePreview = view.findViewById(R.id.image_preview);
        placeholderContainer = view.findViewById(R.id.placeholder_container);
        selectButton = view.findViewById(R.id.button_select);
        clearButton = view.findViewById(R.id.button_clear);
        progressIndicator = view.findViewById(R.id.progress_indicator);
        statusText = view.findViewById(R.id.status_text);

        // Set up button click listeners
        selectButton.setOnClickListener(v -> openGallery());
        clearButton.setOnClickListener(v -> clearSelection());

        // Load existing preview
        loadSavedPreview();

        // Configure bottom sheet behavior
        setupBottomSheetBehavior();
    }

    private void setupBottomSheetBehavior() {
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            dialog.setOnShowListener(d -> {
                BottomSheetDialog bottomSheetDialog = (BottomSheetDialog) d;
                FrameLayout bottomSheet = bottomSheetDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
                if (bottomSheet != null) {
                    BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    behavior.setSkipCollapsed(true);
                }
            });
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        imagePickerLauncher.launch(intent);
    }

    private void clearSelection() {
        Context context = getContext();
        if (context == null) return;

        showLoading(true);

        try {
            // Clear SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(PREF_IMAGE_PATH).apply();
            
            SharedPreferences dpPrefs = getDeviceProtectedPrefs(context);
            if (dpPrefs != null) {
                dpPrefs.edit().remove(PREF_IMAGE_PATH).apply();
            }
            makePrefsReadable(context);

            // Remove external storage file
            File externalFile = new File(EXTERNAL_IMAGE_DIR, EXTERNAL_IMAGE_NAME);
            if (externalFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                externalFile.delete();
            }

            // Remove internal stored file
            File destFile = new File(context.getFilesDir(), "injected_image.jpg");
            if (destFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destFile.delete();
            }

            // Remove device-protected file
            File dpFile = getDeviceProtectedFile(context);
            if (dpFile != null && dpFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dpFile.delete();
            }

            showPlaceholder();
            updateStatus(getString(R.string.image_picker_cleared));
            Toast.makeText(context, R.string.image_picker_cleared, Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Logger.e(TAG, "Error clearing selection: " + e.getMessage());
            Toast.makeText(context, R.string.image_picker_error_clear, Toast.LENGTH_SHORT).show();
        } finally {
            showLoading(false);
        }
    }

    private void saveImageLocally(Uri uri) {
        Context context = getContext();
        if (context == null) return;

        showLoading(true);
        updateStatus(getString(R.string.image_picker_saving));

        try {
            // Create external directory
            File externalDir = new File(EXTERNAL_IMAGE_DIR);
            if (!externalDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                externalDir.mkdirs();
            }
            File destFile = new File(externalDir, EXTERNAL_IMAGE_NAME);
            File internalFile = new File(context.getFilesDir(), "injected_image.jpg");
            File dpFile = getDeviceProtectedFile(context);

            // Convert to JPEG - camera apps expect JPEG data
            Bitmap bitmap;
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    showError(getString(R.string.image_picker_error_open));
                    return;
                }
                bitmap = BitmapFactory.decodeStream(inputStream);
            }

            if (bitmap == null) {
                showError(getString(R.string.image_picker_error_decode));
                return;
            }

            // Save as JPEG with high quality
            try (FileOutputStream outputStream = new FileOutputStream(destFile)) {
                boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);
                if (!success) {
                    bitmap.recycle();
                    showError(getString(R.string.image_picker_error_save));
                    return;
                }
            }

            Logger.i(TAG, "Converted and saved image as JPEG: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            bitmap.recycle();

            // Make external file world-readable
            //noinspection ResultOfMethodCallIgnored
            destFile.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            externalDir.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            externalDir.setExecutable(true, false);

            // Copy to /data/local/tmp (world-readable on rooted devices)
            copyFileQuietly(destFile, new File(TMP_IMAGE_PATH));

            // Copy to internal storage
            copyFileQuietly(destFile, internalFile);

            // Copy to device-protected storage
            if (dpFile != null) {
                copyFileQuietly(destFile, dpFile);
                //noinspection ResultOfMethodCallIgnored
                dpFile.setReadable(true, false);
                File dpParent = dpFile.getParentFile();
                if (dpParent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    dpParent.setExecutable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    dpParent.setReadable(true, false);
                }
            }

            // Save path to SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_IMAGE_PATH, destFile.getAbsolutePath()).apply();

            SharedPreferences dpPrefs = getDeviceProtectedPrefs(context);
            if (dpPrefs != null) {
                dpPrefs.edit().putString(PREF_IMAGE_PATH, destFile.getAbsolutePath()).apply();
            }

            makePrefsReadable(context);
            showPreview(destFile);
            updateStatus(getString(R.string.image_picker_ready));
            Toast.makeText(context, R.string.image_picker_saved, Toast.LENGTH_LONG).show();
            Logger.i(TAG, "Saved injected image: " + destFile.getAbsolutePath());

        } catch (Exception e) {
            Logger.e(TAG, "Error saving image: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            showError(getString(R.string.image_picker_error_general, e.getMessage()));
        } finally {
            showLoading(false);
        }
    }

    private void copyFileQuietly(File src, File dest) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            //noinspection ResultOfMethodCallIgnored
            dest.setReadable(true, false);
        } catch (Exception e) {
            Logger.w(TAG, "Failed to copy to " + dest.getPath() + ": " + e.getMessage());
        }
    }

    private void loadSavedPreview() {
        Context context = getContext();
        if (context == null) return;

        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        String existingPath = prefs.getString(PREF_IMAGE_PATH, null);
        
        if (existingPath == null) {
            SharedPreferences dpPrefs = getDeviceProtectedPrefs(context);
            if (dpPrefs != null) {
                existingPath = dpPrefs.getString(PREF_IMAGE_PATH, null);
            }
        }

        if (existingPath == null) {
            showPlaceholder();
            updateStatus(getString(R.string.image_picker_no_image));
            return;
        }

        File file = new File(existingPath);
        if (file.exists()) {
            showPreview(file);
            updateStatus(getString(R.string.image_picker_ready));
        } else {
            showPlaceholder();
            updateStatus(getString(R.string.image_picker_no_image));
        }
    }

    private void showPlaceholder() {
        if (imagePreview != null) {
            imagePreview.setImageDrawable(null);
            imagePreview.setVisibility(View.GONE);
        }
        if (placeholderContainer != null) {
            placeholderContainer.setVisibility(View.VISIBLE);
        }
        if (clearButton != null) {
            clearButton.setEnabled(false);
        }
    }

    private void showPreview(File imageFile) {
        if (imageFile == null || !imageFile.exists()) {
            showPlaceholder();
            return;
        }

        Bitmap bitmap = decodeScaledBitmap(imageFile);
        if (bitmap != null) {
            if (imagePreview != null) {
                imagePreview.setImageBitmap(bitmap);
                imagePreview.setVisibility(View.VISIBLE);
            }
            if (placeholderContainer != null) {
                placeholderContainer.setVisibility(View.GONE);
            }
            if (clearButton != null) {
                clearButton.setEnabled(true);
            }
        } else {
            showPlaceholder();
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

    private void showLoading(boolean loading) {
        if (progressIndicator != null) {
            progressIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (selectButton != null) {
            selectButton.setEnabled(!loading);
        }
        if (clearButton != null && !loading) {
            // Re-enable clear button only if there's an image
            Context context = getContext();
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
                String path = prefs.getString(PREF_IMAGE_PATH, null);
                clearButton.setEnabled(path != null);
            }
        } else if (clearButton != null) {
            clearButton.setEnabled(false);
        }
    }

    private void updateStatus(String status) {
        if (statusText != null) {
            statusText.setText(status);
        }
    }

    private void showError(String message) {
        showLoading(false);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        updateStatus(getString(R.string.image_picker_error));
    }

    @SuppressWarnings({"ResultOfMethodCallIgnored", "deprecation"})
    private void makePrefsReadable(Context context) {
        try {
            File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }

            File prefsFile = new File(prefsDir, SHARED_PREFS_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }

            Context dp = getDeviceProtectedContext(context);
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

    private Context getDeviceProtectedContext(Context context) {
        try {
            return context.createDeviceProtectedStorageContext();
        } catch (Exception e) {
            Logger.w(TAG, "Device protected context unavailable: " + e.getMessage());
            return null;
        }
    }

    private SharedPreferences getDeviceProtectedPrefs(Context context) {
        Context dp = getDeviceProtectedContext(context);
        if (dp == null) return null;
        return dp.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File getDeviceProtectedFile(Context context) {
        Context dp = getDeviceProtectedContext(context);
        if (dp == null) return null;
        return new File(dp.getFilesDir(), "injected_image.jpg");
    }
}
