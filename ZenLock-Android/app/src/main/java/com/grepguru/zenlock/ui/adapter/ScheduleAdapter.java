package com.grepguru.zenlock.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.grepguru.zenlock.R;
import com.grepguru.zenlock.model.ScheduleModel;

import java.util.List;

/**
 * Adapter for displaying schedules in RecyclerView
 * Features expandable items with brief info initially, full details when expanded
 */
public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.ScheduleViewHolder> {
    
    private List<ScheduleModel> schedules;
    private ScheduleListener listener;
    private int expandedPosition = -1; // Track which item is expanded
    
    public interface ScheduleListener {
        void onToggleSchedule(ScheduleModel schedule);
        void onEditSchedule(ScheduleModel schedule);
        void onDeleteSchedule(ScheduleModel schedule);
    }
    
    public ScheduleAdapter(List<ScheduleModel> schedules, ScheduleListener listener) {
        this.schedules = schedules;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ScheduleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_schedule, parent, false);
        return new ScheduleViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ScheduleViewHolder holder, int position) {
        ScheduleModel schedule = schedules.get(position);
        holder.bind(schedule, position);
    }
    
    @Override
    public int getItemCount() {
        return schedules.size();
    }
    
    class ScheduleViewHolder extends RecyclerView.ViewHolder {
        
        private LinearLayout scheduleCard;
        private TextView scheduleName;
        private TextView scheduleTime;
        private TextView scheduleStatus;
        private ImageView expandIcon;
        private LinearLayout expandedContent;
        
        // Expanded content views
        private TextView scheduleDetails;
        private TextView repeatInfo;
        private TextView notificationInfo;
        private TextView endTimeInfo;
        private Button toggleButton;
        private Button editButton;
        private Button deleteButton;
        
        public ScheduleViewHolder(@NonNull View itemView) {
            super(itemView);
            
            scheduleCard = itemView.findViewById(R.id.scheduleCard);
            scheduleName = itemView.findViewById(R.id.scheduleName);
            scheduleTime = itemView.findViewById(R.id.scheduleTime);
            scheduleStatus = itemView.findViewById(R.id.scheduleStatus);
            expandIcon = itemView.findViewById(R.id.expandIcon);
            expandedContent = itemView.findViewById(R.id.expandedContent);
            
            scheduleDetails = itemView.findViewById(R.id.scheduleDetails);
            repeatInfo = itemView.findViewById(R.id.repeatInfo);
            notificationInfo = itemView.findViewById(R.id.notificationInfo);
            endTimeInfo = itemView.findViewById(R.id.endTimeInfo);
            toggleButton = itemView.findViewById(R.id.toggleButton);
            editButton = itemView.findViewById(R.id.editButton);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }
        
        public void bind(ScheduleModel schedule, int position) {
            // Basic info (always visible)
            scheduleName.setText(schedule.getName());
            scheduleTime.setText(schedule.getFormattedStartTime());
            
            // Status indicator
            if (schedule.isEnabled()) {
                scheduleStatus.setText("Active");
                scheduleStatus.setTextColor(itemView.getContext().getColor(R.color.green));
            } else {
                scheduleStatus.setText("Inactive");
                scheduleStatus.setTextColor(itemView.getContext().getColor(R.color.gray));
            }
            
            // Expanded content
            scheduleDetails.setText(schedule.getFormattedDuration() + " focus session");
            repeatInfo.setText("Repeat: " + schedule.getRepeatDescription());
            notificationInfo.setText("Notify: " + schedule.getPreNotifyDescription());
            endTimeInfo.setText("Ends at: " + schedule.getFormattedEndTime());
            
            // Toggle / Edit / Delete — only allowed within 20 min of start time
            boolean editAllowed = isEditWindowOpen(schedule);

            toggleButton.setText(schedule.isEnabled() ? "Disable" : "Enable");
            toggleButton.setAlpha(editAllowed ? 1f : 0.4f);
            toggleButton.setOnClickListener(v -> {
                if (!isEditWindowOpen(schedule)) {
                    android.widget.Toast.makeText(v.getContext(),
                        "Can only change schedule within 20 min of start time",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (listener != null) {
                    listener.onToggleSchedule(schedule);
                }
            });

            editButton.setAlpha(editAllowed ? 1f : 0.4f);
            editButton.setOnClickListener(v -> {
                if (!isEditWindowOpen(schedule)) {
                    android.widget.Toast.makeText(v.getContext(),
                        "Can only edit schedule within 20 min of start time",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (listener != null) {
                    listener.onEditSchedule(schedule);
                }
            });

            deleteButton.setAlpha(editAllowed ? 1f : 0.4f);
            deleteButton.setOnClickListener(v -> {
                if (!isEditWindowOpen(schedule)) {
                    android.widget.Toast.makeText(v.getContext(),
                        "Can only delete schedule within 20 min of start time",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (listener != null) {
                    listener.onDeleteSchedule(schedule);
                }
            });
            
            // Expand/collapse functionality
            boolean isExpanded = position == expandedPosition;
            expandedContent.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            expandIcon.setRotation(isExpanded ? 180 : 0);
            
            scheduleCard.setOnClickListener(v -> {
                if (expandedPosition == position) {
                    // Collapse
                    expandedPosition = -1;
                    notifyItemChanged(position);
                } else {
                    // Expand
                    int previousExpanded = expandedPosition;
                    expandedPosition = position;
                    notifyItemChanged(previousExpanded);
                    notifyItemChanged(position);
                }
            });
        }
    }

    /**
     * Returns true if the schedule's next start time is within 20 minutes from now,
     * meaning edits/deletes/disabling are permitted.
     */
    private static boolean isEditWindowOpen(ScheduleModel schedule) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar next = java.util.Calendar.getInstance();
        next.set(java.util.Calendar.HOUR_OF_DAY, schedule.getStartHour());
        next.set(java.util.Calendar.MINUTE, schedule.getStartMinute());
        next.set(java.util.Calendar.SECOND, 0);
        next.set(java.util.Calendar.MILLISECOND, 0);

        // If start time has already passed today, move to tomorrow
        if (!next.after(now)) {
            next.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }

        long diffMs = next.getTimeInMillis() - now.getTimeInMillis();
        long diffMinutes = diffMs / 60000L;
        return diffMinutes <= 20;
    }

}