package com.grepguru.zenlock;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.app.AlertDialog;
import androidx.core.app.NotificationCompat;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.grepguru.zenlock.model.*;
import com.grepguru.zenlock.ui.adapter.*;
import com.grepguru.zenlock.ui.timer.TimerType;
import com.grepguru.zenlock.ui.timer.TimerFactory;
import com.grepguru.zenlock.utils.AppUtils;
import com.grepguru.zenlock.utils.AnalyticsManager;
import com.grepguru.zenlock.utils.EnhancedUnlockManager;
import com.grepguru.zenlock.utils.KeyguardUtils;
import com.grepguru.zenlock.utils.WhitelistManager;
import com.grepguru.zenlock.VibrationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class LockScreenActivity extends AppCompatActivity {

    private static volatile boolean isLockScreenActive = false;
    private EditText pinInput;
    private SharedPreferences preferences;
    private boolean isLaunchingWhitelistedApp = false;
    private AnalyticsManager analyticsManager;
    private boolean isExpanded = false;
    private EnhancedUnlockManager unlockManager;
    private android.os.CountDownTimer countDownTimer;
    private android.os.Handler targetHandler;
    private Runnable targetRunnable;
    private boolean wasManuallyUnlocked = false;
    private android.os.Handler autoHideHandler;
    private Runnable autoHideRunnable;
    
    // Timer system
    private TimerType currentTimer;
    private View timerContainer;
    
    // Persistent notification
    private static final String CHANNEL_ID = "zenlock_persistent_lock";
    private static final int NOTIFICATION_ID = 1001;
    private NotificationManager notificationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            // Dismiss keyguard if needed (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.KeyguardManager keyguardManager = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguardManager != null) {
                    keyguardManager.requestDismissKeyguard(this, null);
                }
            }
        } else if (getWindow() != null) {
            // Only use non-deprecated flag for older versions
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);
        // Prevent multiple instances
        if (isLockScreenActive) {
            Log.d("LockScreenActivity", "Lock screen already active, finishing duplicate instance");
            finish();
            return;
        }
        isLockScreenActive = true;

        // Dismiss blocker notification if it was used to launch us (MIUI fallback)
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(9999); // BLOCKER_NOTIFICATION_ID from LockScreenLauncher
        }

        // Start overlay lock service
        Intent overlayIntent = new Intent(this, OverlayLockService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent);
        } else {
            startService(overlayIntent);
        }
        
        preferences = getSharedPreferences("FocusLockPrefs", Context.MODE_PRIVATE);
        analyticsManager = new AnalyticsManager(this);
        unlockManager = new EnhancedUnlockManager(this);

        // Detect reboot using system uptime
        long storedUptime = preferences.getLong("uptimeAtLock", -1);
        long currentUptime = android.os.SystemClock.elapsedRealtime();

        // Check if the device restarted using uptime OR if the wasDeviceRestarted flag is set
        boolean wasRestarted = preferences.getBoolean("wasDeviceRestarted", false);
        boolean autoRestartPref = preferences.getBoolean("auto_restart", false);

        // If device was restarted (stored uptime > current uptime OR wasDeviceRestarted flag is set)
        if (storedUptime > currentUptime || wasRestarted) {
            if (!autoRestartPref) {
                // User disabled auto-restart, clear the lock
                SharedPreferences.Editor editor = preferences.edit();
                editor.putBoolean("isLocked", false);
                editor.remove("lockEndTime");
                editor.putBoolean("wasDeviceRestarted", false);
                editor.apply();

                if (analyticsManager.hasActiveSession()) {
                    analyticsManager.endSession(false);
                }

                finishLockScreen();
                return;
            } else {
                // Auto-restart is enabled, clear the restart flag and continue with lock
                preferences.edit().putBoolean("wasDeviceRestarted", false).apply();
                // Continue — lock remains active and will be enforced below
            }
        }

        // Normal behaviour if the device is not restarted
        // Retrieve saved lock end time
        long lockEndTime = preferences.getLong("lockEndTime", 0);
        long currentTime = System.currentTimeMillis();

        // If no active lock or timer already expired or device restarted, exit lock screen
        if (!preferences.getBoolean("isLocked", false) || lockEndTime == 0 || currentTime >= lockEndTime) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("isLocked", false);
            editor.remove("lockEndTime");
            editor.apply();

            // End analytics session if active
            if (analyticsManager.hasActiveSession()) {
                analyticsManager.endSession(false); // Interrupted due to expired timer
            }

            // Return to MainActivity
            isLockScreenActive = false; // Reset flag before finishing
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finishLockScreen();
            return;
        }

        // -----------------------------------------------------------
        // Setting up UI
        setContentView(R.layout.activity_lock_screen);

        // Initializing UI Components
        pinInput = findViewById(R.id.pinInput);
        Button unlockButton = findViewById(R.id.unlockButton);
        TextView timerCountdown = findViewById(R.id.timerCountdown);
        //  TextView lockMessage = findViewById(R.id.lockscreenMessage);
        Button unlockPromptButton = findViewById(R.id.unlockPromptButton);
        ImageView unlockArrow = findViewById(R.id.unlockArrow);
        LinearLayout unlockInputsContainer = findViewById(R.id.unlockInputsContainer);
        LinearLayout expandButtonContainer = findViewById(R.id.expandButtonContainer);
        ImageView pinVisibilityToggle = findViewById(R.id.pinVisibilityToggle);
        
        // Initialize extend lock functionality
        Button extendLockButton = findViewById(R.id.extendLockButton);
        LinearLayout unlockExtendButtonContainer = findViewById(R.id.unlockExtendButtonContainer);
        
        // Start countdown timer with remaining time
        long remainingTimeMillis = lockEndTime - currentTime;
        
        // Determine target duration to preserve progress across reinstates
        long targetDuration = preferences.getLong("lockTargetDuration", 0);
        if (targetDuration <= 0) {
            long lockStartTime = preferences.getLong("lockStartTime", 0);
            if (lockStartTime > 0 && lockEndTime > lockStartTime) {
                targetDuration = lockEndTime - lockStartTime;
            } else {
                // Fallback to current remaining time (old installs)
                targetDuration = remainingTimeMillis;
            }
        }
        
        // Initialize timer system with total target duration
        initializeTimer(targetDuration);
        if (remainingTimeMillis <= 0) {
            isLockScreenActive = false; // Reset flag before finishing
            finishLockScreen();
            return;
        }

        // Single RecyclerView for all apps (default + additional)
        RecyclerView appsRecycler = findViewById(R.id.defaultAppsRecycler);
        LinearLayout noAppsContainer = findViewById(R.id.noAppsContainer);
        android.widget.ImageView expandAppsButton = findViewById(R.id.expandAppsButton);

        // Use GridLayoutManager for better organization - 3 apps per row
        androidx.recyclerview.widget.GridLayoutManager layoutManager = new androidx.recyclerview.widget.GridLayoutManager(this, 3);
        appsRecycler.setLayoutManager(layoutManager);

        SharedPreferences preferences = getSharedPreferences("FocusLockPrefs", MODE_PRIVATE);
        Set<String> whitelistedApps = preferences.getStringSet("whitelisted_apps", new HashSet<>());

        // Separate default apps and additional apps
        Set<String> defaultApps = AppUtils.getMainDefaultApps(this);
        List<String> additionalApps = new ArrayList<>();
        
        for (String packageName : whitelistedApps) {
            if (!defaultApps.contains(packageName)) {
                additionalApps.add(packageName);
            }
        }

        // Load default apps
        List<AppModel> defaultAppModels = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String packageName : defaultApps) {
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                String appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)).toString();
                defaultAppModels.add(new AppModel(packageName, appName, true, icon));
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Load additional apps
        List<AppModel> additionalAppModels = new ArrayList<>();
        for (String packageName : additionalApps) {
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                String appName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)).toString();
                additionalAppModels.add(new AppModel(packageName, appName, false, icon));
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Debug logging
        Log.d("LockScreen", "Default apps count: " + defaultAppModels.size());
        Log.d("LockScreen", "Additional apps count: " + additionalAppModels.size());
        Log.d("LockScreen", "Whitelisted apps: " + whitelistedApps.toString());

        // Create combined list starting with default apps only
        List<AppModel> currentAppModels = new ArrayList<>(defaultAppModels);

        // Create single adapter for the RecyclerView
        AllowedAppsAdapter appsAdapter = new AllowedAppsAdapter(this, currentAppModels);
        appsAdapter.setOnAppLaunchListener(() -> {
            isLaunchingWhitelistedApp = true;
        });

        appsRecycler.setAdapter(appsAdapter);

        // -----------------------------------------------------------
        // Setting up Apps Section

        // Always show the expand button container
        expandButtonContainer.setVisibility(View.VISIBLE);

        // Set up PIN visibility toggle
        pinVisibilityToggle.setOnClickListener(v -> {
            if (pinInput.getInputType() == (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                // Show PIN
                pinInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                pinVisibilityToggle.setImageResource(R.drawable.ic_eye_off);
            } else {
                // Hide PIN
                pinInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                pinVisibilityToggle.setImageResource(R.drawable.ic_eye);
            }
        });

        // Set up expand button click listener
        expandAppsButton.setOnClickListener(v -> {
            if (!isExpanded) {
                // Expand: Show all apps (default + additional)
                if (additionalAppModels.isEmpty()) {
                    // Only default apps exist, just show them
                    currentAppModels.clear();
                    currentAppModels.addAll(defaultAppModels);
                } else {
                    // Show both default and additional apps
                    currentAppModels.clear();
                    currentAppModels.addAll(defaultAppModels);
                    currentAppModels.addAll(additionalAppModels);
                }
                appsAdapter.notifyDataSetChanged();
                
                // Check if there are any apps to show
                if (currentAppModels.isEmpty()) {
                    // Show "No Apps Allowed" message
                    noAppsContainer.setVisibility(View.VISIBLE);
                    noAppsContainer.setAlpha(0f);
                    noAppsContainer.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start();
                    appsRecycler.setVisibility(View.GONE);
                } else {
                    // Show the RecyclerView with smooth animation
                    appsRecycler.setVisibility(View.VISIBLE);
                    appsRecycler.setAlpha(0f);
                    appsRecycler.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start();
                    noAppsContainer.setVisibility(View.GONE);
                }
                
                // Animate arrow to bottom of main content container (above the apps)
                // Get the parent apps container and calculate the exact position
                View parentAppsContainer = findViewById(R.id.parentAppsContainer);
                parentAppsContainer.post(() -> {
                    int[] parentContainerLocation = new int[2];
                    parentAppsContainer.getLocationInWindow(parentContainerLocation);
                    int parentContainerTop = parentContainerLocation[1];
                    
                    int[] arrowLocation = new int[2];
                    expandButtonContainer.getLocationInWindow(arrowLocation);
                    int arrowCurrentY = arrowLocation[1];
                    
                    // Move arrow to just above the parent container (with 20dp buffer)
                    int bufferPixels = (int) (20 * getResources().getDisplayMetrics().density);
                    int targetY = parentContainerTop - bufferPixels;
                    int distanceToMove = arrowCurrentY - targetY;
                    
                    expandButtonContainer.animate()
                        .translationY(-distanceToMove)
                        .setDuration(300)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                });
                
                // Scroll to show all apps from the beginning after a short delay
                appsRecycler.postDelayed(() -> {
                    layoutManager.scrollToPosition(0);
                }, 100);
            } else {
                // Collapse: Hide all apps
                currentAppModels.clear();
                appsAdapter.notifyDataSetChanged();
                
                // Hide both the RecyclerView and noAppsContainer with smooth animation
                appsRecycler.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> appsRecycler.setVisibility(View.GONE))
                    .start();
                
                noAppsContainer.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> noAppsContainer.setVisibility(View.GONE))
                    .start();
                
                // Animate arrow back to bottom position
                expandButtonContainer.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
                    .start();
            }

            isExpanded = !isExpanded;

            // Rotate the expand icon (0° for expanded pointing down, 180° for collapsed pointing up)
            expandAppsButton.animate()
                .rotation(isExpanded ? 0 : 180)
                .setDuration(300)
                .start();
        });


        // -----------------------------------------------------------
        // Setting up Click Listeners

        // Initially Hide PIN Input and Keep Apps Visible
        unlockInputsContainer.setVisibility(View.GONE);
        // appsSection.setVisibility(View.VISIBLE); // This line is removed

        // Set up enhanced unlock manager
        unlockManager.setOnUnlockListener(new EnhancedUnlockManager.OnUnlockListener() {
            @Override
            public void onUnlockSuccess(UnlockMethod method) {
                handleUnlockSuccess(method);
            }
            
            @Override
            public void onUnlockCancelled() {
                // User cancelled unlock, stay in lock screen
                Toast.makeText(LockScreenActivity.this, "Unlock cancelled", Toast.LENGTH_SHORT).show();
            }
        });

        unlockPromptButton.setOnClickListener(v -> {
            // Show enhanced unlock dialog
            unlockManager.showUnlockDialog();
        });

        // unlockArrow now shows a popup dialog with Unlock and Extend options
        unlockArrow.setOnClickListener(v -> showUnlockExtendPopup());

        // mainContentContainer removed from layout — no tap handler needed

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Toast.makeText(LockScreenActivity.this, "Cannot exit Focus Mode!", Toast.LENGTH_SHORT).show();
            }
        });

        // Set up motivational quotes
        setupMotivationalQuotes();
        setupTargetCountdown();

        // Start Countdown Timer
        startCountdownTimer(targetDuration, remainingTimeMillis);
        
        // Create persistent notification if enabled
        createPersistentNotificationIfEnabled();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isLaunchingWhitelistedApp) {
            isLaunchingWhitelistedApp = false;
            return;
        }
        if (!isFinishing() && !isDestroyed() && isScreenOn()) {
            com.grepguru.zenlock.OverlayLockService.showOverlay(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always bring lock screen to front if not already
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.moveTaskToFront(getTaskId(), 0);
        }

        // Ensure persistent notification is always visible
        createPersistentNotificationIfEnabled();

        // If lock screen lost focus, restart it instantly
        if (!isLockScreenActive) {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // Remove automatic restart on focus change to prevent loops
        // The onPause/onStop methods will handle legitimate cases where user tries to leave
    }

    /**
     * Show unlock button with auto-hide after 5 seconds
     */
    private void showUnlockButton() {
        Button unlockPromptButton = findViewById(R.id.unlockPromptButton);
        ImageView unlockArrow = findViewById(R.id.unlockArrow);
        LinearLayout unlockExtendButtonContainer = findViewById(R.id.unlockExtendButtonContainer);

        // Cancel any existing auto-hide timer
        if (autoHideHandler != null && autoHideRunnable != null) {
            autoHideHandler.removeCallbacks(autoHideRunnable);
        }

        // Show unlock and extend button container with smooth slide-up animation
        unlockExtendButtonContainer.setVisibility(View.VISIBLE);
        unlockExtendButtonContainer.setTranslationY(50f); // Start slightly below
        unlockExtendButtonContainer.setAlpha(0f);
        unlockExtendButtonContainer.animate()
            .translationY(0f) // Slide up to final position
            .alpha(1f) // Fade in
            .setDuration(300)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();

        // Hide arrow with fade out
        unlockArrow.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction(() -> unlockArrow.setVisibility(View.GONE))
            .start();

        // Set up auto-hide after 5 seconds
        autoHideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        autoHideRunnable = () -> {
            hideUnlockButton();
        };
        autoHideHandler.postDelayed(autoHideRunnable, 5000); // 5 seconds
    }

    /**
     * Hide unlock button and show arrow
     */
    private void hideUnlockButton() {
        Button unlockPromptButton = findViewById(R.id.unlockPromptButton);
        ImageView unlockArrow = findViewById(R.id.unlockArrow);
        LinearLayout unlockExtendButtonContainer = findViewById(R.id.unlockExtendButtonContainer);

        // Hide unlock and extend button container with smooth slide-down animation
        unlockExtendButtonContainer.animate()
            .translationY(50f) // Slide down slightly
            .alpha(0f) // Fade out
            .setDuration(300)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> {
                unlockExtendButtonContainer.setVisibility(View.GONE);
                unlockExtendButtonContainer.setTranslationY(0f); // Reset position for next time
            })
            .start();

        // Show arrow with fade in
        unlockArrow.setVisibility(View.VISIBLE);
        unlockArrow.setAlpha(0f);
        unlockArrow.animate()
            .alpha(0.6f)
            .setDuration(300)
            .start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Always reset the flag when activity is destroyed
        isLockScreenActive = false;

        // Stop overlay lock service to prevent resource leak
        stopService(new Intent(this, OverlayLockService.class));

        // Cancel countdown timer to prevent memory leaks
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        if (targetHandler != null && targetRunnable != null) {
            targetHandler.removeCallbacks(targetRunnable);
        }

        // Cancel auto-hide timer
        if (autoHideHandler != null && autoHideRunnable != null) {
            autoHideHandler.removeCallbacks(autoHideRunnable);
        }

        // Cleanup timer
        if (currentTimer != null) {
            currentTimer.cleanup();
        }

        // Cleanup unlock manager
        if (unlockManager != null) {
            unlockManager.cleanup();
        }
    }

    /**
     * Check if the device screen is currently on.
     * Used to prevent restarting LockScreenActivity when the screen turns off,
     * which would cause an infinite wake loop due to setTurnScreenOn(true).
     */
    private boolean isScreenOn() {
        try {
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isInteractive();
        } catch (Exception e) {
            return true; // Assume screen is on if check fails
        }
    }

    /**
     * Check if a whitelisted app is currently in the foreground.
     * This prevents LockScreenActivity from restarting when user is using allowed apps.
     */
    private boolean isWhitelistedAppInForeground() {
        try {
            // Use ActivityManager to get running processes
            android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
                if (runningProcesses != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                        if (processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            String foregroundPackage = processInfo.processName;

                            // Check if this is a whitelisted app
                            if (WhitelistManager.isAppWhitelisted(LockScreenActivity.this, foregroundPackage)) {
                                Log.d("LockScreenActivity", "Whitelisted app detected in foreground: " + foregroundPackage);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("LockScreenActivity", "Error checking foreground app", e);
        }
        return false;
    }



    private void handleUnlockSuccess(UnlockMethod method) {
        // Vibrate for feedback if enabled
        VibrationUtils.vibrate(this, 50);
        Toast.makeText(this, "Unlocked via " + method.getDisplayName(), Toast.LENGTH_SHORT).show();

        // Mark as manually unlocked to prevent completion toast
        wasManuallyUnlocked = true;

        // Cancel the countdown timer to prevent it from finishing
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        // End analytics session
        if (analyticsManager.hasActiveSession()) {
            analyticsManager.endSession(false); // Interrupted by manual unlock
        }

        // Reset lock state
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("isLocked", false); // Mark as unlocked
        editor.remove("lockEndTime");
        editor.apply();

        // Return to MainActivity
        isLockScreenActive = false; // Reset flag before finishing
        Intent intent = new Intent(LockScreenActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishLockScreen();
    }


    /**
     * Shows a popup dialog with Unlock and Extend buttons.
     * Back button or the close tick dismisses the dialog and returns to lock screen.
     */
    private void showUnlockExtendPopup() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        android.view.View dialogView = getLayoutInflater().inflate(android.R.layout.simple_list_item_1, null);

        // Build dialog manually for full control
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 32);
        layout.setBackgroundColor(android.graphics.Color.parseColor("#1A2235"));

        // Title
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Options");
        title.setTextSize(18f);
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        // Unlock button
        android.widget.Button btnUnlock = new android.widget.Button(this);
        btnUnlock.setText("Unlock");
        btnUnlock.setTextSize(16f);
        btnUnlock.setTextColor(android.graphics.Color.WHITE);
        btnUnlock.setBackgroundResource(R.drawable.glass_button_background);
        android.widget.LinearLayout.LayoutParams btnParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 0, 0, 16);
        btnUnlock.setLayoutParams(btnParams);
        layout.addView(btnUnlock);

        // Extend button
        android.widget.Button btnExtend = new android.widget.Button(this);
        btnExtend.setText("Extend");
        btnExtend.setTextSize(16f);
        btnExtend.setTextColor(android.graphics.Color.WHITE);
        btnExtend.setBackgroundResource(R.drawable.glass_button_background);
        android.widget.LinearLayout.LayoutParams btnParams2 =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams2.setMargins(0, 0, 0, 24);
        btnExtend.setLayoutParams(btnParams2);
        layout.addView(btnExtend);

        // Close / back tick button
        android.widget.Button btnClose = new android.widget.Button(this);
        btnClose.setText("✓  Back to Lock Screen");
        btnClose.setTextSize(14f);
        btnClose.setTextColor(android.graphics.Color.parseColor("#8899BB"));
        btnClose.setBackground(null);
        layout.addView(btnClose);

        builder.setView(layout);
        builder.setCancelable(true); // back button dismisses

        android.app.AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnUnlock.setOnClickListener(v -> {
            dialog.dismiss();
            unlockManager.showUnlockDialog();
        });

        btnExtend.setOnClickListener(v -> {
            dialog.dismiss();
            showExtendDialog();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void setupTargetCountdown() {
        android.view.View card         = findViewById(R.id.targetCountdownCard);
        TextView          labelView    = findViewById(R.id.targetLabel);
        TextView          countdownView = findViewById(R.id.targetCountdown);

        if (card == null || labelView == null || countdownView == null) return;

        JSONArray targets = loadTargetsJson();
        if (targets == null || targets.length() == 0) {
            card.setVisibility(View.GONE);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
        JSONObject nextTarget = null;
        long nextMillis = Long.MAX_VALUE;
        long now = System.currentTimeMillis();

        for (int i = 0; i < targets.length(); i++) {
            try {
                JSONObject t = targets.getJSONObject(i);
                Date date = sdf.parse(t.getString("datetime"));
                if (date == null) continue;
                long millis = date.getTime();
                if (millis > now && millis < nextMillis) {
                    nextMillis = millis;
                    nextTarget = t;
                }
            } catch (Exception e) {
                Log.w("LockScreen", "Skipping malformed target", e);
            }
        }

        if (nextTarget == null) {
            card.setVisibility(View.GONE);
            return;
        }

        try { labelView.setText(nextTarget.getString("label")); }
        catch (Exception e) { labelView.setText("Target"); }
        card.setVisibility(View.VISIBLE);

        final long targetMillis = nextMillis;
        targetHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        targetRunnable = new Runnable() {
            @Override
            public void run() {
                long diff = targetMillis - System.currentTimeMillis();
                if (diff <= 0) {
                    countdownView.setText("Now!");
                    return;
                }
                long days    = diff / (1000 * 60 * 60 * 24);
                long hours   = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
                long minutes = (diff % (1000 * 60 * 60)) / (1000 * 60);
                long seconds = (diff % (1000 * 60)) / 1000;
                String display;
                if (days > 0) display = days + "d " + hours + "h " + minutes + "m " + seconds + "s";
                else if (hours > 0) display = hours + "h " + minutes + "m " + seconds + "s";
                else display = minutes + "m " + seconds + "s";
                countdownView.setText(display);
                targetHandler.postDelayed(this, 1000);
            }
        };
        targetHandler.post(targetRunnable);
    }

    private JSONArray loadTargetsJson() {
        try {
            int resId = getResources().getIdentifier("targets", "raw", getPackageName());
            if (resId == 0) return null;
            InputStream is = getResources().openRawResource(resId);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            return new JSONArray(new String(buffer, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e("LockScreen", "Failed to load targets.json", e);
            return null;
        }
    }

    private void setupMotivationalQuotes() {
        TextView lockscreenMessage = findViewById(R.id.lockscreenMessage);

        // Check if quotes are enabled
        if (!preferences.getBoolean("show_quotes", true)) {
            lockscreenMessage.setText("Stay focused, stay productive!");
            return;
        }

        // Motivational quotes array
        String[] quotes = {
            "තව දවස් ටිකයි නේ තියෙන්නේ වැඩ කරමු.",
            "කම්මැලිද? එහෙනම් නැගිටලා වැඩ කරපන්.",
            "වැඩ නොකරත් S3ත් නෑ. ගෙදර තමයි ඉන්න වෙන්නෙ.",
            "පුළුවන් තරම් පේපර්ම කරපන්.",
            "පොඩි කාලෙයිනේ YouTube පැත්තේ ගිහිල්ලා පිස්සු කෙලින්න එපා.",
            "අවශ්‍යම පාඩම් ටික බලාගනින් හොඳට.",
            "පේපර් ලියනකොට සිහියෙන් ලියපන්, සින්දු අහන්න එපා.",
            "යකෝ අනිත් යැවුන් යක්කු වගේ වැඩ කරන්නේ.",
            "ඔහොම ගියොත් Plan ඔලුවෙම තමයි තියන් ඉන්නේ වෙන්නේ.",
            "වැඩ කරපන් යකෝ, වැඩ කරපන්...",
            "Stay focused, stay productive!",
            "අවධානය කොතනද? උඹ එතන ඉන්න ඕන.",
            "අද දවස පිස්සුවක් කරමු.",
            "වෙන වැඩ කරන්න කාලයක් නෑ හරිද?",
            "මාසයක් නෑනේ, මේ ටික කරමු.",
            "බලපන් ඊට පස්සේ අපිට කොච්චර වැඩ තියෙද කියල කරන්න, දැන් ඔය ටික කරපන්."
        };

        // Select a random quote
        int randomIndex = (int) (Math.random() * quotes.length);
        lockscreenMessage.setText(quotes[randomIndex]);
    }

    /**
     * Initialize the timer system based on user preferences
     */
    private void initializeTimer(long totalTimeMs) {
        // Get timer style from preferences
        String timerStyle = preferences.getString("timer_style", "digital");

        // Create timer instance
        currentTimer = TimerFactory.createTimer(this, timerStyle);

        // Get timer container and replace the default timer
        timerContainer = findViewById(R.id.timerContainer);
        if (timerContainer != null && timerContainer instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout frameLayout = (android.widget.FrameLayout) timerContainer;
            frameLayout.removeAllViews();
            frameLayout.addView(currentTimer.getTimerView());

            // Adjust container size based on timer type
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params = (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) frameLayout.getLayoutParams();
            if ("circular".equals(timerStyle)) {
                // Circular timer needs more space
                params.width = (int) (350 * getResources().getDisplayMetrics().density); // Convert dp to px
                params.height = (int) (350 * getResources().getDisplayMetrics().density); // Convert dp to px
            } else {
                // Digital timer needs even less space to reduce gap with quotes
                params.width = (int) (300 * getResources().getDisplayMetrics().density); // Convert dp to px
                params.height = (int) (100 * getResources().getDisplayMetrics().density); // Reduced from 120dp to 100dp
            }
            frameLayout.setLayoutParams(params);
        }

        // Initialize the timer using total duration so progress reflects overall session
        currentTimer.initialize(totalTimeMs);

        // Show quotes for all timer styles
        TextView lockscreenMessage = findViewById(R.id.lockscreenMessage);
        if (lockscreenMessage != null) {
            boolean quotesEnabled = preferences.getBoolean("show_quotes", true);
            lockscreenMessage.setVisibility(quotesEnabled ? View.VISIBLE : View.GONE);
        }
    }

    private void startCountdownTimer(long totalTimeMs, long remainingTimeMillis) {
        countDownTimer = new android.os.CountDownTimer(remainingTimeMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (currentTimer != null) {
                    currentTimer.updateTimer(totalTimeMs, millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                long persistedEndTime = preferences.getLong("lockEndTime", 0);
                long currentTime = System.currentTimeMillis();

                // If the session was extended elsewhere, this timer instance is stale.
                // Restart from the persisted state instead of ending the focus session early.
                if (preferences.getBoolean("isLocked", false) && persistedEndTime > currentTime) {
                    long remainingTimeMillis = persistedEndTime - currentTime;
                    long persistedTargetDuration = preferences.getLong("lockTargetDuration", 0);
                    if (persistedTargetDuration <= 0) {
                        long persistedStartTime = preferences.getLong("lockStartTime", 0);
                        if (persistedStartTime > 0 && persistedEndTime > persistedStartTime) {
                            persistedTargetDuration = persistedEndTime - persistedStartTime;
                        } else {
                            persistedTargetDuration = remainingTimeMillis;
                        }
                    }

                    updateTimerDisplay(remainingTimeMillis);
                    startCountdownTimer(persistedTargetDuration, remainingTimeMillis);
                    updatePersistentNotification();
                    return;
                }

                // Only show completion toast if not manually unlocked
                if (!wasManuallyUnlocked) {
                    // End analytics session
                    if (analyticsManager.hasActiveSession()) {
                        analyticsManager.endSession(true); // Completed successfully
                    }

                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean("isLocked", false);
                    editor.remove("lockEndTime"); // Remove saved lock end time
                    editor.apply();

                    // Vibrate on timer completion
                    VibrationUtils.vibrate(LockScreenActivity.this, 500); // 500ms vibration

                    Toast.makeText(LockScreenActivity.this, "Time's up! Focus Mode Ended.", Toast.LENGTH_SHORT).show();
                }
                finishLockScreen();
            }
        };
        countDownTimer.start();
    }

    private void finishLockScreen() {
        com.grepguru.zenlock.OverlayLockService.hideOverlay(this);
        // Call this when lock ends (unlock, timer expires, etc.)
        isLockScreenActive = false;
        
        // Remove persistent notification
        removePersistentNotification();
        
        // Clear any pre-notifications for this session
        clearPreNotificationsForCurrentSession();
        
        // Stop overlay lock service
        stopService(new Intent(this, OverlayLockService.class));
                finish();
            }

    /**
     * Show dialog to select extension time with consistent dark theme design
     */
    private void showExtendDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_extend_time_picker, null);
        NumberPicker hoursPicker = dialogView.findViewById(R.id.hoursPicker);
        NumberPicker minutesPicker = dialogView.findViewById(R.id.minutesPicker);
        Button cancelButton = dialogView.findViewById(R.id.cancelButton);
        Button extendButton = dialogView.findViewById(R.id.extendButton);
        
        // Configure hours picker (0-5 hours for extension)
        hoursPicker.setMinValue(0);
        hoursPicker.setMaxValue(5);
        hoursPicker.setValue(0);
        
        // Configure minutes picker with predefined values (same as schedule creation)
        minutesPicker.setMinValue(0);
        minutesPicker.setMaxValue(8);
        String[] minuteValues = {"0", "1", "5", "10", "15", "20", "30", "40", "50"};
        minutesPicker.setDisplayedValues(minuteValues);
        minutesPicker.setValue(0);
        
        // Create custom dialog with dark theme
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create();
        
        // Set custom background to match app theme
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        // Set up button listeners
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        
        extendButton.setOnClickListener(v -> {
            int hours = hoursPicker.getValue();
            int minutes = Integer.parseInt(minuteValues[minutesPicker.getValue()]);
            long extraMillis = (hours * 3600 + minutes * 60) * 1000;
            
            if (extraMillis > 0) {
                extendLockDuration(extraMillis);
                String timeText = "";
                if (hours > 0) timeText += hours + "h ";
                if (minutes > 0) timeText += minutes + "m";
                Toast.makeText(this, "Focus session extended by " + timeText, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                Toast.makeText(this, "Please select a valid extension time", Toast.LENGTH_SHORT).show();
            }
        });
        
        dialog.show();
    }

    /**
     * Extend the current lock duration
     */
    private void extendLockDuration(long extraMillis) {
        long lockEndTime = preferences.getLong("lockEndTime", 0);
        long newEndTime = lockEndTime + extraMillis;
        preferences.edit().putLong("lockEndTime", newEndTime).apply();
        
        long lockStartTime = preferences.getLong("lockStartTime", 0);
        long targetDuration = preferences.getLong("lockTargetDuration", 0) + extraMillis;
        preferences.edit().putLong("lockTargetDuration", targetDuration).apply();
        
        long currentTime = System.currentTimeMillis();
        long remainingTimeMillis = newEndTime - currentTime;

        // Restart timer with new duration
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        startCountdownTimer(targetDuration, remainingTimeMillis);
        
        // Update the timer display immediately
        updateTimerDisplay(remainingTimeMillis);
        
        // Update persistent notification with new end time
        updatePersistentNotification();
    }

    /**
     * Update timer display with new remaining time
     */
    private void updateTimerDisplay(long remainingTimeMillis) {
        TextView timerCountdown = findViewById(R.id.timerCountdown);
        if (timerCountdown != null) {
            long hours = remainingTimeMillis / (1000 * 60 * 60);
            long minutes = (remainingTimeMillis % (1000 * 60 * 60)) / (1000 * 60);
            long seconds = (remainingTimeMillis % (1000 * 60)) / 1000;
            
            String timeText;
            if (hours > 0) {
                timeText = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                timeText = String.format("%02d:%02d", minutes, seconds);
            }
            timerCountdown.setText(timeText);
        }
    }
    
    /**
     * Create persistent notification if enabled in settings
     */
    private void createPersistentNotificationIfEnabled() {
        boolean persistentNotificationEnabled = preferences.getBoolean("persistent_notification", true);
        if (!persistentNotificationEnabled) {
            return;
        }
        
        try {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                Log.e("LockScreenActivity", "NotificationManager is null");
                return;
            }
            
            // Create notification channel for Android 8.0+
            createNotificationChannel();
            
            // Create and show notification
            Notification notification = createPersistentNotification();
            notificationManager.notify(NOTIFICATION_ID, notification);
            Log.d("LockScreenActivity", "Persistent notification created");
            
        } catch (Exception e) {
            Log.e("LockScreenActivity", "Failed to create persistent notification", e);
        }
    }
    
    /**
     * Create notification channel for Android 8.0+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ZenLock Focus Session",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Persistent notification when focus session is active");
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * Create persistent notification
     */
    private Notification createPersistentNotification() {
        // Create intent to open the app
        Intent intent = new Intent(this, LockScreenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            NOTIFICATION_ID, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Get end time for display
        long lockEndTime = preferences.getLong("lockEndTime", 0);
        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        String endTimeText = timeFormat.format(new java.util.Date(lockEndTime));
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("ZenLock Active")
            .setContentText("Ends at " + endTimeText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .build();
    }
    
    /**
     * Update persistent notification with current end time
     */
    private void updatePersistentNotification() {
        boolean persistentNotificationEnabled = preferences.getBoolean("persistent_notification", true);
        if (!persistentNotificationEnabled || notificationManager == null) {
            return;
        }
        
        try {
            Notification notification = createPersistentNotification();
            notificationManager.notify(NOTIFICATION_ID, notification);
            Log.d("LockScreenActivity", "Persistent notification updated");
        } catch (Exception e) {
            Log.e("LockScreenActivity", "Failed to update persistent notification", e);
        }
    }
    
    /**
     * Remove persistent notification
     */
    private void removePersistentNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
            Log.d("LockScreenActivity", "Persistent notification removed");
        }
    }
    
    /**
     * Clear pre-notifications for the current session
     */
    private void clearPreNotificationsForCurrentSession() {
        try {
            // Get current session source to identify which schedule triggered this session
            String sessionSource = preferences.getString("current_session_source", "");
            if (sessionSource.startsWith("schedule:")) {
                // Extract schedule ID from session source (format: "schedule:ScheduleName")
                // For now, we'll clear all pre-notifications since we don't store schedule ID in session source
                // This is a simple approach that clears all pre-notifications when any scheduled session ends
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    // Clear all pre-notifications (they use IDs 2000+)
                    // This is safe because pre-notifications should be cleared anyway when session starts
                    for (int i = 2000; i < 3000; i++) { // Clear a reasonable range of pre-notification IDs
                        notificationManager.cancel(i);
                    }
                    Log.d("LockScreenActivity", "Cleared all pre-notifications for scheduled session");
                }
            }
        } catch (Exception e) {
            Log.e("LockScreenActivity", "Failed to clear pre-notifications", e);
        }
    }


}
