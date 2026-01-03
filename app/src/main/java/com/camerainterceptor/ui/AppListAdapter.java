package com.camerainterceptor.ui;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.camerainterceptor.R;
import com.google.android.material.chip.Chip;

import java.util.Objects;

/**
 * RecyclerView adapter for displaying apps with DiffUtil for efficient updates
 */
public class AppListAdapter extends ListAdapter<AppListAdapter.AppInfo, AppListAdapter.AppViewHolder> {

    private OnAppModeChangeListener modeChangeListener;

    public interface OnAppModeChangeListener {
        void onModeChanged(AppInfo appInfo, Mode newMode);
    }

    public AppListAdapter() {
        super(DIFF_CALLBACK);
    }

    public void setOnAppModeChangeListener(OnAppModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    private static final DiffUtil.ItemCallback<AppInfo> DIFF_CALLBACK = new DiffUtil.ItemCallback<AppInfo>() {
        @Override
        public boolean areItemsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            return oldItem.packageName.equals(newItem.packageName);
        }

        @Override
        public boolean areContentsTheSame(@NonNull AppInfo oldItem, @NonNull AppInfo newItem) {
            return oldItem.name.equals(newItem.name) 
                    && oldItem.mode == newItem.mode
                    && oldItem.isSystemApp == newItem.isSystemApp;
        }
    };

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo appInfo = getItem(position);
        holder.bind(appInfo, modeChangeListener);
    }

    /**
     * ViewHolder for app list items
     */
    static class AppViewHolder extends RecyclerView.ViewHolder {
        private final ImageView iconView;
        private final TextView nameView;
        private final TextView packageView;
        private final TextView systemBadge;
        private final Chip modeChip;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.app_icon);
            nameView = itemView.findViewById(R.id.app_name);
            packageView = itemView.findViewById(R.id.app_package);
            systemBadge = itemView.findViewById(R.id.system_badge);
            modeChip = itemView.findViewById(R.id.mode_chip);
        }

        void bind(AppInfo appInfo, OnAppModeChangeListener listener) {
            iconView.setImageDrawable(appInfo.icon);
            nameView.setText(appInfo.name);
            packageView.setText(appInfo.packageName);
            
            // Show system badge if system app
            systemBadge.setVisibility(appInfo.isSystemApp ? View.VISIBLE : View.GONE);
            
            // Update mode chip
            updateModeChip(appInfo.mode);
            
            // Mode chip click - cycle through modes
            modeChip.setOnClickListener(v -> {
                Mode newMode = appInfo.mode.next();
                appInfo.mode = newMode;
                updateModeChip(newMode);
                if (listener != null) {
                    listener.onModeChanged(appInfo, newMode);
                }
            });
            
            // Item click also cycles mode
            itemView.setOnClickListener(v -> {
                modeChip.performClick();
            });
        }

        private void updateModeChip(Mode mode) {
            modeChip.setText(mode.label);
            
            // Update chip appearance based on mode
            int chipColorRes;
            int textColorRes;
            
            switch (mode) {
                case INJECT:
                    chipColorRes = com.google.android.material.R.attr.colorPrimaryContainer;
                    textColorRes = com.google.android.material.R.attr.colorOnPrimaryContainer;
                    break;
                case PROFILE:
                    chipColorRes = com.google.android.material.R.attr.colorTertiaryContainer;
                    textColorRes = com.google.android.material.R.attr.colorOnTertiaryContainer;
                    break;
                default: // OFF
                    chipColorRes = com.google.android.material.R.attr.colorSurfaceVariant;
                    textColorRes = com.google.android.material.R.attr.colorOnSurfaceVariant;
                    break;
            }
            
            // Apply colors using theme attributes
            int chipColor = getThemeColor(chipColorRes);
            int textColor = getThemeColor(textColorRes);
            
            modeChip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(chipColor));
            modeChip.setTextColor(textColor);
        }
        
        private int getThemeColor(int attrRes) {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            itemView.getContext().getTheme().resolveAttribute(attrRes, typedValue, true);
            return typedValue.data;
        }
    }

    /**
     * App information data class
     */
    public static class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public Mode mode = Mode.OFF;
        public boolean isSystemApp = false;

        public AppInfo() {}

        public AppInfo(String name, String packageName, Drawable icon, boolean isSystemApp) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSystemApp = isSystemApp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AppInfo appInfo = (AppInfo) o;
            return isSystemApp == appInfo.isSystemApp &&
                    mode == appInfo.mode &&
                    Objects.equals(name, appInfo.name) &&
                    Objects.equals(packageName, appInfo.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, packageName, mode, isSystemApp);
        }

        /**
         * Create a copy with a different mode
         */
        public AppInfo withMode(Mode newMode) {
            AppInfo copy = new AppInfo(name, packageName, icon, isSystemApp);
            copy.mode = newMode;
            return copy;
        }
    }

    /**
     * Interception modes for apps
     */
    public enum Mode {
        OFF("Off"),
        INJECT("Inject"),
        PROFILE("Profile");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
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
