package com.grepguru.zenlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.WindowManager;
import androidx.annotation.Nullable;

/**
 * OverlayLockService — draws a full-screen opaque overlay while a lock session is active.
 *
 * Key reliability improvements over the original:
 *  - START_STICKY so the OS restarts it if killed.
 *  - Holds a PARTIAL_WAKE_LOCK so it survives aggressive doze/battery optimisation.
 *    (PARTIAL_WAKE_LOCK keeps the CPU running; it does NOT prevent the screen from turning
 *     off, so there is no battery drain from screen-on while idle.)
 *  - showOverlay() is idempotent — safe to call repeatedly from AppBlockerService.
 *  - hideOverlay() is the single stop-point; only called from LockScreenActivity.finishLockScreen().
 */
public class OverlayLockService extends Service {

    private static final String CHANNEL_ID  = "zenlock_overlay_lock";
    private static final int    NOTIF_ID    = 2;
    private static final String EXTRA_HIDE  = "hide_overlay";

    private LockOverlayView overlayView;
    private WindowManager   windowManager;
    private PowerManager.WakeLock wakeLock;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Acquire partial wake lock to keep the service alive during Doze windows.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenLock:OverlayLock");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(); // released in onDestroy / hideOverlay
            }
        }

        // Add overlay view to window — covers everything, receives no touch input
        // so the user still sees real windows underneath (they just can't interact until
        // AppBlockerService pushes them back to LockScreenActivity).
        overlayView = new LockOverlayView(this);
        windowManager.addView(overlayView, overlayView.getLayoutParams());

        startForeground(NOTIF_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_HIDE, false)) {
            // hideOverlay() was called — stop the service cleanly.
            stopSelf();
            return START_NOT_STICKY;
        }
        // For all other starts (including system restarts after kill): keep overlay up.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -----------------------------------------------------------------------
    // Static helpers — called from anywhere
    // -----------------------------------------------------------------------

    /**
     * Show (or keep showing) the overlay. Idempotent — safe to call on every
     * accessibility event without worrying about duplicate views.
     */
    public static void showOverlay(Context context) {
        Intent intent = new Intent(context, OverlayLockService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Remove the overlay. Called only after the lock session has ended cleanly.
     */
    public static void hideOverlay(Context context) {
        Intent intent = new Intent(context, OverlayLockService.class);
        intent.putExtra(EXTRA_HIDE, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ZenLock Active",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZenLock is active")
            .setContentText("Focus session in progress.")
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .build();
    }
}
