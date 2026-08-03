package com.grepguru.zenlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.grepguru.zenlock.utils.ManualStartDelayScheduler;
import com.grepguru.zenlock.utils.ScheduleActivator;

/**
 * BootReceiver — handles device restart.
 *
 * Lock-reliability fix: if a lock session was active when the device restarted,
 * we ALWAYS re-engage the lock provided the scheduled end-time has not yet passed.
 * The original "auto_restart" preference toggle has been removed from this gate —
 * once a scheduled session starts it must run to completion regardless of restarts.
 *
 * The auto_restart pref is kept in SharedPreferences for backward compatibility
 * (Settings UI may still write it) but it no longer controls boot behaviour.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Log.d(TAG, "Boot completed — rescheduling sessions");

        SharedPreferences prefs = context.getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE);
        boolean isLocked   = prefs.getBoolean("isLocked", false);
        long    lockEndTime = prefs.getLong("lockEndTime", 0);
        long    currentTime = System.currentTimeMillis();

        // --- Re-engage lock if the session is still within its time window ---
        if (isLocked && lockEndTime > currentTime) {
            Log.d(TAG, "Active lock session found after boot — re-engaging overlay and lock screen");

            // Clear the restarted flag; the session continues normally
            prefs.edit()
                 .putBoolean("wasDeviceRestarted", false)
                 .putLong("uptimeAtLock", android.os.SystemClock.elapsedRealtime())
                 .apply();

            // Restart overlay service
            Intent overlayIntent = new Intent(context, OverlayLockService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(overlayIntent);
            } else {
                context.startService(overlayIntent);
            }

            // Launch lock screen
            Intent lockIntent = new Intent(context, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(lockIntent);

        } else if (isLocked) {
            // Session expired while device was off — clean up
            Log.d(TAG, "Lock session expired during restart — clearing state");
            prefs.edit()
                 .putBoolean("isLocked", false)
                 .remove("lockEndTime")
                 .remove("uptimeAtLock")
                 .putBoolean("wasDeviceRestarted", false)
                 .apply();
        }

        // Always reschedule alarms for future sessions
        rescheduleAllSchedules(context);
        ManualStartDelayScheduler.reschedulePendingSession(context);
    }

    private void rescheduleAllSchedules(Context context) {
        try {
            new ScheduleActivator(context).rescheduleAllSchedules();
            Log.d(TAG, "All focus schedules rescheduled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to reschedule schedules after boot", e);
        }
    }
}
