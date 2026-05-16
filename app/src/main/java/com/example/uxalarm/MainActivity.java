package com.example.uxalarm;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Field;

public class MainActivity extends AppCompatActivity {
    private static final int BLACK = Color.BLACK;
    private static final int WHITE = Color.WHITE;
    private static final String PREFS_NAME = "uxalarm_prefs";
    private static final String PREFS_KEY_ALARMS = "alarms_json";
    private static final ScanTarget[] SCAN_TARGETS = {
            new ScanTarget("bottle_water", "💧", "Bottle of water", new String[]{"tableware", "bottle", "water bottle", "plastic bottle"}),
            new ScanTarget("shoe", "👟", "Shoe", new String[]{"shoe", "footwear", "sneaker"}),
            new ScanTarget("book", "📚", "Book", new String[]{"book", "books"}),
            new ScanTarget("glasses", "👓", "Glasses", new String[]{"goggles", "glasses", "sunglasses", "eyewear"}),
            new ScanTarget("paper", "📄", "Paper", new String[]{"paper", "document", "paper product", "stationery"})
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Alarm> alarms = new ArrayList<>();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private AlarmType activeTab = AlarmType.ALARM;
    private String activeMenuId = null;
    private Alarm editingAlarm = null;
    private Alarm triggeredAlarm = null;
    private androidx.camera.lifecycle.ProcessCameraProvider activeCameraProvider = null;

    private enum AlarmType {
        ALARM,
        REMINDER
    }

    private static class Alarm {
        String id;
        int hour;
        int minute;
        AlarmType type;
        boolean enabled;
        String sound = "Default";
        List<String> repeat = new ArrayList<>();
        int bedtimeReminderHours = 0;
        String bedtimeText = "";
        String goodMorningText = "";
        String reminderText = "";
        String scanTargetKey = "bottle_water";

        Alarm(String id, int hour, int minute, AlarmType type, boolean enabled) {
            this.id = id;
            this.hour = hour;
            this.minute = minute;
            this.type = type;
            this.enabled = enabled;
        }

        Alarm copy() {
            Alarm copy = new Alarm(id, hour, minute, type, enabled);
            copy.sound = sound;
            copy.repeat = new ArrayList<>(repeat);
            copy.bedtimeReminderHours = bedtimeReminderHours;
            copy.bedtimeText = bedtimeText;
            copy.goodMorningText = goodMorningText;
            copy.reminderText = reminderText;
            copy.scanTargetKey = scanTargetKey;
            return copy;
        }
    }

    private static class ScanTarget {
        String key;
        String emoji;
        String name;
        String[] labels;

        ScanTarget(String key, String emoji, String name, String[] labels) {
            this.key = key;
            this.emoji = emoji;
            this.name = name;
            this.labels = labels;
        }

        String displayName() {
            return emoji + " " + name;
        }
    }

    private interface StringSetter {
        void set(String value);
    }

    private interface IntSetter {
        void set(int value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(WHITE);
        window.setNavigationBarColor(WHITE);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        loadAlarms();

        if (alarms.isEmpty()) {
            Alarm morning = new Alarm("1", 7, 30, AlarmType.ALARM, true);
            morning.goodMorningText = "Good Morning!";
            morning.bedtimeReminderHours = 8;
            morning.bedtimeText = "You need to get to sleep.";
            alarms.add(morning);

            Alarm reminder = new Alarm("2", 10, 0, AlarmType.REMINDER, true);
            reminder.reminderText = "Don't forget!";
            alarms.add(reminder);
            saveAlarms();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 200);
        }

        rescheduleAllAlarms();
        handleAlarmIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAlarmIntent(intent);
    }

    private void handleAlarmIntent(Intent intent) {
        if (intent == null) {
            showAlarmList();
            return;
        }
        if (intent.getBooleanExtra("reschedule_only", false)) {
            rescheduleAllAlarms();
            finish();
            return;
        }
        String alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID);
        if (alarmId != null) {
            if (alarmId.endsWith("_bedtime")) {
                String baseAlarmId = alarmId.substring(0, alarmId.length() - "_bedtime".length());
                for (Alarm alarm : alarms) {
                    if (alarm.id.equals(baseAlarmId)) {
                        showAlarmList();
                        handler.post(() -> showSleepReminder(alarm));
                        return;
                    }
                }
            }
            for (Alarm alarm : alarms) {
                if (alarm.id.equals(alarmId)) {
                    handleTriggeredAlarmState(alarm);
                    if (alarm.type == AlarmType.ALARM) {
                        triggeredAlarm = alarm.copy();
                        showScanScreen();
                    } else {
                        showSuccess(alarm);
                    }
                    return;
                }
            }
        }
        showAlarmList();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopCameraPreview();
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    private FrameLayout wrapWithBackground(View content, boolean swapImages) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(WHITE);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(match(), match());
        contentParams.setMargins(dp(4), dp(28), dp(4), dp(4));
        root.addView(content, contentParams);
        return root;
    }

    private void showAlarmList() {
        handler.removeCallbacksAndMessages(null);
        stopCameraPreview();
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        triggeredAlarm = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(match(), 0, 1));

        List<Alarm> visibleAlarms = new ArrayList<>();
        for (Alarm alarm : alarms) {
            if (alarm.type == activeTab) {
                visibleAlarms.add(alarm);
            }
        }
        Collections.sort(visibleAlarms, Comparator.comparingInt(this::minutesSinceMidnight));
        for (Alarm alarm : visibleAlarms) {
            addAlarmCard(list, alarm);
        }

        if (visibleAlarms.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(72), dp(24), dp(24));
            list.addView(empty, new LinearLayout.LayoutParams(match(), dp(280)));

            TextView icon = label(activeTab == AlarmType.ALARM ? "ALARM" : "REMINDER", 16, BLACK, Typeface.BOLD);
            icon.setLetterSpacing(0.1f);
            empty.addView(icon);

            TextView title = label("No " + activeTab.name().toLowerCase(Locale.US) + "s yet.", 20, BLACK, Typeface.NORMAL);
            title.setPadding(0, dp(16), 0, dp(4));
            empty.addView(title);

            TextView subtitle = label("Tap + to add one.", 16, BLACK, Typeface.NORMAL);
            empty.addView(subtitle);
        }

        root.addView(bottomNavigation(), new LinearLayout.LayoutParams(match(), wrap()));
        
        FrameLayout wrapped = wrapWithBackground(root, false);
        ImageButton addButton = new ImageButton(this);
        Drawable addIcon = AppCompatResources.getDrawable(this, R.drawable.ic_add);
        if (addIcon != null) {
            addIcon.setColorFilter(new PorterDuffColorFilter(BLACK, PorterDuff.Mode.SRC_IN));
            addButton.setImageDrawable(addIcon);
        }
        addButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        addButton.setBackground(rounded(WHITE, dp(14), BLACK, 2));
        addButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        addButton.setOnClickListener(v -> openNewAlarm(activeTab));
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        addParams.gravity = Gravity.BOTTOM | Gravity.END;
        addParams.setMargins(0, 0, dp(24), dp(90));
        wrapped.addView(addButton, addParams);
        
        setContentView(wrapped);
    }

    private void addAlarmCard(LinearLayout list, Alarm alarm) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(24), dp(18), dp(24));
        card.setBackground(rounded(WHITE, dp(16), BLACK, 2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(match(), wrap());
        cardParams.setMargins(0, 0, 0, dp(14));
        list.addView(card, cardParams);

        TextView time = label(formatTime(alarm.hour, alarm.minute), 64, BLACK, Typeface.NORMAL);
        time.setIncludeFontPadding(false);
        time.setOnClickListener(v -> openExistingAlarm(alarm));
        card.addView(time, new LinearLayout.LayoutParams(0, wrap(), 1));

        if (alarm.type == AlarmType.ALARM) {
            SwitchCompat enabledSwitch = new SwitchCompat(this);
            enabledSwitch.setChecked(alarm.enabled);
            enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                alarm.enabled = isChecked;
                saveAlarms();
                if (isChecked) {
                    scheduleAlarm(alarm);
                } else {
                    cancelAlarm(alarm);
                }
                showAlarmList();
            });
            card.addView(enabledSwitch);
        } else {
            ImageButton delete = new ImageButton(this);
            Drawable deleteIcon = AppCompatResources.getDrawable(this, R.drawable.ic_delete);
            if (deleteIcon != null) {
                deleteIcon.setColorFilter(new PorterDuffColorFilter(BLACK, PorterDuff.Mode.SRC_IN));
                delete.setImageDrawable(deleteIcon);
            }
            delete.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            delete.setBackground(rounded(Color.TRANSPARENT, dp(21), Color.TRANSPARENT, 0));
            delete.setPadding(dp(6), dp(6), dp(6), dp(6));
            delete.setOnClickListener(v -> deleteAlarm(alarm.id));
            card.addView(delete, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }


    }

    private LinearLayout bottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(16), dp(10), dp(16), dp(14));
        nav.setBackgroundColor(Color.TRANSPARENT);
        nav.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams tabParams1 = new LinearLayout.LayoutParams(0, dp(64), 1);
        tabParams1.setMarginEnd(dp(6));
        nav.addView(tabButton(R.drawable.ic_alarm, AlarmType.ALARM), tabParams1);

        LinearLayout.LayoutParams tabParams2 = new LinearLayout.LayoutParams(0, dp(64), 1);
        tabParams2.setMarginStart(dp(6));
        nav.addView(tabButton(R.drawable.ic_notifications, AlarmType.REMINDER), tabParams2);
        return nav;
    }

    private ImageButton tabButton(int iconRes, AlarmType type) {
        boolean selected = activeTab == type;
        ImageButton button = new ImageButton(this);
        Drawable tabIcon = AppCompatResources.getDrawable(this, iconRes);
        if (tabIcon != null) {
            tabIcon = tabIcon.mutate();
            tabIcon.setColorFilter(new PorterDuffColorFilter(selected ? WHITE : BLACK, PorterDuff.Mode.SRC_IN));
            button.setImageDrawable(tabIcon);
        }
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(rounded(selected ? BLACK : WHITE, dp(18), BLACK, 2));
        button.setOnClickListener(v -> {
            activeTab = type;
            activeMenuId = null;
            showAlarmList();
        });
        return button;
    }

    private void openNewAlarm(AlarmType type) {
        Alarm alarm = new Alarm(String.valueOf(System.currentTimeMillis()), 8, 0, type, true);
        if (type == AlarmType.ALARM) {
            alarm.bedtimeReminderHours = 8;
            alarm.bedtimeText = "You need to get to sleep.";
        }
        editingAlarm = alarm;
        showEditAlarm();
    }

    private void openExistingAlarm(Alarm alarm) {
        editingAlarm = alarm.copy();
        showEditAlarm();
    }

    private void showEditAlarm() {
        handler.removeCallbacksAndMessages(null);
        activeMenuId = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(18), dp(18), dp(18));
        root.addView(header, new LinearLayout.LayoutParams(match(), wrap()));

        TextView title = label("EDIT " + editingAlarm.type.name(), 22, BLACK, Typeface.BOLD);
        title.setLetterSpacing(0.06f);
        header.addView(title, new LinearLayout.LayoutParams(0, wrap(), 1));

        Button close = circleButton("x", 42, WHITE, Color.TRANSPARENT);
        close.setTextColor(BLACK);
        close.setOnClickListener(v -> {
            editingAlarm = null;
            showAlarmList();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(BLACK);
        root.addView(headerDivider, new LinearLayout.LayoutParams(match(), 1));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(12), dp(24), dp(24));
        scrollView.addView(form);
        root.addView(scrollView, new LinearLayout.LayoutParams(match(), 0, 1));

        addTimeRow(form);
        addSoundRow(form);
        if (editingAlarm.type == AlarmType.ALARM) {
            addScanTargetSection(form);
            addRepeatSection(form);
            addBedtimeSection(form);
            addTextField(form, "GOOD MORNING TEXT", "e.g., Good Morning!", editingAlarm.goodMorningText, value -> editingAlarm.goodMorningText = value, false);
        } else {
            addTextField(form, "REMINDER TEXT", "What to remind you about...", editingAlarm.reminderText, value -> editingAlarm.reminderText = value, true);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(dp(24), dp(14), dp(24), dp(18));
        footer.setBackgroundColor(Color.TRANSPARENT);
        Button save = filledButton("SAVE " + editingAlarm.type.name(), BLACK, WHITE);
        save.setOnClickListener(v -> saveEditingAlarm());
        footer.addView(save, new LinearLayout.LayoutParams(match(), dp(62)));
        root.addView(footer, new LinearLayout.LayoutParams(match(), wrap()));

        setContentView(wrapWithBackground(root, true));
    }

    private void addTimeRow(LinearLayout form) {
        LinearLayout row = formRow("TIME");
        Button timeButton = outlineButton("CLOCK  " + formatTime(editingAlarm.hour, editingAlarm.minute));
        timeButton.setOnClickListener(v -> showTimePicker(editingAlarm.hour, editingAlarm.minute));
        row.addView(timeButton, new LinearLayout.LayoutParams(wrap(), dp(50)));
        form.addView(row);
        addDivider(form);
    }

    private void addSoundRow(LinearLayout form) {
        LinearLayout row = formRow("SOUND");
        Spinner spinner = spinner(new String[]{"System Alarm", "System Notification", "System Ringtone", "Beep"});
        setSpinnerSelection(spinner, editingAlarm.sound);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editingAlarm.sound = (String) parent.getItemAtPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(170), dp(52)));
        form.addView(row);
        addDivider(form);
    }

    private void addScanTargetSection(LinearLayout form) {
        LinearLayout row = formRow("SCAN TO STOP");
        Spinner spinner = spinner(scanTargetDisplayNames());
        setSpinnerSelection(spinner, scanTargetByKey(editingAlarm.scanTargetKey).displayName());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editingAlarm.scanTargetKey = SCAN_TARGETS[position].key;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(210), dp(52)));
        form.addView(row);
        addDivider(form);
    }

    private void addRepeatSection(LinearLayout form) {
        TextView label = sectionLabel("REPEAT");
        form.addView(label);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, 0, dp(18));
        form.addView(chips);

        List<String> weekDays = Arrays.asList("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
        for (String day : weekDays) {
            boolean selected = editingAlarm.repeat.contains(day);
            Button chip = pill(day, selected);
            chip.setOnClickListener(v -> {
                if (editingAlarm.repeat.contains(day)) {
                    editingAlarm.repeat.remove(day);
                } else {
                    editingAlarm.repeat.add(day);
                }
                showEditAlarm();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
            params.setMarginEnd(dp(4));
            chips.addView(chip, params);
        }
        addDivider(form);
    }

    private void addBedtimeSection(LinearLayout form) {
        TextView label = sectionLabel("BEDTIME REMINDER");
        form.addView(label);

        Spinner spinner = spinner(new String[]{"None", "6 hours before", "7 hours before", "8 hours before", "9 hours before", "10 hours before"});
        int selectedIndex = editingAlarm.bedtimeReminderHours == 0 ? 0 : editingAlarm.bedtimeReminderHours - 5;
        spinner.setSelection(Math.max(0, Math.min(selectedIndex, 5)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int newValue = position == 0 ? 0 : position + 5;
                if (editingAlarm.bedtimeReminderHours != newValue) {
                    editingAlarm.bedtimeReminderHours = newValue;
                    showEditAlarm();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        form.addView(spinner, new LinearLayout.LayoutParams(match(), dp(58)));
        addDividerWithSpacing(form, dp(18));

        if (editingAlarm.bedtimeReminderHours > 0) {
            addTextField(form, "BEDTIME TEXT", "You need to get to sleep.", editingAlarm.bedtimeText,
                    value -> editingAlarm.bedtimeText = value, false);
        }
    }

    private void addTextField(LinearLayout form, String title, String hint, String value, StringSetter setter, boolean multiline) {
        TextView label = sectionLabel(title);
        form.addView(label);

        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setHint(hint);
        input.setTextColor(BLACK);
        input.setHintTextColor(BLACK);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setBackground(rounded(WHITE, dp(18), BLACK, 2));
        if (multiline) {
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setPadding(dp(18), dp(14), dp(18), dp(14));
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                setter.set(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        form.addView(input, new LinearLayout.LayoutParams(match(), multiline ? dp(112) : dp(56)));
        addDividerWithSpacing(form, dp(18));
    }

    private LinearLayout formRow(String labelText) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView label = label(labelText, 15, BLACK, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), 1));
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView label = label(text, 15, BLACK, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        label.setPadding(0, dp(20), 0, dp(12));
        return label;
    }

    private void saveEditingAlarm() {
        editingAlarm.enabled = true;
        boolean replaced = false;
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).id.equals(editingAlarm.id)) {
                alarms.set(i, editingAlarm.copy());
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            alarms.add(editingAlarm.copy());
        }
        Alarm saved = editingAlarm.copy();
        activeTab = editingAlarm.type;
        editingAlarm = null;
        saveAlarms();
        if (saved.enabled) {
            scheduleAlarm(saved);
        }
        showAlarmList();
    }

    private void showTimePicker(int initialHour, int initialMinute) {
        handler.removeCallbacksAndMessages(null);
        final int[] hour = {initialHour};
        final int[] minute = {initialMinute};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(rounded(WHITE, dp(24), BLACK, 2));
        panel.setPadding(dp(24), dp(32), dp(24), dp(32));
        root.addView(panel, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER);
        panel.addView(timeRow);

        timeRow.addView(numberPicker(numberLabels(24, true), hour[0], value -> hour[0] = value), new LinearLayout.LayoutParams(dp(96), dp(270)));

        TextView colon = label(":", 72, BLACK, Typeface.NORMAL);
        colon.setGravity(Gravity.CENTER);
        timeRow.addView(colon, new LinearLayout.LayoutParams(wrap(), dp(270)));

        timeRow.addView(numberPicker(numberLabels(60, true), minute[0], value -> minute[0] = value), new LinearLayout.LayoutParams(dp(96), dp(270)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(44), 0, 0);
        panel.addView(actions, new LinearLayout.LayoutParams(match(), wrap()));

        Button cancel = outlineButton("CANCEL");
        cancel.setOnClickListener(v -> showEditAlarm());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(56), 1));

        Button set = filledButton("SET", BLACK, WHITE);
        set.setOnClickListener(v -> {
            editingAlarm.hour = hour[0];
            editingAlarm.minute = minute[0];
            showEditAlarm();
        });
        LinearLayout.LayoutParams setParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        setParams.setMarginStart(dp(12));
        actions.addView(set, setParams);

        setContentView(wrapWithBackground(root, true));
    }

    private void showSuccess(Alarm alarm) {
        handler.removeCallbacksAndMessages(null);
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        triggeredAlarm = alarm.copy();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(36));
        root.setBackgroundColor(WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(top, new LinearLayout.LayoutParams(match(), 0, 1));

        TextView bell = label("BELL", 20, WHITE, Typeface.BOLD);
        bell.setGravity(Gravity.CENTER);
        bell.setBackground(rounded(BLACK, dp(64), Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams bellParams = new LinearLayout.LayoutParams(dp(128), dp(128));
        bellParams.setMargins(0, dp(42), 0, dp(32));
        top.addView(bell, bellParams);

        TextView currentTime = label(currentClockTime(), 68, BLACK, Typeface.NORMAL);
        currentTime.setGravity(Gravity.CENTER);
        top.addView(currentTime);

        Runnable ticker = new Runnable() {
            @Override
            public void run() {
                currentTime.setText(currentClockTime());
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(ticker, 1000);

        String message = alarm.type == AlarmType.ALARM ? firstNonEmpty(alarm.goodMorningText, "GOOD MORNING!") : firstNonEmpty(alarm.reminderText, "REMINDER");
        TextView messageView = label(message, 28, BLACK, Typeface.BOLD);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0, dp(12), 0, 0);
        top.addView(messageView, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions, new LinearLayout.LayoutParams(match(), wrap()));

        if (alarm.type == AlarmType.ALARM) {
            Button snooze = filledButton("Snooze (10m)", WHITE, BLACK);
            snooze.setOnClickListener(v -> {
                stopCurrentAlert(alarm.id);
                showAlarmList();
            });
            actions.addView(snooze, new LinearLayout.LayoutParams(match(), dp(62)));
        } else {
            Button remindLater = filledButton("Remind Me Later", WHITE, BLACK);
            remindLater.setOnClickListener(v -> {
                stopCurrentAlert(alarm.id);
                showRemindLaterPicker(alarm);
            });
            actions.addView(remindLater, new LinearLayout.LayoutParams(match(), dp(62)));
        }

        Button stop = filledButton(alarm.type == AlarmType.REMINDER ? "Mark as Done" : "Scan Water to Stop", BLACK, WHITE);
        stop.setOnClickListener(v -> {
            if (alarm.type == AlarmType.REMINDER) {
                markReminderDone(alarm.id);
            } else {
                showScanScreen();
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(match(), dp(62));
        stopParams.setMargins(0, dp(12), 0, 0);
        actions.addView(stop, stopParams);

        setContentView(root);
    }

    private void showRemindLaterPicker(Alarm alarm) {
        handler.removeCallbacksAndMessages(null);
        final int[] addHours = {0};
        final int[] addMinutes = {15};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(rounded(WHITE, dp(24), BLACK, 2));
        panel.setPadding(dp(24), dp(32), dp(24), dp(32));
        root.addView(panel, new LinearLayout.LayoutParams(match(), wrap()));

        TextView title = label("Remind Me In", 20, BLACK, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(24));
        panel.addView(title);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER);
        panel.addView(timeRow);

        timeRow.addView(numberPicker(numberLabels(24, false), addHours[0], value -> addHours[0] = value), new LinearLayout.LayoutParams(dp(96), dp(270)));

        TextView colon = label("h : m", 24, BLACK, Typeface.NORMAL);
        colon.setGravity(Gravity.CENTER);
        timeRow.addView(colon, new LinearLayout.LayoutParams(wrap(), dp(270)));

        timeRow.addView(numberPicker(numberLabels(60, true), addMinutes[0], value -> addMinutes[0] = value), new LinearLayout.LayoutParams(dp(96), dp(270)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(44), 0, 0);
        panel.addView(actions, new LinearLayout.LayoutParams(match(), wrap()));

        Button cancel = outlineButton("CANCEL");
        cancel.setOnClickListener(v -> showSuccess(alarm));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(56), 1));

        Button set = filledButton("SET", BLACK, WHITE);
        set.setOnClickListener(v -> {
            int totalMins = alarm.hour * 60 + alarm.minute + addHours[0] * 60 + addMinutes[0];
            alarm.hour = (totalMins / 60) % 24;
            alarm.minute = totalMins % 60;
            for (int i = 0; i < alarms.size(); i++) {
                if (alarms.get(i).id.equals(alarm.id)) {
                    alarms.set(i, alarm);
                    break;
                }
            }
            showAlarmList();
        });
        LinearLayout.LayoutParams setParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        setParams.setMarginStart(dp(12));
        actions.addView(set, setParams);

        setContentView(wrapWithBackground(root, false));
    }

    @ExperimentalGetImage
    private void showScanScreen() {
        handler.removeCallbacksAndMessages(null);
        stopCameraPreview();
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        ScanTarget target = scanTargetByKey(triggeredAlarm != null ? triggeredAlarm.scanTargetKey : "bottle_water");
        ImageLabeler labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);
        final boolean[] itemFound = {false};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(54), dp(24), dp(34));
        root.setBackgroundColor(Color.TRANSPARENT);

        TextView title = label("Scan " + target.displayName(), 30, BLACK, Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = label("Show the selected item to unlock Stop Alarm", 17, BLACK, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, 0);
        root.addView(subtitle);

        View spacerTop = new View(this);
        root.addView(spacerTop, new LinearLayout.LayoutParams(1, 0, 1));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rounded(WHITE, dp(48), BLACK, 2));
        TextView frameText = label("LOOKING FOR " + target.name.toUpperCase(Locale.US), 18, BLACK, Typeface.BOLD);
        frameText.setGravity(Gravity.CENTER);

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 100);
            frameText.setText("CAMERA PERMISSION NEEDED");
        } else {
            androidx.camera.view.PreviewView previewView = new androidx.camera.view.PreviewView(this);
            frame.addView(previewView, new FrameLayout.LayoutParams(match(), match()));
            com.google.common.util.concurrent.ListenableFuture<androidx.camera.lifecycle.ProcessCameraProvider> cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    androidx.camera.lifecycle.ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    activeCameraProvider = cameraProvider;
                    androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    final boolean[] isProcessing = {false};
                    imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                        if (itemFound[0] || isProcessing[0]) {
                            imageProxy.close();
                            return;
                        }
                        android.media.Image mediaImage = imageProxy.getImage();
                        if (mediaImage == null) {
                            imageProxy.close();
                            return;
                        }
                        isProcessing[0] = true;
                        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                        labeler.process(image)
                                .addOnSuccessListener(labels -> {
                                    for (ImageLabel imageLabel : labels) {
                                        if (matchesScanTarget(target, imageLabel)) {
                                            itemFound[0] = true;
                                            runOnUiThread(() -> {
                                                frameText.setText(target.name.toUpperCase(Locale.US) + " FOUND");
                                                frame.setBackground(rounded(WHITE, dp(48), BLACK, 3));
                                            });
                                            break;
                                        }
                                    }
                                    if (!itemFound[0] && !labels.isEmpty()) {
                                        ImageLabel best = labels.get(0);
                                        runOnUiThread(() -> frameText.setText("SAW " + best.getText().toUpperCase(Locale.US)));
                                    }
                                })
                                .addOnFailureListener(e -> runOnUiThread(() -> frameText.setText("KEEP SCANNING")))
                                .addOnCompleteListener(task -> {
                                    isProcessing[0] = false;
                                    imageProxy.close();
                                });
                    });

                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                } catch (Exception e) {}
            }, androidx.core.content.ContextCompat.getMainExecutor(this));
        }

        frame.addView(frameText, new FrameLayout.LayoutParams(match(), match()));
        root.addView(frame, new LinearLayout.LayoutParams(dp(288), dp(288)));

        View spacerBottom = new View(this);
        root.addView(spacerBottom, new LinearLayout.LayoutParams(1, 0, 1));

        Button stop = filledButton("Looking for " + target.displayName(), BLACK, WHITE);
        stop.setEnabled(false);
        root.addView(stop, new LinearLayout.LayoutParams(match(), dp(64)));

        Button cancel = flatButton("Cancel");
        cancel.setTextColor(BLACK);
        cancel.setOnClickListener(v -> {
            labeler.close();
            stopCameraPreview();
            if (triggeredAlarm != null) {
                showSuccess(triggeredAlarm);
            } else {
                showAlarmList();
            }
        });
        root.addView(cancel, new LinearLayout.LayoutParams(match(), dp(54)));

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (itemFound[0]) {
                    stop.setText(target.displayName() + " found - Stop Alarm");
                    stop.setEnabled(true);
                    return;
                }
                handler.postDelayed(this, 250);
            }
        }, 250);

        stop.setOnClickListener(v -> {
            labeler.close();
            stopCameraPreview();
            if (triggeredAlarm != null) {
                stopCurrentAlert(triggeredAlarm.id);
            }
            showAlarmList();
        });

        setContentView(wrapWithBackground(root, false));
    }

    private void showSleepReminder(Alarm alarm) {
        activeMenuId = null;
        String message = "You should be asleep now so you can sleep "
                + alarm.bedtimeReminderHours
                + " hours before your alarm.";
        String customText = firstNonEmpty(alarm.bedtimeText);
        if (!customText.isEmpty()) {
            message += "\n\n" + customText;
        }
        new AlertDialog.Builder(this)
                .setTitle("Time to Rest")
                .setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> {
                    stopCurrentAlert(alarm.id + "_bedtime");
                    dialog.dismiss();
                })
                .show();
    }

    private void markReminderDone(String id) {
        stopCurrentAlert(id);
        for (int i = alarms.size() - 1; i >= 0; i--) {
            if (alarms.get(i).id.equals(id)) {
                cancelAlarm(alarms.get(i));
                alarms.remove(i);
            }
        }
        activeMenuId = null;
        triggeredAlarm = null;
        activeTab = AlarmType.REMINDER;
        saveAlarms();
        showAlarmList();
    }

    private void handleTriggeredAlarmState(Alarm alarm) {
        if (alarm.type != AlarmType.ALARM) {
            return;
        }

        if (alarm.repeat.isEmpty()) {
            alarm.enabled = false;
            cancelAlarm(alarm);
        } else {
            scheduleAlarm(alarm);
        }
        saveAlarms();
    }

    private void stopCurrentAlert(String id) {
        AlarmReceiver.stopActiveAlert(this, id);
    }

    private void stopCameraPreview() {
        if (activeCameraProvider != null) {
            activeCameraProvider.unbindAll();
            activeCameraProvider = null;
        }
    }

    private void deleteAlarm(String id) {
        for (int i = alarms.size() - 1; i >= 0; i--) {
            if (alarms.get(i).id.equals(id)) {
                cancelAlarm(alarms.get(i));
                alarms.remove(i);
            }
        }
        activeMenuId = null;
        saveAlarms();
        showAlarmList();
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(rounded(WHITE, dp(18), BLACK, 2));
        spinner.setPopupBackgroundDrawable(rounded(WHITE, dp(12), BLACK, 1));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        return spinner;
    }

    private void styleSpinnerText(View view) {
        view.setBackgroundColor(WHITE);
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(BLACK);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            textView.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
    }

    private NumberPicker numberPicker(String[] labels, int selectedValue, IntSetter setter) {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(labels.length - 1);
        picker.setDisplayedValues(labels);
        picker.setValue(Math.max(0, Math.min(selectedValue, labels.length - 1)));
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setBackground(rounded(WHITE, dp(18), BLACK, 2));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.setTextColor(BLACK);
        }
        applyNumberPickerTextColor(picker);
        picker.post(() -> applyNumberPickerTextColor(picker));
        picker.setOnValueChangedListener((view, oldVal, newVal) -> {
            setter.set(newVal);
            applyNumberPickerTextColor(view);
            view.post(() -> applyNumberPickerTextColor(view));
        });
        return picker;
    }

    private void applyNumberPickerTextColor(NumberPicker picker) {
        for (int i = 0; i < picker.getChildCount(); i++) {
            View child = picker.getChildAt(i);
            if (child instanceof TextView) {
                TextView textView = (TextView) child;
                textView.setTextColor(BLACK);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                textView.setGravity(Gravity.CENTER);
                textView.setBackgroundColor(WHITE);
            }
        }
        try {
            Field selectorWheelPaintField = NumberPicker.class.getDeclaredField("mSelectorWheelPaint");
            selectorWheelPaintField.setAccessible(true);
            Paint paint = (Paint) selectorWheelPaintField.get(picker);
            if (paint != null) {
                paint.setColor(BLACK);
                paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30, getResources().getDisplayMetrics()));
            }
            picker.invalidate();
        } catch (Exception ignored) {}
    }

    private String[] numberLabels(int count, boolean twoDigit) {
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = twoDigit ? twoDigits(i) : String.valueOf(i);
        }
        return labels;
    }

    private void setSpinnerSelection(Spinner spinner, String value) {
        for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
            if (spinner.getAdapter().getItem(i).equals(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private Button flatButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        button.setTextColor(BLACK);
        button.setBackground(rounded(Color.TRANSPARENT, dp(18), Color.TRANSPARENT, 0));
        return button;
    }

    private Button outlineButton(String text) {
        Button button = flatButton(text);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setLetterSpacing(0.06f);
        button.setBackground(rounded(WHITE, dp(24), BLACK, 2));
        button.setPadding(dp(18), 0, dp(18), 0);
        return button;
    }

    private Button filledButton(String text, int background, int foreground) {
        Button button = flatButton(text);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setLetterSpacing(0.05f);
        button.setTextColor(foreground);
        button.setBackground(rounded(background, dp(24), Color.TRANSPARENT, 0));
        return button;
    }

    private Button circleButton(String text, int sizeDp, int background, int strokeColor) {
        Button button = flatButton(text);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(background, dp(sizeDp / 2), strokeColor, strokeColor == Color.TRANSPARENT ? 0 : 2));
        return button;
    }

    private Button pill(String text, boolean selected) {
        Button button = flatButton(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(selected ? WHITE : BLACK);
        button.setBackground(rounded(selected ? BLACK : WHITE, dp(18), selected ? BLACK : BLACK, 2));
        return button;
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return drawable;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(BLACK);
        parent.addView(divider, new LinearLayout.LayoutParams(match(), 1));
    }

    private void addDividerWithSpacing(LinearLayout parent, int topMargin) {
        View divider = new View(this);
        divider.setBackgroundColor(BLACK);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), 1);
        params.setMargins(0, topMargin, 0, 0);
        parent.addView(divider, params);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private ScanTarget scanTargetByKey(String key) {
        for (ScanTarget target : SCAN_TARGETS) {
            if (target.key.equals(key)) {
                return target;
            }
        }
        return SCAN_TARGETS[0];
    }

    private String[] scanTargetDisplayNames() {
        String[] names = new String[SCAN_TARGETS.length];
        for (int i = 0; i < SCAN_TARGETS.length; i++) {
            names[i] = SCAN_TARGETS[i].displayName();
        }
        return names;
    }

    private boolean matchesScanTarget(ScanTarget target, ImageLabel label) {
        if (label.getConfidence() < 0.55f) {
            return false;
        }
        String normalized = label.getText().toLowerCase(Locale.US);
        for (String acceptedLabel : target.labels) {
            if (normalized.contains(acceptedLabel.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private String formatTime(int hour, int minute) {
        return twoDigits(hour) + ":" + twoDigits(minute);
    }

    private int minutesSinceMidnight(Alarm alarm) {
        return alarm.hour * 60 + alarm.minute;
    }

    private String twoDigits(int value) {
        return String.format(Locale.US, "%02d", value);
    }

    private String currentClockTime() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private int match() {
        return LinearLayout.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return LinearLayout.LayoutParams.WRAP_CONTENT;
    }

    // ── Persistence ──

    private void saveAlarms() {
        try {
            JSONArray arr = new JSONArray();
            for (Alarm a : alarms) {
                JSONObject obj = new JSONObject();
                obj.put("id", a.id);
                obj.put("hour", a.hour);
                obj.put("minute", a.minute);
                obj.put("type", a.type.name());
                obj.put("enabled", a.enabled);
                obj.put("sound", a.sound);
                obj.put("repeat", new JSONArray(a.repeat));
                obj.put("bedtimeReminderHours", a.bedtimeReminderHours);
                obj.put("bedtimeText", a.bedtimeText);
                obj.put("goodMorningText", a.goodMorningText);
                obj.put("reminderText", a.reminderText);
                obj.put("scanTargetKey", a.scanTargetKey);
                arr.put(obj);
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREFS_KEY_ALARMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadAlarms() {
        alarms.clear();
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREFS_KEY_ALARMS, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Alarm a = new Alarm(
                        obj.getString("id"),
                        obj.getInt("hour"),
                        obj.getInt("minute"),
                        AlarmType.valueOf(obj.getString("type")),
                        obj.getBoolean("enabled")
                );
                a.sound = obj.optString("sound", "Default");
                JSONArray rep = obj.optJSONArray("repeat");
                if (rep != null) {
                    for (int j = 0; j < rep.length(); j++) a.repeat.add(rep.getString(j));
                }
                a.bedtimeReminderHours = obj.optInt("bedtimeReminderHours", 0);
                a.bedtimeText = obj.optString("bedtimeText", "");
                a.goodMorningText = obj.optString("goodMorningText", "");
                a.reminderText = obj.optString("reminderText", "");
                a.scanTargetKey = obj.optString("scanTargetKey", "bottle_water");
                alarms.add(a);
            }
        } catch (Exception ignored) {}
    }

    // ── Scheduling ──

    private void scheduleAlarm(Alarm alarm) {
        cancelAlarm(alarm);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_TYPE, alarm.type.name());
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_SOUND, alarm.sound);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, alarm.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Calendar cal = nextTriggerTime(alarm);

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }

        // Schedule bedtime reminder if applicable
        if (alarm.type == AlarmType.ALARM && alarm.bedtimeReminderHours > 0) {
            scheduleBedtimeReminder(alarm, cal.getTimeInMillis());
        }
    }

    private Calendar nextTriggerTime(Alarm alarm) {
        Calendar now = Calendar.getInstance();
        Calendar candidate = Calendar.getInstance();
        candidate.set(Calendar.HOUR_OF_DAY, alarm.hour);
        candidate.set(Calendar.MINUTE, alarm.minute);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);

        if (alarm.type != AlarmType.ALARM || alarm.repeat.isEmpty()) {
            if (candidate.getTimeInMillis() <= now.getTimeInMillis()) {
                candidate.add(Calendar.DAY_OF_YEAR, 1);
            }
            return candidate;
        }

        Calendar best = null;
        for (String repeatDay : alarm.repeat) {
            Calendar repeated = (Calendar) candidate.clone();
            int targetDay = calendarDayForRepeat(repeatDay);
            int daysUntilTarget = (targetDay - now.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            repeated.add(Calendar.DAY_OF_YEAR, daysUntilTarget);
            if (repeated.getTimeInMillis() <= now.getTimeInMillis()) {
                repeated.add(Calendar.DAY_OF_YEAR, 7);
            }
            if (best == null || repeated.getTimeInMillis() < best.getTimeInMillis()) {
                best = repeated;
            }
        }
        return best == null ? candidate : best;
    }

    private int calendarDayForRepeat(String repeatDay) {
        switch (repeatDay) {
            case "Mon":
                return Calendar.MONDAY;
            case "Tue":
                return Calendar.TUESDAY;
            case "Wed":
                return Calendar.WEDNESDAY;
            case "Thu":
                return Calendar.THURSDAY;
            case "Fri":
                return Calendar.FRIDAY;
            case "Sat":
                return Calendar.SATURDAY;
            case "Sun":
                return Calendar.SUNDAY;
            default:
                return Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        }
    }

    private void scheduleBedtimeReminder(Alarm alarm, long alarmTimeMillis) {
        long bedtimeMillis = alarmTimeMillis - (alarm.bedtimeReminderHours * 3600000L);
        if (bedtimeMillis <= System.currentTimeMillis()) return;

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id + "_bedtime");
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_TYPE, "BEDTIME");
        intent.putExtra(AlarmReceiver.EXTRA_ALARM_SOUND, "Chime");
        PendingIntent pi = PendingIntent.getBroadcast(
                this, (alarm.id + "_bedtime").hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeMillis, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, bedtimeMillis, pi);
        }
    }

    private void cancelAlarm(Alarm alarm) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, alarm.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        am.cancel(pi);

        // Also cancel bedtime reminder
        PendingIntent bedtimePi = PendingIntent.getBroadcast(
                this, (alarm.id + "_bedtime").hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        am.cancel(bedtimePi);
    }

    private void rescheduleAllAlarms() {
        loadAlarms();
        for (Alarm alarm : alarms) {
            if (alarm.enabled) {
                scheduleAlarm(alarm);
            }
        }
    }
}
