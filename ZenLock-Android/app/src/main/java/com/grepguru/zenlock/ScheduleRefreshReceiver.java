package com.grepguru.zenlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.grepguru.zenlock.utils.ManualStartDelayScheduler;
import com.grepguru.zenlock.utils.ScheduleActivator;

/**
 * ScheduleRefreshReceiver - Re-registers all alarms after events that silently
 * wipe them: app updates, exact-alarm permission changes, time/timezone changes.
 */
public class ScheduleRefreshReceiver extends BroadcastReceiver {

    private static final String TAG = "ScheduleRefreshReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        Log.d(TAG, "Re-registering schedules after: " + action);

        try {
            new ScheduleActivator(context).rescheduleAllSchedules();
            ManualStartDelayScheduler.reschedulePendingSession(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to re-register schedules", e);
        }
    }
}
