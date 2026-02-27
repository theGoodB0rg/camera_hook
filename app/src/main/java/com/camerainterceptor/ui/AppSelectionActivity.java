package com.camerainterceptor.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.camerainterceptor.R;
import com.camerainterceptor.utils.Logger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App Selection Activity with RecyclerView, search, and filter support
 */
public class AppSelectionActivity extends AppCompatActivity {
    private static final String TAG = "AppSelectionActivity";

    // Views
    private RecyclerView recyclerView;
    private FrameLayout loadingContainer;
    private LinearLayout emptyStateContainer;
    private ExtendedFloatingActionButton fabSave;
    private ChipGroup chipGroupFilter;
    private Chip chipAll, chipUser, chipSystem, chipEnabled;

    // Data
    private AppListAdapter adapter;
    private List<AppListAdapter.AppInfo> allApps = new ArrayList<>();
    private List<AppListAdapter.AppInfo> filteredApps = new ArrayList<>();
    private Set<String> allowedApps = new HashSet<>(); // SAFE mode
    private Set<String> deepApps = new HashSet<>(); // DEEP mode
    private Set<String> profilingApps = new HashSet<>(); // PROFILE mode

    // Filter state
    private String currentSearchQuery = "";
    private FilterType currentFilter = FilterType.ALL;

    // Executor for background work
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static final String SHARED_PREFS_NAME = "CameraInterceptorPrefs";
    public static final String PREF_ALLOWED_APPS = "allowed_apps"; // SAFE mode
    public static final String PREF_DEEP_APPS = "deep_apps"; // DEEP mode
    public static final String PREF_PROFILING_APPS = "profiling_apps";

    private enum FilterType {
        ALL, USER, SYSTEM, ENABLED
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection_new);

        initViews();
        setupToolbar();
        setupRecyclerView();
        setupFilterChips();
        setupFab();
        loadPreferences();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_apps);
        loadingContainer = findViewById(R.id.loading_container);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        fabSave = findViewById(R.id.fab_save);
        chipGroupFilter = findViewById(R.id.chip_group_filter);
        chipAll = findViewById(R.id.chip_all);
        chipUser = findViewById(R.id.chip_user);
        chipSystem = findViewById(R.id.chip_system);
        chipEnabled = findViewById(R.id.chip_enabled);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

    private void setupRecyclerView() {
        adapter = new AppListAdapter();
        adapter.setOnAppModeChangeListener((appInfo, newMode) -> {
            // Update the mode in allApps list
            for (AppListAdapter.AppInfo app : allApps) {
                if (app.packageName.equals(appInfo.packageName)) {
                    app.mode = newMode;
                    break;
                }
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // FAB scroll behavior - shrink on scroll down, extend on scroll up
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && fabSave.isExtended()) {
                    fabSave.shrink();
                } else if (dy < 0 && !fabSave.isExtended()) {
                    fabSave.extend();
                }
            }
        });
    }

    private void setupFilterChips() {
        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty())
                return;

            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chip_all) {
                currentFilter = FilterType.ALL;
            } else if (checkedId == R.id.chip_user) {
                currentFilter = FilterType.USER;
            } else if (checkedId == R.id.chip_system) {
                currentFilter = FilterType.SYSTEM;
            } else if (checkedId == R.id.chip_enabled) {
                currentFilter = FilterType.ENABLED;
            }
            applyFilters();
        });
    }

    private void setupFab() {
        fabSave.setOnClickListener(v -> savePreferences());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_app_selection, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();

        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_apps_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    currentSearchQuery = newText.toLowerCase().trim();
                    applyFilters();
                    return true;
                }
            });
        }

        return true;
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        allowedApps = new HashSet<>(prefs.getStringSet(PREF_ALLOWED_APPS, new HashSet<>()));
        deepApps = new HashSet<>(prefs.getStringSet(PREF_DEEP_APPS, new HashSet<>()));
        profilingApps = new HashSet<>(prefs.getStringSet(PREF_PROFILING_APPS, new HashSet<>()));
    }

    private void loadApps() {
        showLoading(true);

        executor.execute(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<AppListAdapter.AppInfo> results = new ArrayList<>();

                for (ApplicationInfo packageInfo : packages) {
                    AppListAdapter.AppInfo info = new AppListAdapter.AppInfo();
                    info.name = packageInfo.loadLabel(pm).toString();
                    info.packageName = packageInfo.packageName;
                    info.icon = packageInfo.loadIcon(pm);
                    info.isSystemApp = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    if (profilingApps.contains(info.packageName)) {
                        info.mode = AppListAdapter.Mode.PROFILE;
                    } else if (deepApps.contains(info.packageName)) {
                        info.mode = AppListAdapter.Mode.DEEP;
                    } else if (allowedApps.contains(info.packageName)) {
                        info.mode = AppListAdapter.Mode.SAFE;
                    } else {
                        info.mode = AppListAdapter.Mode.OFF;
                    }
                    results.add(info);
                }

                Collections.sort(results, (a, b) -> a.name.compareToIgnoreCase(b.name));

                runOnUiThread(() -> {
                    allApps = results;
                    applyFilters();
                    showLoading(false);
                });
            } catch (Exception e) {
                Logger.e(TAG, "Error loading apps: " + e.getMessage());
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, R.string.error_loading_apps, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void applyFilters() {
        filteredApps.clear();

        for (AppListAdapter.AppInfo app : allApps) {
            // Apply filter type
            boolean passesFilter = false;
            switch (currentFilter) {
                case ALL:
                    passesFilter = true;
                    break;
                case USER:
                    passesFilter = !app.isSystemApp;
                    break;
                case SYSTEM:
                    passesFilter = app.isSystemApp;
                    break;
                case ENABLED:
                    passesFilter = app.mode != AppListAdapter.Mode.OFF;
                    break;
            }

            if (!passesFilter)
                continue;

            // Apply search query
            if (!currentSearchQuery.isEmpty()) {
                boolean matchesSearch = app.name.toLowerCase().contains(currentSearchQuery)
                        || app.packageName.toLowerCase().contains(currentSearchQuery);
                if (!matchesSearch)
                    continue;
            }

            filteredApps.add(app);
        }

        adapter.submitList(new ArrayList<>(filteredApps));
        updateEmptyState();
    }

    private void showLoading(boolean loading) {
        loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(loading ? View.GONE : View.VISIBLE);
        if (loading) {
            emptyStateContainer.setVisibility(View.GONE);
        }
    }

    private void updateEmptyState() {
        boolean isEmpty = filteredApps.isEmpty() && !allApps.isEmpty();
        emptyStateContainer.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void savePreferences() {
        Set<String> safeApps = new HashSet<>();
        Set<String> deepApps = new HashSet<>();
        Set<String> profileApps = new HashSet<>();

        for (AppListAdapter.AppInfo info : allApps) {
            switch (info.mode) {
                case SAFE:
                    safeApps.add(info.packageName);
                    break;
                case DEEP:
                    deepApps.add(info.packageName);
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
                .putStringSet(PREF_ALLOWED_APPS, safeApps)
                .putStringSet(PREF_DEEP_APPS, deepApps)
                .putStringSet(PREF_PROFILING_APPS, profileApps)
                .apply();

        makePrefsReadable();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
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
}
