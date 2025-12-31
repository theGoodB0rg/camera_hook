package com.camerainterceptor.ui;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.camerainterceptor.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class LogViewerActivity extends Activity {

    private TextView logTextView;
    private ScrollView scrollView;
    private static final String LOG_FILE_DIR = "CameraInterceptor";
    private static final String LOG_FILE_NAME = "camera_interceptor_log.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        logTextView = findViewById(R.id.text_logs);
        scrollView = findViewById(R.id.scroll_view);

        Button refreshButton = findViewById(R.id.button_refresh);
        refreshButton.setOnClickListener(v -> loadLogs());

        Button clearButton = findViewById(R.id.button_clear);
        clearButton.setOnClickListener(v -> clearLogs());

        loadLogs();
    }

    private void loadLogs() {
        new LoadLogsTask().execute();
    }

    private void clearLogs() {
        try {
            File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
            File logFile = new File(logDir, LOG_FILE_NAME);

            if (logFile.exists()) {
                if (logFile.delete()) {
                    logTextView.setText("Logs cleared.");
                    Toast.makeText(this, "Logs cleared successfully", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to delete log file", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No logs found", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error clearing logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class LoadLogsTask extends AsyncTask<Void, Void, String> {
        @Override
        protected void onPreExecute() {
            logTextView.setText("Loading...");
        }

        @Override
        protected String doInBackground(Void... voids) {
            File logDir = new File(Environment.getExternalStorageDirectory(), LOG_FILE_DIR);
            File logFile = new File(logDir, LOG_FILE_NAME);

            if (!logFile.exists()) {
                return "No logs found at " + logFile.getAbsolutePath();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                String line;
                // Read last 2000 lines effectively? Or just read all for now.
                // Log files are capped at 5MB, reading might take a moment but is okay for
                // text.
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                return "Error reading logs: " + e.getMessage();
            }
            return sb.toString();
        }

        @Override
        protected void onPostExecute(String result) {
            logTextView.setText(result);
            // Scroll to bottom
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }
}
