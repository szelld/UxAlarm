package com.example.uxalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

public class AlarmRingingService extends Service {
    public static final String ACTION_START = "com.example.uxalarm.action.START_RINGING";
    public static final String ACTION_STOP = "com.example.uxalarm.action.STOP_RINGING";
    public static final String CHANNEL_ID = "uxalarm_alerts";

    private static final int NOTIFICATION_ID = 7001;

    private final Handler toneHandler = new Handler(Looper.getMainLooper());
    private Ringtone ringtone;
    private ToneGenerator toneGenerator;
    private Runnable toneLoop;
    private String activeAlarmId;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopAlert();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        activeAlarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID);
        String alarmType = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_TYPE);
        String sound = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_SOUND);
        startForeground(NOTIFICATION_ID, buildNotification(activeAlarmId, alarmType));
        startAlert(sound);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAlert();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, String alarmId, String alarmType, String sound) {
        Intent intent = new Intent(context, AlarmRingingService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_TYPE, alarmType);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_SOUND, sound);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context, String alarmId) {
        Intent intent = new Intent(context, AlarmRingingService.class);
        intent.setAction(ACTION_STOP);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        context.startService(intent);
    }

    static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "UxAlarm active alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Active alarm, reminder, and bedtime alerts");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});

            NotificationManager notificationManager =
                    context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String alarmId, String alarmType) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        launchIntent.putExtra(AlarmReceiver.EXTRA_ALARM_TYPE, alarmType);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                alarmId == null ? 0 : alarmId.hashCode(),
                launchIntent,
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

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .build();
    }

    private void startAlert(String sound) {
        stopAlert();
        vibrate();
        try {
            Uri alarmUri = resolveSoundUri(sound);
            ringtone = alarmUri == null ? null : RingtoneManager.getRingtone(this, alarmUri);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setLooping(true);
                }
                ringtone.play();
                return;
            }
        } catch (Exception ignored) {
            // Fall through to generated tone.
        }
        startFallbackTone();
    }

    private void stopAlert() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        ringtone = null;
        stopFallbackTone();
        cancelVibration();

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && activeAlarmId != null) {
            notificationManager.cancel(activeAlarmId.hashCode());
        }
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void vibrate() {
        long[] pattern = new long[]{0, 500, 200, 500};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().vibrate(VibrationEffect.createWaveform(pattern, 0));
            }
        } else {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        }
    }

    private void cancelVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().cancel();
            }
        } else {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.cancel();
            }
        }
    }

    private Uri resolveSoundUri(String sound) {
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

        Uri actual = RingtoneManager.getActualDefaultRingtoneUri(this, type);
        if (actual != null) {
            return actual;
        }
        return RingtoneManager.getDefaultUri(type);
    }

    private void startFallbackTone() {
        stopFallbackTone();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        toneLoop = new Runnable() {
            @Override
            public void run() {
                if (toneGenerator == null) {
                    return;
                }
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
                toneHandler.postDelayed(this, 1200);
            }
        };
        toneLoop.run();
    }

    private void stopFallbackTone() {
        if (toneLoop != null) {
            toneHandler.removeCallbacks(toneLoop);
            toneLoop = null;
        }
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
    }
}
