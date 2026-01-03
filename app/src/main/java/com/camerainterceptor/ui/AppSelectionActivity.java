package com.camerainterceptor.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.camerainterceptor.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends Activity {

    private ListView listView;
    private AppAdapter adapter;
    private List<AppInfo> appList = new ArrayList<>();
    private Set<String> allowedApps = new HashSet<>();
    private Set<String> profilingApps = new HashSet<>();

    public static final String SHARED_PREFS_NAME = "CameraInterceptorPrefs";
    public static final String PREF_ALLOWED_APPS = "allowed_apps";
    public static final String PREF_PROFILING_APPS = "profiling_apps";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection);

        listView = findViewById(R.id.list_apps);
        Button saveButton = findViewById(R.id.button_save);

        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);

        loadPreferences();

        saveButton.setOnClickListener(v -> savePreferences());

        new LoadAppsTask().execute();
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        allowedApps = new HashSet<>(prefs.getStringSet(PREF_ALLOWED_APPS, new HashSet<>()));
        profilingApps = new HashSet<>(prefs.getStringSet(PREF_PROFILING_APPS, new HashSet<>()));
    }

    private void savePreferences() {
        Set<String> injectApps = new HashSet<>();
        Set<String> profileApps = new HashSet<>();
        for (AppInfo info : appList) {
            switch (info.mode) {
                case INJECT:
                    injectApps.add(info.packageName);
                    break;
                case PROFILE:
                    profileApps.add(info.packageName);
                    break;
                default:
                    break;
            }
        }

        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putStringSet(PREF_ALLOWED_APPS, injectApps)
                .putStringSet(PREF_PROFILING_APPS, profileApps)
                .apply();

        // Make prefs readable by Xposed module
        makePrefsReadable();

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @SuppressWarnings("deprecation")
    private void makePrefsReadable() {
        try {
            java.io.File prefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
            java.io.File prefsFile = new java.io.File(prefsDir, SHARED_PREFS_NAME + ".xml");
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false);
                prefsDir.setExecutable(true, false);
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception ignored) {
        }
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {
        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> results = new ArrayList<>();

            for (ApplicationInfo packageInfo : packages) {
                AppInfo info = new AppInfo();
                info.name = packageInfo.loadLabel(pm).toString();
                info.packageName = packageInfo.packageName;
                info.icon = packageInfo.loadIcon(pm);
                if (profilingApps.contains(info.packageName)) {
                    info.mode = Mode.PROFILE;
                } else if (allowedApps.contains(info.packageName)) {
                    info.mode = Mode.INJECT;
                } else {
                    info.mode = Mode.OFF;
                }
                results.add(info);
            }

            Collections.sort(results, (a, b) -> a.name.compareToIgnoreCase(b.name));
            return results;
        }

        @Override
        protected void onPostExecute(List<AppInfo> results) {
            appList = results;
            adapter = new AppAdapter(AppSelectionActivity.this, appList);
            listView.setAdapter(adapter);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                AppInfo info = appList.get(position);
                info.mode = info.mode.next();
                adapter.notifyDataSetChanged();
            });
        }
    }

    private static class AppInfo {
        String name;
        String packageName;
        Drawable icon;
        Mode mode = Mode.OFF;
    }

    private class AppAdapter extends ArrayAdapter<AppInfo> {
        public AppAdapter(Context context, List<AppInfo> apps) {
            super(context, 0, apps);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.list_item_app_mode, parent, false);
            }
            AppInfo info = getItem(position);
            ImageView iconView = convertView.findViewById(R.id.app_icon);
            TextView title = convertView.findViewById(R.id.app_title);
            TextView subtitle = convertView.findViewById(R.id.app_subtitle);
            TextView modeView = convertView.findViewById(R.id.app_mode);

            iconView.setImageDrawable(info.icon);
            title.setText(info.name);
            subtitle.setText(info.packageName);
            modeView.setText("Mode: " + info.mode.label);

            return convertView;
        }
    }

    private enum Mode {
        OFF("Off"),
        INJECT("Inject"),
        PROFILE("Profile");

        final String label;

        Mode(String label) {
            this.label = label;
        }

        Mode next() {
            switch (this) {
                case OFF:
                    return INJECT;
                case INJECT:
                    return PROFILE;
                default:
                    return OFF;
            }
        }
    }
}
