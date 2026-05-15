package com.example.uxalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-schedules all enabled alarms after device reboot.
 * The actual scheduling logic lives in MainActivity.rescheduleAllAlarms().
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Launch MainActivity briefly to reschedule alarms
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.putExtra("reschedule_only", true);
            context.startActivity(launchIntent);
        }
    }
}
