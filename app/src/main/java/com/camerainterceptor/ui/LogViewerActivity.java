package com.camerainterceptor.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.camerainterceptor.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for viewing and managing application logs.
 * Features:
 * - Syntax highlighting by log level
 * - Pull-to-refresh
 * - Search within logs
 * - Filter by log level
 * - Share, export, and copy functionality
 * - Clear logs with undo
 */
public class LogViewerActivity extends AppCompatActivity {

    private static final String LOG_FILE_DIR = "CameraInterceptor";
    private static final String LOG_FILE_NAME = "camera_interceptor_log.txt";

    // Views
    private RecyclerView recyclerLogs;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout layoutLoading;
    private LinearLayout layoutEmpty;
    private LinearLayout layoutError;
    private FloatingActionButton fabClear;
    private BottomAppBar bottomAppBar;

    // Filter chips
    private Chip chipError;
    private Chip chipWarn;
    private Chip chipInfo;
    private Chip chipDebug;

    // Data
    private LogEntryAdapter adapter;
    private List<LogEntry> allLogEntries = new ArrayList<>();
    private List<LogEntry> filteredLogEntries = new ArrayList<>();
    private String searchQuery = "";
    private Set<LogEntry.Level> activeFilters = EnumSet.allOf(LogEntry.Level.class);
    
    // Async
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // For undo functionality
    private String deletedLogsContent = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        initViews();
        setupToolbar();
        setupRecyclerView();
        setupSwipeRefresh();
        setupFilterChips();
        setupBottomBar();
        setupFab();

        loadLogs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initViews() {
        recyclerLogs = findViewById(R.id.recycler_logs);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        layoutLoading = findViewById(R.id.layout_loading);
        layoutEmpty = findViewById(R.id.layout_empty);
        layoutError = findViewById(R.id.layout_error);
        fabClear = findViewById(R.id.fab_clear);
        bottomAppBar = findViewById(R.id.bottom_app_bar);

        chipError = findViewById(R.id.chip_error);
        chipWarn = findViewById(R.id.chip_warn);
        chipInfo = findViewById(R.id.chip_info);
        chipDebug = findViewById(R.id.chip_debug);

        findViewById(R.id.button_retry).setOnClickListener(v -> loadLogs());
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        adapter = new LogEntryAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Start from bottom (newest logs)
        recyclerLogs.setLayoutManager(layoutManager);
        recyclerLogs.setAdapter(adapter);
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.md_theme_light_primary);
        swipeRefresh.setOnRefreshListener(this::loadLogs);
    }

    private void setupFilterChips() {
        // Initially all filters are active (checked)
        chipError.setChecked(true);
        chipWarn.setChecked(true);
        chipInfo.setChecked(true);
        chipDebug.setChecked(true);

        View.OnClickListener filterListener = v -> {
            updateActiveFilters();
            applyFilters();
        };

        chipError.setOnClickListener(filterListener);
        chipWarn.setOnClickListener(filterListener);
        chipInfo.setOnClickListener(filterListener);
        chipDebug.setOnClickListener(filterListener);
    }

    private void updateActiveFilters() {
        activeFilters.clear();
        if (chipError.isChecked()) activeFilters.add(LogEntry.Level.ERROR);
        if (chipWarn.isChecked()) activeFilters.add(LogEntry.Level.WARN);
        if (chipInfo.isChecked()) activeFilters.add(LogEntry.Level.INFO);
        if (chipDebug.isChecked()) {
            activeFilters.add(LogEntry.Level.DEBUG);
            activeFilters.add(LogEntry.Level.VERBOSE);
            activeFilters.add(LogEntry.Level.UNKNOWN);
        }
    }

    private void setupBottomBar() {
        bottomAppBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_share) {
                shareLogs();
                return true;
            } else if (id == R.id.action_export) {
                exportLogs();
                return true;
            } else if (id == R.id.action_copy) {
                copyAllLogs();
                return true;
            }
            return false;
        });
    }

    private void setupFab() {
        fabClear.setOnClickListener(v -> showClearConfirmation());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_log_viewer, menu);

        // Setup SearchView
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.log_search_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    searchQuery = newText;
                    applyFilters();
                    return true;
                }
            });
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_scroll_bottom) {
            scrollToBottom();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadLogs() {
        showLoading();
        
        executor.execute(() -> {
            File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
            File logFile = new File(logDir, LOG_FILE_NAME);

            List<LogEntry> entries = new ArrayList<>();
            String error = null;

            if (!logFile.exists()) {
                // Not an error, just empty
            } else {
                try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                    String line;
                    long id = 0;
                    while ((line = br.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            entries.add(LogEntry.parse(id++, line));
                        }
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                }
            }

            final List<LogEntry> finalEntries = entries;
            final String finalError = error;

            mainHandler.post(() -> {
                swipeRefresh.setRefreshing(false);
                
                if (finalError != null) {
                    showError(finalError);
                } else {
                    allLogEntries = finalEntries;
                    applyFilters();
                }
            });
        });
    }

    private void applyFilters() {
        filteredLogEntries.clear();

        for (LogEntry entry : allLogEntries) {
            // Check level filter
            if (!activeFilters.contains(entry.getLevel())) {
                continue;
            }

            // Check search query
            if (!searchQuery.isEmpty()) {
                String lowerQuery = searchQuery.toLowerCase();
                boolean matches = entry.getRawLine().toLowerCase().contains(lowerQuery) ||
                        (entry.getMessage() != null && entry.getMessage().toLowerCase().contains(lowerQuery)) ||
                        (entry.getTag() != null && entry.getTag().toLowerCase().contains(lowerQuery));
                if (!matches) {
                    continue;
                }
            }

            filteredLogEntries.add(entry);
        }

        adapter.submitList(new ArrayList<>(filteredLogEntries));

        if (filteredLogEntries.isEmpty()) {
            if (allLogEntries.isEmpty()) {
                showEmpty();
            } else {
                // Has logs but filters hide all
                showEmptyFiltered();
            }
        } else {
            showContent();
        }
    }

    private void showLoading() {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        recyclerLogs.setVisibility(View.GONE);
    }

    private void showContent() {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        recyclerLogs.setVisibility(View.VISIBLE);
    }

    private void showEmpty() {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        recyclerLogs.setVisibility(View.GONE);
        
        ((android.widget.TextView) findViewById(R.id.text_empty_title)).setText(R.string.log_empty_title);
        ((android.widget.TextView) findViewById(R.id.text_empty_subtitle)).setText(R.string.log_empty_subtitle);
    }

    private void showEmptyFiltered() {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        recyclerLogs.setVisibility(View.GONE);
        
        ((android.widget.TextView) findViewById(R.id.text_empty_title)).setText(R.string.log_no_matches);
        ((android.widget.TextView) findViewById(R.id.text_empty_subtitle)).setText(R.string.log_adjust_filters);
    }

    private void showError(String message) {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        recyclerLogs.setVisibility(View.GONE);
        
        ((android.widget.TextView) findViewById(R.id.text_error)).setText(
                getString(R.string.log_error_detail, message));
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) {
            recyclerLogs.smoothScrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void showClearConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_message)
                .setPositiveButton(R.string.log_clear_confirm, (dialog, which) -> clearLogs())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearLogs() {
        executor.execute(() -> {
            File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
            File logFile = new File(logDir, LOG_FILE_NAME);

            // Save content for undo
            if (logFile.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    deletedLogsContent = sb.toString();
                } catch (IOException e) {
                    deletedLogsContent = null;
                }
            }

            boolean success = !logFile.exists() || logFile.delete();

            mainHandler.post(() -> {
                if (success) {
                    allLogEntries.clear();
                    applyFilters();
                    
                    Snackbar snackbar = Snackbar.make(fabClear, R.string.log_cleared, Snackbar.LENGTH_LONG);
                    if (deletedLogsContent != null && !deletedLogsContent.isEmpty()) {
                        snackbar.setAction(R.string.log_undo, v -> restoreLogs());
                    }
                    snackbar.setAnchorView(fabClear);
                    snackbar.show();
                } else {
                    Snackbar.make(fabClear, R.string.log_clear_failed, Snackbar.LENGTH_SHORT)
                            .setAnchorView(fabClear)
                            .show();
                }
            });
        });
    }

    private void restoreLogs() {
        if (deletedLogsContent == null || deletedLogsContent.isEmpty()) {
            return;
        }

        executor.execute(() -> {
            File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            File logFile = new File(logDir, LOG_FILE_NAME);

            boolean success = false;
            try (FileWriter writer = new FileWriter(logFile)) {
                writer.write(deletedLogsContent);
                success = true;
            } catch (IOException e) {
                // Failed to restore
            }

            final boolean finalSuccess = success;
            mainHandler.post(() -> {
                if (finalSuccess) {
                    loadLogs();
                    Snackbar.make(fabClear, R.string.log_restored, Snackbar.LENGTH_SHORT)
                            .setAnchorView(fabClear)
                            .show();
                } else {
                    Snackbar.make(fabClear, R.string.log_restore_failed, Snackbar.LENGTH_SHORT)
                            .setAnchorView(fabClear)
                            .show();
                }
                deletedLogsContent = null;
            });
        });
    }

    private void shareLogs() {
        File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
        File logFile = new File(logDir, LOG_FILE_NAME);

        if (!logFile.exists()) {
            Snackbar.make(fabClear, R.string.log_empty_title, Snackbar.LENGTH_SHORT)
                    .setAnchorView(fabClear)
                    .show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", logFile);
            
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.log_share)));
        } catch (Exception e) {
            // Fallback to sharing as text
            StringBuilder content = new StringBuilder();
            for (LogEntry entry : filteredLogEntries) {
                content.append(entry.getRawLine()).append("\n");
            }
            
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, content.toString());
            startActivity(Intent.createChooser(shareIntent, getString(R.string.log_share)));
        }
    }

    private void exportLogs() {
        // For now, share as export (could implement SAF for proper file saving)
        shareLogs();
    }

    private void copyAllLogs() {
        if (filteredLogEntries.isEmpty()) {
            Snackbar.make(fabClear, R.string.log_empty_title, Snackbar.LENGTH_SHORT)
                    .setAnchorView(fabClear)
                    .show();
            return;
        }

        StringBuilder content = new StringBuilder();
        for (LogEntry entry : filteredLogEntries) {
            content.append(entry.getRawLine()).append("\n");
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("logs", content.toString());
            clipboard.setPrimaryClip(clip);
            Snackbar.make(fabClear, R.string.log_copied, Snackbar.LENGTH_SHORT)
                    .setAnchorView(fabClear)
                    .show();
        }
    }
}
