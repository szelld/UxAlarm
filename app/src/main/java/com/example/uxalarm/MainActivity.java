package com.example.uxalarm;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int BLACK = Color.rgb(17, 24, 39);
    private static final int WHITE = Color.WHITE;
    private static final int PAGE = Color.rgb(249, 250, 251);
    private static final int BORDER = Color.rgb(229, 231, 235);
    private static final int MUTED = Color.rgb(107, 114, 128);
    private static final int LIGHT = Color.rgb(243, 244, 246);
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int BLUE = Color.rgb(37, 99, 235);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Alarm> alarms = new ArrayList<>();

    private AlarmType activeTab = AlarmType.ALARM;
    private boolean editMode = false;
    private String activeMenuId = null;
    private Alarm editingAlarm = null;
    private Alarm triggeredAlarm = null;

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
        String objective = "";
        String note = "";
        int remindBefore = 0;
        int bedtimeReminderHours = 0;
        String bedtimeReminderMessage = "";

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
            copy.objective = objective;
            copy.note = note;
            copy.remindBefore = remindBefore;
            copy.bedtimeReminderHours = bedtimeReminderHours;
            copy.bedtimeReminderMessage = bedtimeReminderMessage;
            return copy;
        }
    }

    private interface StringSetter {
        void set(String value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(WHITE);
        window.setNavigationBarColor(WHITE);

        Alarm morning = new Alarm("1", 2, 30, AlarmType.ALARM, true);
        morning.objective = "ALARM";
        morning.bedtimeReminderHours = 8;
        morning.bedtimeReminderMessage = "You need to get to sleep.";
        alarms.add(morning);

        Alarm reminder = new Alarm("2", 10, 0, AlarmType.REMINDER, true);
        reminder.note = "REMINDER";
        alarms.add(reminder);

        showAlarmList();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showAlarmList() {
        handler.removeCallbacksAndMessages(null);
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        triggeredAlarm = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(18), dp(24), dp(18));
        header.setBackgroundColor(WHITE);
        root.addView(header, new LinearLayout.LayoutParams(match(), wrap()));

        Button editButton = outlineButton(editMode ? "DONE" : "EDIT");
        editButton.setOnClickListener(v -> {
            editMode = !editMode;
            activeMenuId = null;
            showAlarmList();
        });
        header.addView(editButton, new LinearLayout.LayoutParams(wrap(), dp(44)));

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        Button addButton = circleButton("+", 40, WHITE, BLACK);
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        addButton.setOnClickListener(v -> openNewAlarm(activeTab));
        header.addView(addButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(match(), 0, 1));

        boolean hasItems = false;
        for (Alarm alarm : alarms) {
            if (alarm.type == activeTab) {
                hasItems = true;
                addAlarmCard(list, alarm);
            }
        }

        if (!hasItems) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(72), dp(24), dp(24));
            list.addView(empty, new LinearLayout.LayoutParams(match(), dp(280)));

            TextView icon = label(activeTab == AlarmType.ALARM ? "ALARM" : "REMINDER", 14, MUTED, Typeface.BOLD);
            icon.setLetterSpacing(0.1f);
            empty.addView(icon);

            TextView title = label("No " + activeTab.name().toLowerCase(Locale.US) + "s yet.", 18, MUTED, Typeface.NORMAL);
            title.setPadding(0, dp(16), 0, dp(4));
            empty.addView(title);

            TextView subtitle = label("Tap + to add one.", 14, MUTED, Typeface.NORMAL);
            empty.addView(subtitle);
        }

        root.addView(bottomNavigation(), new LinearLayout.LayoutParams(match(), wrap()));
        setContentView(root);
    }

    private void addAlarmCard(LinearLayout list, Alarm alarm) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(20), dp(24), dp(14));
        card.setBackgroundColor(WHITE);
        list.addView(card, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setOnClickListener(v -> {
            if (!editMode) {
                openExistingAlarm(alarm);
            }
        });
        row.addView(info, new LinearLayout.LayoutParams(0, wrap(), 1));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(timeRow);

        if (editMode) {
            Button delete = circleButton("-", 28, WHITE, RED);
            delete.setTextColor(RED);
            delete.setOnClickListener(v -> deleteAlarm(alarm.id));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(30), dp(30));
            deleteParams.setMarginEnd(dp(12));
            timeRow.addView(delete, deleteParams);
        }

        TextView time = label(formatTime(alarm.hour, alarm.minute), 48, alarm.enabled ? BLACK : Color.rgb(156, 163, 175), Typeface.NORMAL);
        time.setIncludeFontPadding(false);
        timeRow.addView(time);

        String subtitle = firstNonEmpty(alarm.objective, alarm.note, alarm.type.name());
        TextView description = label(subtitle.toUpperCase(Locale.US), 13, MUTED, Typeface.BOLD);
        description.setLetterSpacing(0.08f);
        description.setPadding(editMode ? dp(42) : 0, dp(8), 0, 0);
        info.addView(description);

        SwitchCompat enabledSwitch = new SwitchCompat(this);
        enabledSwitch.setChecked(alarm.enabled);
        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alarm.enabled = isChecked;
            showAlarmList();
        });
        row.addView(enabledSwitch);

        Button menuButton = flatButton("...");
        menuButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        menuButton.setOnClickListener(v -> {
            activeMenuId = alarm.id.equals(activeMenuId) ? null : alarm.id;
            showAlarmList();
        });
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(match(), dp(42));
        menuParams.topMargin = dp(10);
        card.addView(menuButton, menuParams);

        if (alarm.id.equals(activeMenuId)) {
            addInlineMenu(card, alarm);
        }

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        list.addView(divider, new LinearLayout.LayoutParams(match(), 1));
    }

    private void addInlineMenu(LinearLayout card, Alarm alarm) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setBackground(rounded(WHITE, dp(20), BORDER, 1));

        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(match(), wrap());
        menuParams.setMargins(dp(24), 0, dp(24), dp(12));
        card.addView(menu, menuParams);

        addMenuItem(menu, "Edit", BLACK, () -> openExistingAlarm(alarm));
        addMenuItem(menu, "Test Trigger", BLUE, () -> showSuccess(alarm));
        if (alarm.type == AlarmType.ALARM && alarm.bedtimeReminderHours > 0) {
            addMenuItem(menu, "Test Sleep Popup", Color.rgb(79, 70, 229), () -> showSleepReminder(alarm));
        }
        addMenuItem(menu, "Delete", RED, () -> deleteAlarm(alarm.id));
    }

    private void addMenuItem(LinearLayout menu, String text, int color, Runnable action) {
        Button item = flatButton(text);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setTextColor(color);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setOnClickListener(v -> {
            activeMenuId = null;
            action.run();
        });
        menu.addView(item, new LinearLayout.LayoutParams(match(), dp(48)));
    }

    private LinearLayout bottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(16), dp(10), dp(16), dp(14));
        nav.setBackgroundColor(WHITE);
        nav.setGravity(Gravity.CENTER);

        nav.addView(tabButton("ALARM", AlarmType.ALARM), new LinearLayout.LayoutParams(0, dp(64), 1));
        nav.addView(tabButton("REMINDER", AlarmType.REMINDER), new LinearLayout.LayoutParams(0, dp(64), 1));
        return nav;
    }

    private Button tabButton(String title, AlarmType type) {
        boolean selected = activeTab == type;
        Button button = flatButton(title);
        button.setTextColor(selected ? BLACK : Color.rgb(156, 163, 175));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(selected ? LIGHT : WHITE, dp(18), Color.TRANSPARENT, 0));
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
            alarm.bedtimeReminderMessage = "You need to get to sleep.";
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
        root.setBackgroundColor(WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(18), dp(18), dp(18));
        root.addView(header, new LinearLayout.LayoutParams(match(), wrap()));

        TextView title = label("EDIT " + editingAlarm.type.name(), 20, BLACK, Typeface.BOLD);
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
            addRepeatSection(form);
            addBedtimeSection(form);
        }

        addTextField(form, "OBJECTIVE", "e.g., Wake up, Meeting", editingAlarm.objective, value -> editingAlarm.objective = value, false);
        addTextField(form, "NOTE", "Add a note...", editingAlarm.note, value -> editingAlarm.note = value, true);

        if (editingAlarm.type == AlarmType.REMINDER) {
            addReminderBeforeSection(form);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(dp(24), dp(14), dp(24), dp(18));
        footer.setBackgroundColor(WHITE);
        Button save = filledButton("SAVE " + editingAlarm.type.name(), BLACK, WHITE);
        save.setOnClickListener(v -> saveEditingAlarm());
        footer.addView(save, new LinearLayout.LayoutParams(match(), dp(62)));
        root.addView(footer, new LinearLayout.LayoutParams(match(), wrap()));

        setContentView(root);
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
        Spinner spinner = spinner(new String[]{"Default", "Chime", "Bell", "Radar"});
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
        addDivider(form);

        if (editingAlarm.bedtimeReminderHours > 0) {
            addTextField(form, "BEDTIME MESSAGE", "You need to get to sleep.", editingAlarm.bedtimeReminderMessage,
                    value -> editingAlarm.bedtimeReminderMessage = value, false);
        }
    }

    private void addReminderBeforeSection(LinearLayout form) {
        TextView label = sectionLabel("REMIND BEFORE");
        form.addView(label);

        Spinner spinner = spinner(new String[]{"None", "5 minutes", "10 minutes", "15 minutes", "30 minutes"});
        int[] values = {0, 5, 10, 15, 30};
        int selection = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == editingAlarm.remindBefore) {
                selection = i;
                break;
            }
        }
        spinner.setSelection(selection);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editingAlarm.remindBefore = values[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        form.addView(spinner, new LinearLayout.LayoutParams(match(), dp(58)));
        addDivider(form);
    }

    private void addTextField(LinearLayout form, String title, String hint, String value, StringSetter setter, boolean multiline) {
        TextView label = sectionLabel(title);
        form.addView(label);

        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setHint(hint);
        input.setTextColor(BLACK);
        input.setHintTextColor(Color.rgb(209, 213, 219));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setBackground(rounded(WHITE, dp(18), BORDER, 2));
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
        addDivider(form);
    }

    private LinearLayout formRow(String labelText) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView label = label(labelText, 13, MUTED, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), 1));
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView label = label(text, 13, MUTED, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        label.setPadding(0, dp(20), 0, dp(12));
        return label;
    }

    private void saveEditingAlarm() {
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
        activeTab = editingAlarm.type;
        editingAlarm = null;
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
        root.setBackgroundColor(WHITE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        root.addView(panel, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER);
        panel.addView(timeRow);

        TextView hourText = bigTimeText(hour[0]);
        TextView minuteText = bigTimeText(minute[0]);

        timeRow.addView(numberColumn(hourText, () -> {
            hour[0] = (hour[0] + 1) % 24;
            hourText.setText(twoDigits(hour[0]));
        }, () -> {
            hour[0] = (hour[0] + 23) % 24;
            hourText.setText(twoDigits(hour[0]));
        }));

        TextView colon = label(":", 72, BLACK, Typeface.NORMAL);
        colon.setPadding(dp(12), 0, dp(12), dp(48));
        timeRow.addView(colon);

        timeRow.addView(numberColumn(minuteText, () -> {
            minute[0] = (minute[0] + 1) % 60;
            minuteText.setText(twoDigits(minute[0]));
        }, () -> {
            minute[0] = (minute[0] + 59) % 60;
            minuteText.setText(twoDigits(minute[0]));
        }));

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

        setContentView(root);
    }

    private LinearLayout numberColumn(TextView valueText, Runnable increment, Runnable decrement) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        Button up = circleButton("^", 62, WHITE, BLACK);
        up.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        up.setOnClickListener(v -> increment.run());
        column.addView(up, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(wrap(), wrap());
        valueParams.setMargins(0, dp(18), 0, dp(18));
        column.addView(valueText, valueParams);

        Button down = circleButton("v", 62, WHITE, BLACK);
        down.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        down.setOnClickListener(v -> decrement.run());
        column.addView(down, new LinearLayout.LayoutParams(dp(64), dp(64)));
        return column;
    }

    private TextView bigTimeText(int value) {
        TextView text = label(twoDigits(value), 72, BLACK, Typeface.NORMAL);
        text.setIncludeFontPadding(false);
        return text;
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

        TextView currentTime = label(currentClockTime(), 64, BLACK, Typeface.NORMAL);
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

        String message = alarm.type == AlarmType.ALARM ? "GOOD MORNING!" : firstNonEmpty(alarm.note, "REMINDER");
        TextView messageView = label(message, 24, MUTED, Typeface.BOLD);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0, dp(12), 0, 0);
        top.addView(messageView, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions, new LinearLayout.LayoutParams(match(), wrap()));

        if (alarm.type == AlarmType.ALARM) {
            Button snooze = filledButton("Snooze (10m)", LIGHT, BLACK);
            snooze.setOnClickListener(v -> showAlarmList());
            actions.addView(snooze, new LinearLayout.LayoutParams(match(), dp(62)));
        }

        Button stop = filledButton(alarm.type == AlarmType.REMINDER ? "Mark as Done" : "Scan Water to Stop", BLACK, WHITE);
        stop.setOnClickListener(v -> {
            if (alarm.type == AlarmType.REMINDER) {
                showAlarmList();
            } else {
                showScanScreen();
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(match(), dp(62));
        stopParams.setMargins(0, dp(12), 0, 0);
        actions.addView(stop, stopParams);

        setContentView(root);
    }

    private void showScanScreen() {
        handler.removeCallbacksAndMessages(null);
        getWindow().setStatusBarColor(Color.rgb(3, 7, 18));
        getWindow().setNavigationBarColor(Color.rgb(3, 7, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(54), dp(24), dp(34));
        root.setBackgroundColor(Color.rgb(3, 7, 18));

        TextView title = label("Scan Water", 30, WHITE, Typeface.NORMAL);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = label("Point camera at water to wake up", 17, Color.rgb(209, 213, 219), Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, 0);
        root.addView(subtitle);

        View spacerTop = new View(this);
        root.addView(spacerTop, new LinearLayout.LayoutParams(1, 0, 1));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rounded(Color.rgb(17, 24, 39), dp(48), Color.rgb(229, 231, 235), 2));
        TextView frameText = label("WATER", 20, Color.rgb(156, 163, 175), Typeface.BOLD);
        frameText.setGravity(Gravity.CENTER);
        frame.addView(frameText, new FrameLayout.LayoutParams(match(), match()));
        root.addView(frame, new LinearLayout.LayoutParams(dp(288), dp(288)));

        View spacerBottom = new View(this);
        root.addView(spacerBottom, new LinearLayout.LayoutParams(1, 0, 1));

        Button scan = filledButton("Tap to Scan", BLUE, WHITE);
        root.addView(scan, new LinearLayout.LayoutParams(match(), dp(64)));

        Button cancel = flatButton("Cancel");
        cancel.setTextColor(Color.rgb(209, 213, 219));
        cancel.setOnClickListener(v -> {
            if (triggeredAlarm != null) {
                showSuccess(triggeredAlarm);
            } else {
                showAlarmList();
            }
        });
        root.addView(cancel, new LinearLayout.LayoutParams(match(), dp(54)));

        scan.setOnClickListener(v -> {
            scan.setEnabled(false);
            scan.setText("Analyzing...");
            frameText.setText("SCANNING");
            handler.postDelayed(() -> {
                scan.setText("Water Verified!");
                frameText.setText("VERIFIED");
                frame.setBackground(rounded(Color.rgb(20, 83, 45), dp(48), Color.rgb(134, 239, 172), 3));
                handler.postDelayed(this::showAlarmList, 1200);
            }, 1800);
        });

        setContentView(root);
    }

    private void showSleepReminder(Alarm alarm) {
        activeMenuId = null;
        String message = firstNonEmpty(alarm.bedtimeReminderMessage, "You need to get to sleep.");
        new AlertDialog.Builder(this)
                .setTitle("Time to Rest")
                .setMessage(message)
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void deleteAlarm(String id) {
        for (int i = alarms.size() - 1; i >= 0; i--) {
            if (alarms.get(i).id.equals(id)) {
                alarms.remove(i);
            }
        }
        activeMenuId = null;
        showAlarmList();
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        spinner.setAdapter(adapter);
        spinner.setBackground(rounded(WHITE, dp(18), BORDER, 2));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        return spinner;
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(selected ? WHITE : MUTED);
        button.setBackground(rounded(selected ? BLACK : WHITE, dp(18), selected ? BLACK : BORDER, 2));
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
        divider.setBackgroundColor(BORDER);
        parent.addView(divider, new LinearLayout.LayoutParams(match(), 1));
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String formatTime(int hour, int minute) {
        return hour + ":" + twoDigits(minute);
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
}
