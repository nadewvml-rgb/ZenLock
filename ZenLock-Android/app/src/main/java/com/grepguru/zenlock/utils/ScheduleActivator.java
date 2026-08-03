package com.grepguru.zenlock.utils;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.grepguru.zenlock.LockScreenActivity;
import com.grepguru.zenlock.LockScreenService;
import com.grepguru.zenlock.OverlayLockService;
import com.grepguru.zenlock.PreNotificationReceiver;
import com.grepguru.zenlock.ScheduleTriggerReceiver;
import com.grepguru.zenlock.model.ScheduleModel;

import java.util.Calendar;
import java.util.List;

/**
 * ScheduleActivator — schedules AlarmManager triggers for focus sessions.
 *
 * Reliability fix: scheduleSchedule() now checks whether the schedule's time
 * window is CURRENTLY active (i.e. start time already passed but end time has
 * not yet passed).  If so, it starts the lock session immediately rather than
 * waiting until the next occurrence.  This fixes the bug where re-enabling a
 * daily schedule mid-window did nothing until the following day.
 */
public class ScheduleActivator {

    private static final String TAG = "ScheduleActivator";

    private final Context        context;
    private final ScheduleManager scheduleManager;
    private final AlarmManager   alarmManager;

    public ScheduleActivator(Context context) {
        this.context         = context;
        this.scheduleManager = new ScheduleManager(context);
        this.alarmManager    = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void scheduleAllSchedules() {
        List<ScheduleModel> enabled = scheduleManager.getEnabledSchedules();
        Log.d(TAG, "Scheduling " + enabled.size() + " enabled schedules");
        for (ScheduleModel s : enabled) scheduleSchedule(s);
    }

    public void rescheduleAllSchedules() {
        scheduleAllSchedules();
    }

    public void cancelAllSchedules() {
        for (ScheduleModel s : scheduleManager.getAllSchedules()) cancelSchedule(s);
        Log.d(TAG, "Cancelled all scheduled alarms");
    }

    /**
     * Schedule a specific schedule's next alarm.
     *
     * NEW: if the current wall-clock time falls inside the schedule's active
     * window (start ≤ now < start + duration) and no lock is already running,
     * start the session immediately instead of waiting for the next occurrence.
     */
    public void scheduleSchedule(ScheduleModel schedule) {
        if (!schedule.isEnabled()) {
            Log.d(TAG, schedule.getName() + " is disabled, skipping");
            return;
        }

        try {
            // --- Mid-window catch-up ---
            if (isWindowCurrentlyActive(schedule)) {
                SharedPreferences prefs =
                    context.getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE);
                boolean alreadyLocked = prefs.getBoolean("isLocked", false);
                long    lockEndTime   = prefs.getLong("lockEndTime", 0);
                long    now           = System.currentTimeMillis();

                if (!alreadyLocked || (lockEndTime > 0 && now >= lockEndTime)) {
                    Log.d(TAG, schedule.getName() + " is mid-window — starting session now");
                    startSessionNow(schedule);
                    // Still schedule the next future alarm so the schedule continues
                }
            }

            // --- Schedule next future alarm ---
            Calendar triggerTime = getNextTriggerTime(schedule);
            if (triggerTime == null) {
                Log.w(TAG, "No future trigger time for " + schedule.getName());
                return;
            }
            scheduleMainTrigger(schedule, triggerTime);
            if (schedule.isPreNotifyEnabled() && schedule.getPreNotifyMinutes() > 0) {
                schedulePreNotification(schedule, triggerTime);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule " + schedule.getName(), e);
        }
    }

    public void cancelSchedule(ScheduleModel schedule) {
        try {
            Intent intent = new Intent(context, ScheduleTriggerReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, schedule.getId(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (alarmManager != null) alarmManager.cancel(pi);

            Intent preIntent = new Intent(context, PreNotificationReceiver.class);
            PendingIntent prePi = PendingIntent.getBroadcast(context, -schedule.getId(),
                preIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (alarmManager != null) alarmManager.cancel(prePi);

            Log.d(TAG, "Cancelled alarms for: " + schedule.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel " + schedule.getName(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true if the schedule's time window is currently active.
     * e.g. schedule starts at 09:00 for 60 min → window is 09:00–10:00.
     * If the local clock is 09:30, this returns true.
     */
    private boolean isWindowCurrentlyActive(ScheduleModel schedule) {
        Calendar now   = Calendar.getInstance();
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, schedule.getStartHour());
        start.set(Calendar.MINUTE, schedule.getStartMinute());
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        // If start is in the future today there is no active window to catch up to
        if (start.after(now)) return false;

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MINUTE, schedule.getFocusDurationMinutes());

        // Window is active if: start <= now < end
        return now.before(end);
    }

    /**
     * Start the focus session immediately (mirrors what ScheduleTriggerReceiver does).
     */
    private void startSessionNow(ScheduleModel schedule) {
        try {
            // Write session state to prefs
            SharedPreferences prefs =
                context.getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE);

            // Compute how many minutes remain in the current window
            Calendar now   = Calendar.getInstance();
            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, schedule.getStartHour());
            start.set(Calendar.MINUTE, schedule.getStartMinute());
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);

            long totalDurationMs  = schedule.getFocusDurationMinutes() * 60_000L;
            long elapsedMs        = now.getTimeInMillis() - start.getTimeInMillis();
            long remainingMs      = totalDurationMs - elapsedMs;

            if (remainingMs <= 0) return; // Already ended

            long lockStartTime = now.getTimeInMillis();
            long lockEndTime   = lockStartTime + remainingMs;

            prefs.edit()
                 .putBoolean("isLocked", true)
                 .putLong("lockStartTime", lockStartTime)
                 .putLong("lockEndTime", lockEndTime)
                 .putLong("lockTargetDuration", totalDurationMs)
                 .putLong("uptimeAtLock", android.os.SystemClock.elapsedRealtime())
                 .putBoolean("wasDeviceRestarted", false)
                 .putString("current_session_source", "schedule_catchup:" + schedule.getName())
                 .commit();

            // Start overlay
            OverlayLockService.showOverlay(context);

            // Launch lock screen via LockScreenService (respects background launch rules)
            Intent svc = new Intent(context, LockScreenService.class);
            svc.putExtra(LockScreenService.EXTRA_SCHEDULE_NAME, schedule.getName());
            svc.putExtra(LockScreenService.EXTRA_SCHEDULE_ID, schedule.getId());
            svc.putExtra(LockScreenService.EXTRA_DURATION_MINUTES,
                         (int) (remainingMs / 60_000L));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start catch-up session for " + schedule.getName(), e);
        }
    }

    private void scheduleMainTrigger(ScheduleModel schedule, Calendar triggerTime) {
        try {
            Intent intent = new Intent(context, ScheduleTriggerReceiver.class);
            intent.putExtra(ScheduleTriggerReceiver.EXTRA_SCHEDULE_ID, schedule.getId());
            intent.putExtra(ScheduleTriggerReceiver.EXTRA_SCHEDULE_NAME, schedule.getName());
            intent.putExtra(ScheduleTriggerReceiver.EXTRA_DURATION_MINUTES,
                            schedule.getFocusDurationMinutes());

            PendingIntent pi = PendingIntent.getBroadcast(context, schedule.getId(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            setAlarm(triggerTime.getTimeInMillis(), pi);
            Log.d(TAG, "Scheduled " + schedule.getName() + " for "
                    + String.format("%02d:%02d on %s",
                        triggerTime.get(Calendar.HOUR_OF_DAY),
                        triggerTime.get(Calendar.MINUTE),
                        formatDate(triggerTime)));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException scheduling main trigger", e);
        }
    }

    private void schedulePreNotification(ScheduleModel schedule, Calendar triggerTime) {
        try {
            Calendar preTime = (Calendar) triggerTime.clone();
            preTime.add(Calendar.MINUTE, -schedule.getPreNotifyMinutes());
            if (preTime.before(Calendar.getInstance())) return;

            Intent intent = new Intent(context, PreNotificationReceiver.class);
            intent.putExtra("schedule_id", schedule.getId());
            intent.putExtra("schedule_name", schedule.getName());
            intent.putExtra("duration_minutes", schedule.getFocusDurationMinutes());
            intent.putExtra("pre_notify_minutes", schedule.getPreNotifyMinutes());

            PendingIntent pi = PendingIntent.getBroadcast(context, -schedule.getId(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            setAlarm(preTime.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException scheduling pre-notification", e);
        }
    }

    private void setAlarm(long triggerAtMillis, PendingIntent pi) {
        if (AlarmPermissionManager.canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            Log.w(TAG, "Exact alarm permission not granted, using windowed alarm");
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, 10 * 60_000L, pi);
        }
    }

    private Calendar getNextTriggerTime(ScheduleModel schedule) {
        Calendar now         = Calendar.getInstance();
        Calendar triggerTime = Calendar.getInstance();
        triggerTime.set(Calendar.HOUR_OF_DAY, schedule.getStartHour());
        triggerTime.set(Calendar.MINUTE, schedule.getStartMinute());
        triggerTime.set(Calendar.SECOND, 0);
        triggerTime.set(Calendar.MILLISECOND, 0);

        switch (schedule.getRepeatType()) {
            case ONCE:
                return triggerTime.before(now) ? null : triggerTime;
            case DAILY:
                if (triggerTime.before(now)) triggerTime.add(Calendar.DAY_OF_YEAR, 1);
                return triggerTime;
            case WEEKLY:
                return getNextWeeklyTriggerTime(schedule, now, triggerTime);
            default:
                return null;
        }
    }

    private Calendar getNextWeeklyTriggerTime(ScheduleModel schedule,
                                              Calendar now,
                                              Calendar triggerTime) {
        java.util.Set<Integer> days = schedule.getRepeatDays();
        if (days.isEmpty()) return null;

        if (days.contains(now.get(Calendar.DAY_OF_WEEK)) && triggerTime.after(now))
            return triggerTime;

        Calendar next = (Calendar) triggerTime.clone();
        for (int d = 1; d <= 7; d++) {
            next.add(Calendar.DAY_OF_YEAR, 1);
            if (days.contains(next.get(Calendar.DAY_OF_WEEK))) return next;
        }
        return null;
    }

    private String formatDate(Calendar cal) {
        return String.format("%d/%d/%d",
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.YEAR));
    }
}
