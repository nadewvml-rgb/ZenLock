package com.grepguru.zenlock;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device Admin Receiver for ZenLock.
 *
 * Enables the app to appear under Settings → Security → Device Admin Apps,
 * which allows the user to prevent uninstallation while the admin is active.
 *
 * To activate: Settings → Security → Device Admin Apps → ZenLock → Enable.
 * To uninstall: deactivate from the same screen first.
 *
 * No UI or code in this app triggers activation — the user does it manually.
 */
public class ZenLockDeviceAdmin extends DeviceAdminReceiver {

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Warning shown to the user when they try to deactivate admin via system settings.
        return "Disabling Device Admin will allow ZenLock to be uninstalled. Continue?";
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        // Called when the user enables device admin. Nothing extra needed.
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // Called when device admin is disabled. Nothing extra needed.
    }
}
