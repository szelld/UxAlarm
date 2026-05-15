package com.example.uxalarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

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
    private static final Handler toneHandler = new Handler(Looper.getMainLooper());
    private static Ringtone activeRingtone;
    private static ToneGenerator activeToneGenerator;
    private static Runnable activeToneLoop;

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

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(alarmId.hashCode(), builder.build());

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

    public static void stopActiveAlert(Context context, String alarmId) {
        if (activeRingtone != null && activeRingtone.isPlaying()) {
            activeRingtone.stop();
        }
        activeRingtone = null;
        stopFallbackTone();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().cancel();
            }
        } else {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.cancel();
            }
        }

        if (alarmId != null) {
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(alarmId.hashCode());
            }
        }
    }

    private void playSound(Context context, String sound) {
        try {
            Uri alarmUri = resolveSoundUri(context, sound);
            if (activeRingtone != null && activeRingtone.isPlaying()) {
                activeRingtone.stop();
            }
            activeRingtone = alarmUri == null ? null : RingtoneManager.getRingtone(context, alarmUri);
            if (activeRingtone != null) {
                activeRingtone.play();
                return;
            }
        } catch (Exception e) {
            // Fall through to generated tone.
        }
        startFallbackTone();
    }

    private Uri resolveSoundUri(Context context, String sound) {
        int type;
        if ("System Notification".equals(sound) || "Chime".equals(sound)) {
            type = RingtoneManager.TYPE_NOTIFICATION;
        } else if ("System Ringtone".equals(sound) || "Bell".equals(sound)) {
            type = RingtoneManager.TYPE_RINGTONE;
        } else if ("Beep".equals(sound)) {
            return null;
        } else {
            type = RingtoneManager.TYPE_ALARM;
        }

        Uri actual = RingtoneManager.getActualDefaultRingtoneUri(context, type);
        if (actual != null) {
            return actual;
        }
        return RingtoneManager.getDefaultUri(type);
    }

    private void startFallbackTone() {
        stopFallbackTone();
        activeToneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        activeToneLoop = new Runnable() {
            @Override
            public void run() {
                if (activeToneGenerator == null) {
                    return;
                }
                activeToneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
                toneHandler.postDelayed(this, 1200);
            }
        };
        activeToneLoop.run();
    }

    private static void stopFallbackTone() {
        if (activeToneLoop != null) {
            toneHandler.removeCallbacks(activeToneLoop);
            activeToneLoop = null;
        }
        if (activeToneGenerator != null) {
            activeToneGenerator.stopTone();
            activeToneGenerator.release();
            activeToneGenerator = null;
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
