package com.example.uxalarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "uxalarm_channel";
    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_ALARM_TYPE = "alarm_type";
    public static final String EXTRA_ALARM_SOUND = "alarm_sound";

    @Override
    public void onReceive(Context context, Intent intent) {
        String alarmId = intent.getStringExtra(EXTRA_ALARM_ID);
        String alarmType = intent.getStringExtra(EXTRA_ALARM_TYPE);
        String sound = intent.getStringExtra(EXTRA_ALARM_SOUND);

        if (alarmId == null) return;

        createNotificationChannel(context);

        // Wake the screen
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                "uxalarm:wakelock"
        );
        wakeLock.acquire(30000);

        // Vibrate
        vibrate(context);

        // Play alarm sound
        playSound(context, sound);

        // Launch the app with the alarm info
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(EXTRA_ALARM_ID, alarmId);
        launchIntent.putExtra(EXTRA_ALARM_TYPE, alarmType);
        context.startActivity(launchIntent);

        // Also show a notification as fallback
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, alarmId.hashCode(), launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "ALARM".equals(alarmType) ? "Alarm" : "Reminder";
        String text = "ALARM".equals(alarmType) ? "Time to wake up!" : "You have a reminder.";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true);

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(alarmId.hashCode(), builder.build());

        wakeLock.release();
    }

    private void vibrate(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            Vibrator vibrator = vm.getDefaultVibrator();
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500, 200, 500}, -1));
        } else {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500, 200, 500}, -1));
            }
        }
    }

    private void playSound(Context context, String sound) {
        try {
            Uri alarmUri;
            if (sound != null) {
                switch (sound) {
                    case "Chime":
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                        break;
                    case "Bell":
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                        break;
                    case "Radar":
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                        break;
                    default:
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                        break;
                }
            } else {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            RingtoneManager.getRingtone(context, alarmUri).play();
        } catch (Exception e) {
            // Fallback silently
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "UxAlarm Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alarm and reminder notifications");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});

            NotificationManager notificationManager =
                    context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
