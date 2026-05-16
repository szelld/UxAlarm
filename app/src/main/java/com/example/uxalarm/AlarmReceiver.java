package com.example.uxalarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "uxalarm_channel";
    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_ALARM_TYPE = "alarm_type";
    public static final String EXTRA_ALARM_SOUND = "alarm_sound";
    private static final String PREFS_NAME = "uxalarm_prefs";
    private static final String PREFS_KEY_ALARMS = "alarms_json";

    @Override
    public void onReceive(Context context, Intent intent) {
        String alarmId = intent.getStringExtra(EXTRA_ALARM_ID);
        String alarmType = intent.getStringExtra(EXTRA_ALARM_TYPE);
        String sound = intent.getStringExtra(EXTRA_ALARM_SOUND);

        if (alarmId == null) return;

        if (shouldSkipTrigger(context, alarmId)) {
            requestReschedule(context);
            return;
        }

        createNotificationChannel(context);
        AlarmRingingService.createNotificationChannel(context);

        // Wake the screen
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                "uxalarm:wakelock"
        );
        wakeLock.acquire(30000);

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(EXTRA_ALARM_ID, alarmId);
        launchIntent.putExtra(EXTRA_ALARM_TYPE, alarmType);
        try {
            context.startActivity(launchIntent);
        } catch (Exception ignored) {
            // Full-screen notification below is the supported fallback on newer Android versions.
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, alarmId.hashCode(), launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title;
        String text;
        if ("ALARM".equals(alarmType)) {
            title = "Alarm";
            text = "Time to wake up!";
        } else if ("BEDTIME".equals(alarmType)) {
            title = "Time to Rest";
            text = "You should be asleep now.";
        } else {
            title = "Reminder";
            text = "You have a reminder.";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true);
        if ("BEDTIME".equals(alarmType)) {
            builder.setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setDefaults(0);
        }

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(alarmId.hashCode(), builder.build());
        if (!"BEDTIME".equals(alarmType)) {
            AlarmRingingService.start(context, alarmId, alarmType, sound);
        }

        wakeLock.release();
    }

    private boolean shouldSkipTrigger(Context context, String alarmId) {
        boolean isBedtimeReminder = alarmId.endsWith("_bedtime");
        String storedAlarmId = isBedtimeReminder
                ? alarmId.substring(0, alarmId.length() - "_bedtime".length())
                : alarmId;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(PREFS_KEY_ALARMS, null);
            if (json == null) {
                return false;
            }

            JSONArray alarms = new JSONArray(json);
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.getJSONObject(i);
                if (!storedAlarmId.equals(alarm.optString("id"))) {
                    continue;
                }
                if (!alarm.optBoolean("enabled", false)) {
                    return true;
                }
                if (!"ALARM".equals(alarm.optString("type"))) {
                    return false;
                }
                if (isBedtimeReminder) {
                    return false;
                }

                JSONArray repeat = alarm.optJSONArray("repeat");
                if (repeat == null || repeat.length() == 0) {
                    return false;
                }

                String today = repeatDayForCalendarDay(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
                for (int j = 0; j < repeat.length(); j++) {
                    if (today.equals(repeat.optString(j))) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private String repeatDayForCalendarDay(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY:
                return "Mon";
            case Calendar.TUESDAY:
                return "Tue";
            case Calendar.WEDNESDAY:
                return "Wed";
            case Calendar.THURSDAY:
                return "Thu";
            case Calendar.FRIDAY:
                return "Fri";
            case Calendar.SATURDAY:
                return "Sat";
            case Calendar.SUNDAY:
                return "Sun";
            default:
                return "";
        }
    }

    private void requestReschedule(Context context) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.putExtra("reschedule_only", true);
        context.startActivity(launchIntent);
    }

    public static void stopActiveAlert(Context context, String alarmId) {
        AlarmRingingService.stop(context, alarmId);

        if (alarmId != null) {
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(alarmId.hashCode());
            }
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
