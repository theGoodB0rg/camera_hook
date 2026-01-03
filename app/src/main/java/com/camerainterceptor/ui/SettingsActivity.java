// SettingsActivity.java 
package com.camerainterceptor.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings activity for the Camera Interceptor module
 * Updated to use AndroidX Preferences and Material Design toolbar
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        try {
            // Set up toolbar
            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);

            // Initialize default preferences if needed
            initDefaultPreferences();

            // Fragment is loaded via FragmentContainerView in layout XML
            // Only add fragment if this is a fresh start (not a config change)
            if (savedInstanceState == null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.settings_container, new SettingsFragment())
                        .commit();
            }

            // Request necessary permissions
            requestRequiredPermissions();

            Logger.i(TAG, "SettingsActivity created");
        } catch (Exception e) {
            Logger.e(TAG, "Error in onCreate: " + e.getMessage());
            Logger.logStackTrace(TAG, e);
            Toast.makeText(this, "Error initializing settings", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Initialize default preference values
     */
    private void initDefaultPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        // Set defaults if they don't exist
        if (!prefs.contains("enable_module")) {
            editor.putBoolean("enable_module", true);
        }

        if (!prefs.contains("show_notifications")) {
            editor.putBoolean("show_notifications", true);
        }

        if (!prefs.contains("use_custom_picker")) {
            editor.putBoolean("use_custom_picker", false);
        }

        editor.apply();
    }

    /**
     * Request permissions required for the module
     */
    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();

            // Check for required permissions
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }

            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.CAMERA);
            }

            // Request permissions if needed
            if (!permissionsNeeded.isEmpty()) {
                requestPermissions(permissionsNeeded.toArray(new String[0]), REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Some permissions were denied. " +
                        "The module may not work correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Settings fragment to display preferences using AndroidX PreferenceFragmentCompat
     */
    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // Load preferences from XML
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // Handle Image Selection Click
            Preference selectImage = findPreference("select_image");
            if (selectImage != null) {
                selectImage.setOnPreferenceClickListener(preference -> {
                    android.content.Intent intent = new android.content.Intent(getActivity(),
                            com.camerainterceptor.ui.ImagePickerActivity.class);
                    startActivity(intent);
                    return true;
                });
            }

            // Handle App Selection Click
            Preference selectApps = findPreference("select_apps");
            if (selectApps != null) {
                selectApps.setOnPreferenceClickListener(preference -> {
                    android.content.Intent intent = new android.content.Intent(getActivity(),
                            com.camerainterceptor.ui.AppSelectionActivity.class);
                    startActivity(intent);
                    return true;
                });
            }

            // Handle View Logs Click
            Preference viewLogs = findPreference("view_logs");
            if (viewLogs != null) {
                viewLogs.setOnPreferenceClickListener(preference -> {
                    android.content.Intent intent = new android.content.Intent(getActivity(),
                            com.camerainterceptor.ui.LogViewerActivity.class);
                    startActivity(intent);
                    return true;
                });
            }
        }
    }

    /**
     * Get a boolean preference value
     */
    public static boolean getBooleanPreference(Context context, String key, boolean defaultValue) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(key, defaultValue);
    }

    /**
     * Get a string preference value
     */
    public static String getStringPreference(Context context, String key, String defaultValue) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(key, defaultValue);
    }
}