package com.camerainterceptor.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.camerainterceptor.R;

/**
 * RecyclerView Adapter for log entries with syntax highlighting by log level.
 * Uses ListAdapter with DiffUtil for efficient updates.
 */
public class LogEntryAdapter extends ListAdapter<LogEntry, LogEntryAdapter.LogViewHolder> {

    private static final DiffUtil.ItemCallback<LogEntry> DIFF_CALLBACK = new DiffUtil.ItemCallback<LogEntry>() {
        @Override
        public boolean areItemsTheSame(@NonNull LogEntry oldItem, @NonNull LogEntry newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull LogEntry oldItem, @NonNull LogEntry newItem) {
            return oldItem.equals(newItem);
        }
    };

    public LogEntryAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_entry, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        private final View levelIndicator;
        private final TextView textTimestamp;
        private final TextView textLevel;
        private final TextView textMessage;
        private final TextView textTag;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            levelIndicator = itemView.findViewById(R.id.level_indicator);
            textTimestamp = itemView.findViewById(R.id.text_timestamp);
            textLevel = itemView.findViewById(R.id.text_level);
            textMessage = itemView.findViewById(R.id.text_message);
            textTag = itemView.findViewById(R.id.text_tag);
        }

        void bind(LogEntry entry) {
            int levelColor = getLevelColor(entry.getLevel());

            // Set level indicator color
            levelIndicator.setBackgroundColor(levelColor);

            // Set timestamp
            if (entry.getTimestamp() != null && !entry.getTimestamp().isEmpty()) {
                textTimestamp.setVisibility(View.VISIBLE);
                textTimestamp.setText(entry.getTimestamp());
            } else {
                textTimestamp.setVisibility(View.GONE);
            }

            // Set level badge
            textLevel.setText(entry.getLevel().getTag());
            GradientDrawable background = (GradientDrawable) ContextCompat.getDrawable(
                    itemView.getContext(), R.drawable.bg_badge);
            if (background != null) {
                background = (GradientDrawable) background.mutate();
                background.setColor(levelColor);
                textLevel.setBackground(background);
            }

            // Set message
            String message = entry.getMessage();
            if (message == null || message.isEmpty()) {
                message = entry.getRawLine();
            }
            textMessage.setText(message);

            // Set tag
            if (entry.getTag() != null && !entry.getTag().isEmpty()) {
                textTag.setVisibility(View.VISIBLE);
                textTag.setText(entry.getTag());
            } else {
                textTag.setVisibility(View.GONE);
            }
        }

        private int getLevelColor(LogEntry.Level level) {
            int colorRes;
            switch (level) {
                case ERROR:
                    colorRes = R.color.log_error;
                    break;
                case WARN:
                    colorRes = R.color.log_warn;
                    break;
                case INFO:
                    colorRes = R.color.log_info;
                    break;
                case DEBUG:
                    colorRes = R.color.log_debug;
                    break;
                case VERBOSE:
                    colorRes = R.color.log_verbose;
                    break;
                default:
                    colorRes = R.color.log_debug;
                    break;
            }
            return ContextCompat.getColor(itemView.getContext(), colorRes);
        }
    }
}
