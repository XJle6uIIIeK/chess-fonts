package com.n3k0.schedule;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_PDF = 5001;
    private static final int REQUEST_NOTIFICATIONS = 5002;

    private static final int BG = Color.rgb(15, 17, 21);
    private static final int SURFACE = Color.rgb(23, 27, 33);
    private static final int SURFACE_2 = Color.rgb(29, 35, 43);
    private static final int BORDER = Color.rgb(42, 50, 61);
    private static final int TEXT = Color.rgb(244, 246, 248);
    private static final int MUTED = Color.rgb(153, 163, 175);
    private static final int ACCENT = Color.rgb(130, 175, 255);
    private static final int WARN = Color.rgb(235, 204, 132);

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler uiHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            refreshTimeSensitiveState();
            uiHandler.postDelayed(this, 30_000L);
        }
    };

    private SharedPreferences prefs;

    private AppState.Screen currentScreen =
            AppState.Screen.SCHEDULE;

    private boolean importing = false;
    private boolean updatingNotificationUi = false;
    private String pendingTestKind;

    private LocalDate selectedDate = LocalDate.now();
    private LocalDate lastClockDate = LocalDate.now();

    private LinearLayout scheduleContainer;
    private LinearLayout settingsContainer;

    private TextView scheduleTab;
    private TextView settingsTab;

    private TextView screenTitle;
    private TextView scheduleGroupLabel;
    private TextView currentStatusCard;
    private TextView expiryBanner;
    private TextView weekTitle;
    private LinearLayout weekDaysRow;
    private TextView dayScheduleText;

    private MaterialSwitch nextSwitch;
    private MaterialSwitch repeatSwitch;
    private MaterialSwitch longBreakSwitch;
    private MaterialSwitch tomorrowSwitch;

    private TextView nextValue;
    private TextView repeatValue;
    private TextView longBreakValue;
    private TextView tomorrowValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PDFBoxResourceLoader.init(getApplicationContext());
        NotificationScheduler.createChannel(this);
        prefs = NotificationScheduler.settings(this);

        // v0.5.2 could leave a pending PDF blocked by heuristic
        // "critical" errors. Re-run that stored import through the new
        // normalizer and non-blocking validator before rendering the UI.
        ScheduleStore.reprocessPending(this);

        setContentView(buildUi());
        requestNotificationPermission();

        renderAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAll();
        startUiClock();
    }

    @Override
    protected void onPause() {
        stopUiClock();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopUiClock();
        executor.shutdown();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(
                root,
                new ScrollView.LayoutParams(-1, -2)
        );

        screenTitle = text(
                "Расписание",
                30,
                TEXT,
                true
        );
        root.addView(screenTitle);

        scheduleGroupLabel = text(
                "",
                14,
                MUTED,
                false
        );
        scheduleGroupLabel.setPadding(
                0,
                dp(2),
                0,
                dp(12)
        );
        root.addView(scheduleGroupLabel);

        root.addView(buildTabs());

        scheduleContainer = new LinearLayout(this);
        scheduleContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(
                scheduleContainer,
                new LinearLayout.LayoutParams(-1, -2)
        );

        settingsContainer = new LinearLayout(this);
        settingsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(
                settingsContainer,
                new LinearLayout.LayoutParams(-1, -2)
        );

        return scroll;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(14));

        scheduleTab = tab("Расписание");
        settingsTab = tab("Настройки");

        scheduleTab.setOnClickListener(v ->
                switchScreen(AppState.Screen.SCHEDULE)
        );

        settingsTab.setOnClickListener(v ->
                switchScreen(AppState.Screen.SETTINGS)
        );

        LinearLayout.LayoutParams left =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        left.setMarginEnd(dp(5));

        LinearLayout.LayoutParams right =
                new LinearLayout.LayoutParams(0, dp(50), 1f);
        right.setMarginStart(dp(5));

        row.addView(scheduleTab, left);
        row.addView(settingsTab, right);

        return row;
    }

    private TextView tab(String label) {
        TextView view = text(
                label,
                15,
                MUTED,
                true
        );
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void switchScreen(AppState.Screen screen) {
        currentScreen = screen;
        renderTabs();

        scheduleContainer.setVisibility(
                screen == AppState.Screen.SCHEDULE
                        ? View.VISIBLE
                        : View.GONE
        );

        settingsContainer.setVisibility(
                screen == AppState.Screen.SETTINGS
                        ? View.VISIBLE
                        : View.GONE
        );

        if (screen == AppState.Screen.SCHEDULE) {
            renderScheduleScreen();
        } else {
            renderSettingsScreen();
        }
    }

    private void renderAll() {
        renderTabs();

        if (currentScreen == AppState.Screen.SCHEDULE) {
            renderScheduleScreen();
        } else {
            renderSettingsScreen();
        }

        scheduleContainer.setVisibility(
                currentScreen == AppState.Screen.SCHEDULE
                        ? View.VISIBLE
                        : View.GONE
        );

        settingsContainer.setVisibility(
                currentScreen == AppState.Screen.SETTINGS
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    private void renderTabs() {
        if (screenTitle != null) {
            screenTitle.setText(
                    currentScreen == AppState.Screen.SCHEDULE
                            ? "Расписание"
                            : "Настройки"
            );
        }

        if (scheduleGroupLabel != null &&
                currentScreen == AppState.Screen.SETTINGS) {
            scheduleGroupLabel.setText("");
        }

        styleTab(
                scheduleTab,
                currentScreen == AppState.Screen.SCHEDULE
        );
        styleTab(
                settingsTab,
                currentScreen == AppState.Screen.SETTINGS
        );
    }

    private void styleTab(
            TextView tab,
            boolean active
    ) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(active ? SURFACE_2 : SURFACE);
        bg.setStroke(
                dp(1),
                active ? ACCENT : BORDER
        );

        tab.setBackground(bg);
        tab.setTextColor(active ? TEXT : MUTED);
    }

    // ---------------------------------------------------------------------
    // Schedule screen
    // ---------------------------------------------------------------------

    private void renderScheduleScreen() {
        scheduleContainer.removeAllViews();

        AppState state =
                AppState.resolve(this, importing);

        List<ScheduleEvent> activeEvents =
                ScheduleStore.loadEvents(this);

        String group = activeGroup();

        scheduleGroupLabel.setText(
                group.isEmpty()
                        ? ""
                        : group
        );

        currentStatusCard = infoCard("");
        scheduleContainer.addView(currentStatusCard);
        addGap(scheduleContainer, 12);

        if (!state.hasActiveSchedule || group.isEmpty()) {
            if (state.hasPendingSchedule) {
                String pendingGroup =
                        ScheduleStore
                                .loadPendingSelectedGroup(this)
                                .trim();

                currentStatusCard.setText(
                        pendingGroup.isEmpty()
                                ? "PDF распознан. Открой «Настройки» и выбери группу."
                                : "PDF распознан, группа выбрана. Открой «Настройки» и подтверди расписание."
                );
            } else {
                currentStatusCard.setText(
                        "Расписание ещё не загружено. Открой «Настройки» и импортируй PDF."
                );
            }

            currentStatusCard.setOnClickListener(v ->
                    switchScreen(AppState.Screen.SETTINGS)
            );

            return;
        }

        LocalDate start =
                NotificationScheduler.scheduleStartDate(activeEvents);
        LocalDate end =
                NotificationScheduler.scheduleEndDate(activeEvents);

        if (selectedDate.isBefore(start) ||
                selectedDate.isAfter(end)) {
            LocalDate today = LocalDate.now();

            if (!today.isBefore(start) &&
                    !today.isAfter(end)) {
                selectedDate = today;
            } else if (today.isAfter(end)) {
                selectedDate = end;
            } else {
                selectedDate = start;
            }
        }

        updateCurrentStatus(activeEvents, group, start, end);

        expiryBanner = infoCard("");
        expiryBanner.setTextColor(WARN);
        updateExpiryBanner(end);

        if (expiryBanner.getVisibility() == View.VISIBLE) {
            scheduleContainer.addView(expiryBanner);
            addGap(scheduleContainer, 12);
        }

        buildWeekSurface(group, start, end);
    }

    private void updateCurrentStatus(
            List<ScheduleEvent> activeEvents,
            String group,
            LocalDate start,
            LocalDate end
    ) {
        LocalDate today = LocalDate.now();

        if (today.isAfter(end)) {
            currentStatusCard.setText(
                    "Расписание закончилось\nПоследняя загруженная дата — " +
                            formatRuDate(end) +
                            "."
            );
            return;
        }

        if (today.isBefore(start)) {
            currentStatusCard.setText(
                    "Расписание начинается " +
                            formatRuDate(start) +
                            "."
            );
            return;
        }

        List<ScheduleEvent> events =
                ScheduleStore.forDateAndGroup(
                        this,
                        today,
                        group
                );

        if (isSelfStudyOnly(events)) {
            currentStatusCard.setText(
                    "Сегодня — день самостоятельной работы."
            );
            return;
        }

        if (events.isEmpty()) {
            currentStatusCard.setText(
                    "Сегодня занятий нет."
            );
            return;
        }

        LocalTime now = LocalTime.now();

        for (ScheduleEvent event : events) {
            if (isSelfStudy(event)) continue;

            if (!now.isBefore(event.start) &&
                    now.isBefore(event.end)) {
                currentStatusCard.setText(
                        formatCurrentOrNext(
                                "СЕЙЧАС",
                                event
                        )
                );
                return;
            }
        }

        for (ScheduleEvent event : events) {
            if (isSelfStudy(event)) continue;

            if (now.isBefore(event.start)) {
                long minutes =
                        java.time.Duration
                                .between(now, event.start)
                                .toMinutes();

                currentStatusCard.setText(
                        formatCurrentOrNext(
                                "СЛЕДУЮЩАЯ ПАРА • через " +
                                        Math.max(1, minutes) +
                                        " мин",
                                event
                        )
                );
                return;
            }
        }

        currentStatusCard.setText(
                "На сегодня занятия закончились."
        );
    }

    private String formatCurrentOrNext(
            String header,
            ScheduleEvent event
    ) {
        StringBuilder out = new StringBuilder();

        out.append(header)
                .append("\n\n")
                .append(pairLabel(event.pairNumber))
                .append("\n")
                .append(event.cleanSubject())
                .append("\n")
                .append(event.start)
                .append("–")
                .append(event.end);

        if (!event.room.isBlank()) {
            out.append(" • каб. ")
                    .append(event.room);
        }

        if (!event.teacher.isBlank()) {
            out.append("\n")
                    .append(event.teacher);
        }

        return out.toString();
    }

    private void updateExpiryBanner(LocalDate end) {
        if (expiryBanner == null) return;

        long days =
                ChronoUnit.DAYS.between(
                        LocalDate.now(),
                        end
                );

        if (days < 0) {
            expiryBanner.setText(
                    "Расписание закончилось " +
                            formatRuDate(end) +
                            ". Импортируй новый PDF в настройках."
            );
            expiryBanner.setVisibility(View.VISIBLE);
        } else if (days <= 2) {
            expiryBanner.setText(
                    "Расписание заканчивается " +
                            formatRuDate(end) +
                            ". Осталось " +
                            days +
                            " дн."
            );
            expiryBanner.setVisibility(View.VISIBLE);
        } else if (days <= 5) {
            expiryBanner.setText(
                    "Расписание действует до " +
                            formatRuDate(end) +
                            "."
            );
            expiryBanner.setVisibility(View.VISIBLE);
        } else {
            expiryBanner.setVisibility(View.GONE);
        }
    }

    private void buildWeekSurface(
            String group,
            LocalDate start,
            LocalDate end
    ) {
        LinearLayout surface = surface();
        scheduleContainer.addView(surface);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView previous = smallAction("‹");
        previous.setOnClickListener(v -> {
            selectedDate = selectedDate.minusWeeks(1);
            renderScheduleScreen();
        });

        weekTitle = text(
                "",
                16,
                TEXT,
                true
        );
        weekTitle.setGravity(Gravity.CENTER);

        TextView next = smallAction("›");
        next.setOnClickListener(v -> {
            selectedDate = selectedDate.plusWeeks(1);
            renderScheduleScreen();
        });

        header.addView(
                previous,
                new LinearLayout.LayoutParams(dp(44), dp(44))
        );
        header.addView(
                weekTitle,
                new LinearLayout.LayoutParams(0, dp(44), 1f)
        );
        header.addView(
                next,
                new LinearLayout.LayoutParams(dp(44), dp(44))
        );

        surface.addView(header);

        TextView todayAction = text(
                "Сегодня",
                13,
                ACCENT,
                true
        );
        todayAction.setGravity(Gravity.CENTER);
        todayAction.setPadding(
                0,
                dp(2),
                0,
                dp(6)
        );
        todayAction.setClickable(true);
        todayAction.setOnClickListener(v -> {
            selectedDate =
                    clampDateToActiveRange(
                            LocalDate.now()
                    );
            refreshWeek(group, start, end);
        });
        surface.addView(todayAction);

        weekDaysRow = new LinearLayout(this);
        weekDaysRow.setOrientation(LinearLayout.HORIZONTAL);
        weekDaysRow.setPadding(0, dp(6), 0, dp(10));
        surface.addView(
                weekDaysRow,
                new LinearLayout.LayoutParams(-1, -2)
        );

        dayScheduleText = text(
                "",
                15,
                TEXT,
                false
        );
        dayScheduleText.setLineSpacing(0f, 1.12f);

        final float[] swipeStartX = {0f};
        dayScheduleText.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                swipeStartX[0] = event.getX();
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - swipeStartX[0];

                if (Math.abs(dx) >= dp(48)) {
                    selectedDate = dx < 0
                            ? selectedDate.plusDays(1)
                            : selectedDate.minusDays(1);

                    refreshWeek(group, start, end);
                }

                view.performClick();
                return true;
            }

            return true;
        });

        surface.addView(dayScheduleText);

        refreshWeek(
                group,
                start,
                end
        );
    }

    private void refreshWeek(
            String group,
            LocalDate start,
            LocalDate end
    ) {
        LocalDate weekStart =
                selectedDate.minusDays(
                        selectedDate
                                .getDayOfWeek()
                                .getValue() - 1L
                );

        LocalDate weekEnd =
                weekStart.plusDays(6);

        weekTitle.setText(
                weekStart.format(
                        DateTimeFormatter.ofPattern(
                                "d MMM",
                                new Locale("ru")
                        )
                ) +
                        " — " +
                        weekEnd.format(
                                DateTimeFormatter.ofPattern(
                                        "d MMM",
                                        new Locale("ru")
                                )
                        )
        );

        weekDaysRow.removeAllViews();

        String[] labels = {
                "ПН",
                "ВТ",
                "СР",
                "ЧТ",
                "ПТ",
                "СБ",
                "ВС"
        };

        for (int i = 0; i < 7; i++) {
            LocalDate date =
                    weekStart.plusDays(i);

            TextView day = text(
                    labels[i] +
                            "\n" +
                            date.getDayOfMonth(),
                    12,
                    date.equals(selectedDate)
                            ? TEXT
                            : MUTED,
                    date.equals(selectedDate)
            );

            day.setGravity(Gravity.CENTER);
            day.setPadding(
                    dp(2),
                    dp(8),
                    dp(2),
                    dp(8)
            );

            GradientDrawable bg =
                    new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(
                    date.equals(selectedDate)
                            ? SURFACE_2
                            : Color.TRANSPARENT
            );

            if (date.equals(LocalDate.now())) {
                bg.setStroke(dp(1), ACCENT);
            }

            day.setBackground(bg);
            day.setClickable(true);

            LocalDate chosen = date;
            day.setOnClickListener(v -> {
                selectedDate = chosen;
                refreshWeek(group, start, end);
            });

            weekDaysRow.addView(
                    day,
                    new LinearLayout.LayoutParams(0, -2, 1f)
            );
        }

        dayScheduleText.setText(
                formatDay(
                        selectedDate,
                        start,
                        end,
                        ScheduleStore.forDateAndGroup(
                                this,
                                selectedDate,
                                group
                        )
                )
        );
    }

    private String formatDay(
            LocalDate date,
            LocalDate start,
            LocalDate end,
            List<ScheduleEvent> events
    ) {
        StringBuilder out = new StringBuilder();

        out.append(
                date.format(
                        DateTimeFormatter.ofPattern(
                                "EEEE, d MMMM",
                                new Locale("ru")
                        )
                )
        );

        if (date.isBefore(start) ||
                date.isAfter(end)) {
            return out
                    .append("\n\nДля этой даты расписание не загружено.")
                    .toString();
        }

        if (isSelfStudyOnly(events)) {
            return out
                    .append("\n\nДень самостоятельной работы")
                    .toString();
        }

        if (events.isEmpty()) {
            return out
                    .append("\n\nПар нет.")
                    .toString();
        }

        for (int pair = 1; pair <= 5; pair++) {
            List<ScheduleEvent> pairEvents =
                    eventsForPair(events, pair);

            out.append("\n\n")
                    .append(pairLabel(pair))
                    .append(" — ");

            if (pairEvents.isEmpty()) {
                out.append("нет пары");
                continue;
            }

            for (int i = 0; i < pairEvents.size(); i++) {
                ScheduleEvent event =
                        pairEvents.get(i);

                if (i > 0) {
                    out.append("\n   + ");
                }

                out.append(event.cleanSubject())
                        .append("\n   ")
                        .append(event.start)
                        .append("–")
                        .append(event.end);

                if (!event.room.isBlank()) {
                    out.append(" • каб. ")
                            .append(event.room);
                }

                if (!event.teacher.isBlank()) {
                    out.append("\n   ")
                            .append(event.teacher);
                }
            }
        }

        return out.toString();
    }

    // ---------------------------------------------------------------------
    // Settings screen
    // ---------------------------------------------------------------------

    private void renderSettingsScreen() {
        settingsContainer.removeAllViews();

        AppState state =
                AppState.resolve(this, importing);

        buildScheduleSettingsSection(state);
        addGap(settingsContainer, 14);

        buildNotificationSettingsSection(state);
        addGap(settingsContainer, 14);

        buildSystemSection();
        addGap(settingsContainer, 14);

        buildChecksSection(state);

        refreshNotificationUi();
    }

    private void buildScheduleSettingsSection(
            AppState state
    ) {
        LinearLayout section = settingsSection(
                "РАСПИСАНИЕ"
        );

        List<ScheduleEvent> activeEvents =
                ScheduleStore.loadEvents(this);

        String period = "Не загружен";

        if (!activeEvents.isEmpty()) {
            LocalDate start =
                    NotificationScheduler
                            .scheduleStartDate(activeEvents);
            LocalDate end =
                    NotificationScheduler
                            .scheduleEndDate(activeEvents);

            period =
                    start.format(
                            DateTimeFormatter.ofPattern(
                                    "dd.MM.yyyy"
                            )
                    ) +
                            " — " +
                            end.format(
                                    DateTimeFormatter.ofPattern(
                                            "dd.MM.yyyy"
                                    )
                            );
        }

        TextView fileRow = settingRow(
                "Файл расписания",
                period + "  ›"
        );
        fileRow.setOnClickListener(v ->
                showScheduleCheck()
        );
        section.addView(fileRow);

        addDivider(section);

        String activeGroup = activeGroup();

        TextView groupRow = settingRow(
                "Моя группа",
                (activeGroup.isEmpty()
                        ? "Не выбрана"
                        : activeGroup) + "  ›"
        );
        groupRow.setOnClickListener(v ->
                showActiveGroupPicker()
        );
        section.addView(groupRow);

        addDivider(section);

        TextView importRow = settingRow(
                importing
                        ? "Импортирую PDF…"
                        : "Импортировать новый PDF",
                importing ? "Подожди" : "›"
        );
        importRow.setEnabled(!importing);
        importRow.setAlpha(importing ? 0.45f : 1f);
        importRow.setOnClickListener(v ->
                openPdf()
        );
        section.addView(importRow);

        if (state.hasPendingSchedule) {
            addDivider(section);

            String pendingGroup =
                    ScheduleStore
                            .loadPendingSelectedGroup(this)
                            .trim();

            TextView pending = settingRow(
                    "Новый PDF ждёт подтверждения",
                    pendingGroup.isEmpty()
                            ? "Выбрать группу ›"
                            : pendingGroup + " • проверить ›"
            );

            pending.setOnClickListener(v ->
                    showPendingActions()
            );

            section.addView(pending);
        }

        settingsContainer.addView(section);
    }

    private void buildNotificationSettingsSection(
            AppState state
    ) {
        LinearLayout section =
                settingsSection("УВЕДОМЛЕНИЯ");

        if (!state.hasActiveSchedule) {
            TextView note = text(
                    "Настройки сохранятся и начнут работать после активации расписания.",
                    13,
                    MUTED,
                    false
            );
            note.setPadding(
                    0,
                    0,
                    0,
                    dp(8)
            );
            section.addView(note);
        }

        nextSwitch = switchRow(
                section,
                "Следующая пара",
                NotificationPrefs.load(this).nextEnabled,
                checked ->
                        saveNotificationBoolean(
                                NotificationPrefs.KEY_NEXT_ENABLED,
                                checked
                        )
        );

        nextValue = settingRow(
                "За сколько уведомлять",
                ""
        );
        nextValue.setOnClickListener(v -> {
            NotificationPrefs.Config config =
                    NotificationPrefs.load(this);

            showDurationEditor(
                    "За сколько уведомлять",
                    config.nextOffsetMinutes,
                    24 * 60,
                    value ->
                            saveNotificationInt(
                                    NotificationPrefs.KEY_NEXT_OFFSET,
                                    value
                            )
            );
        });
        section.addView(nextValue);

        addDivider(section);

        repeatSwitch = switchRow(
                section,
                "Повтор",
                NotificationPrefs.load(this).repeatEnabled,
                checked ->
                        saveNotificationBoolean(
                                NotificationPrefs.KEY_REPEAT_ENABLED,
                                checked
                        )
        );

        repeatValue = settingRow(
                "За сколько повторить",
                ""
        );
        repeatValue.setOnClickListener(v -> {
            NotificationPrefs.Config config =
                    NotificationPrefs.load(this);

            showDurationEditor(
                    "За сколько повторить",
                    config.repeatOffsetMinutes,
                    24 * 60,
                    value ->
                            saveNotificationInt(
                                    NotificationPrefs.KEY_REPEAT_OFFSET,
                                    value
                            )
            );
        });
        section.addView(repeatValue);

        addDivider(section);

        longBreakSwitch = switchRow(
                section,
                "Большая перемена",
                NotificationPrefs.load(this).longBreakEnabled,
                checked ->
                        saveNotificationBoolean(
                                NotificationPrefs.KEY_LONG_BREAK_ENABLED,
                                checked
                        )
        );

        longBreakValue = settingRow(
                "Когда напомнить",
                ""
        );
        longBreakValue.setOnClickListener(v ->
                showLongBreakEditor()
        );
        section.addView(longBreakValue);

        addDivider(section);

        tomorrowSwitch = switchRow(
                section,
                "Пары на завтра",
                NotificationPrefs.load(this).tomorrowEnabled,
                checked ->
                        saveNotificationBoolean(
                                NotificationPrefs.KEY_TOMORROW_ENABLED,
                                checked
                        )
        );

        tomorrowValue = settingRow(
                "Время уведомления",
                ""
        );
        tomorrowValue.setOnClickListener(v ->
                showTomorrowTimePicker()
        );
        section.addView(tomorrowValue);

        settingsContainer.addView(section);
    }

    private void buildSystemSection() {
        LinearLayout section =
                settingsSection("СИСТЕМА");

        boolean notificationsGranted =
                Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(
                                Manifest.permission.POST_NOTIFICATIONS
                        ) ==
                                PackageManager.PERMISSION_GRANTED;

        TextView notificationPermission =
                settingRow(
                        "Уведомления Android",
                        notificationsGranted
                                ? "Разрешены"
                                : "Не разрешены ›"
                );

        notificationPermission.setOnClickListener(v -> {
            if (!notificationsGranted &&
                    Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                        new String[]{
                                Manifest.permission.POST_NOTIFICATIONS
                        },
                        REQUEST_NOTIFICATIONS
                );
            }
        });

        section.addView(notificationPermission);
        addDivider(section);

        boolean exactAllowed = exactAlarmsAllowed();

        TextView exactAlarm =
                settingRow(
                        "Точные будильники",
                        exactAllowed
                                ? "Разрешены"
                                : "Разрешить ›"
                );

        exactAlarm.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S &&
                    !exactAlarmsAllowed()) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse(
                                    "package:" +
                                            getPackageName()
                            )
                    );
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        });

        section.addView(exactAlarm);

        settingsContainer.addView(section);
    }

    private void buildChecksSection(
            AppState state
    ) {
        LinearLayout section =
                settingsSection("ПРОВЕРКА");

        TextView scheduleCheck =
                settingRow(
                        "Проверить расписание",
                        "›"
                );
        scheduleCheck.setOnClickListener(v ->
                showScheduleCheck()
        );
        section.addView(scheduleCheck);

        addDivider(section);

        TextView notificationCheck =
                settingRow(
                        "Проверить уведомления",
                        "›"
                );

        notificationCheck.setOnClickListener(v ->
                showNotificationTestPicker()
        );

        section.addView(notificationCheck);

        settingsContainer.addView(section);
    }

    private void refreshNotificationUi() {
        if (nextValue == null) return;

        NotificationPrefs.Config config =
                NotificationPrefs.load(this);

        updatingNotificationUi = true;

        if (nextSwitch != null) {
            nextSwitch.setChecked(
                    config.nextEnabled
            );
        }

        if (repeatSwitch != null) {
            repeatSwitch.setChecked(
                    config.repeatEnabled
            );
        }

        if (longBreakSwitch != null) {
            longBreakSwitch.setChecked(
                    config.longBreakEnabled
            );
        }

        if (tomorrowSwitch != null) {
            tomorrowSwitch.setChecked(
                    config.tomorrowEnabled
            );
        }

        nextValue.setText(
                "За сколько уведомлять\n" +
                        valueText(
                                config.nextOffsetMinutes == 0
                                        ? "В момент начала"
                                        : "За " +
                                        NotificationPrefs
                                                .formatDuration(
                                                        config.nextOffsetMinutes
                                                )
                        )
        );

        repeatValue.setText(
                "За сколько повторить\n" +
                        valueText(
                                config.repeatOffsetMinutes == 0
                                        ? "В момент начала"
                                        : "За " +
                                        NotificationPrefs
                                                .formatDuration(
                                                        config.repeatOffsetMinutes
                                                )
                        )
        );

        longBreakValue.setText(
                "Когда напомнить\n" +
                        valueText(
                                NotificationPrefs
                                        .longBreakSummary(config) +
                                        " • " +
                                        longBreakClock(config)
                        )
        );

        tomorrowValue.setText(
                "Время уведомления\n" +
                        valueText(
                                NotificationPrefs.formatClock(
                                        config.tomorrowHour,
                                        config.tomorrowMinute
                                )
                        )
        );

        nextValue.setAlpha(
                config.nextEnabled ? 1f : 0.45f
        );

        repeatValue.setAlpha(
                config.repeatEnabled ? 1f : 0.45f
        );

        longBreakValue.setAlpha(
                config.longBreakEnabled ? 1f : 0.45f
        );

        tomorrowValue.setAlpha(
                config.tomorrowEnabled ? 1f : 0.45f
        );

        updatingNotificationUi = false;
    }

    private String valueText(String value) {
        return "   " + value + "  ›";
    }

    private String longBreakClock(
            NotificationPrefs.Config config
    ) {
        if (NotificationPrefs.MODE_AFTER_START
                .equals(config.longBreakMode)) {
            return LocalTime.of(12, 10)
                    .plusMinutes(
                            config.longBreakOffsetMinutes
                    )
                    .toString();
        }

        return LocalTime.of(12, 50)
                .minusMinutes(
                        config.longBreakOffsetMinutes
                )
                .toString();
    }

    // ---------------------------------------------------------------------
    // Import / pending / active state
    // ---------------------------------------------------------------------

    private void openPdf() {
        if (importing) return;

        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );
        intent.setType("application/pdf");

        startActivityForResult(
                intent,
                REQUEST_PDF
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != REQUEST_PDF ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try {
            getContentResolver()
                    .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
        } catch (Exception ignored) {
        }

        importing = true;
        currentScreen =
                AppState.Screen.SETTINGS;
        renderAll();

        executor.execute(() -> {
            try (InputStream input =
                         getContentResolver()
                                 .openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "Не удалось открыть PDF"
                    );
                }

                ScheduleParser.ParseResult raw =
                        new ScheduleParser()
                                .parse(input);

                ScheduleNormalizer.Result normalized =
                        new ScheduleNormalizer()
                                .normalize(raw);

                ScheduleValidator.ValidationResult validation =
                        new ScheduleValidator()
                                .validate(
                                        normalized.parseResult
                                );

                ScheduleStore.savePending(
                        this,
                        normalized.parseResult,
                        validation
                );

                runOnUiThread(() -> {
                    importing = false;
                    renderAll();

                    if (validation.canActivate()) {
                        showPendingGroupPicker();
                    } else {
                        showBlockingImportError(
                                validation
                        );
                    }
                });
            } catch (Exception error) {
                ScheduleStore.saveImportError(
                        this,
                        safeError(error)
                );

                runOnUiThread(() -> {
                    importing = false;
                    renderAll();

                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Не удалось импортировать PDF")
                            .setMessage(
                                    safeError(error)
                            )
                            .setPositiveButton(
                                    "Закрыть",
                                    null
                            )
                            .show();
                });
            }
        });
    }

    private void showBlockingImportError(
            ScheduleValidator.ValidationResult validation
    ) {
        StringBuilder text =
                new StringBuilder();

        for (ScheduleValidationIssue issue :
                validation.issues) {
            if (issue.severity !=
                    ScheduleValidationIssue.Severity.ERROR) {
                continue;
            }

            if (text.length() > 0) {
                text.append("\n\n");
            }

            text.append("• ")
                    .append(issue.message);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("PDF нельзя использовать")
                .setMessage(text.toString())
                .setNegativeButton(
                        "Удалить импорт",
                        (dialog, which) -> {
                            ScheduleStore
                                    .discardPending(this);
                            renderAll();
                        }
                )
                .setPositiveButton(
                        "Закрыть",
                        null
                )
                .show();
    }

    private void showPendingGroupPicker() {
        List<String> groups =
                ScheduleStore.loadPendingGroups(this);

        if (groups.isEmpty()) {
            Toast.makeText(
                    this,
                    "В новом PDF не найдено групп.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        String current =
                ScheduleStore
                        .loadPendingSelectedGroup(this)
                        .trim();

        int checked = -1;

        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i)
                    .equalsIgnoreCase(current)) {
                checked = i;
                break;
            }
        }

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Выбери группу")
                        .setSingleChoiceItems(
                                groups.toArray(
                                        new String[0]
                                ),
                                checked,
                                null
                        )
                        .setNegativeButton(
                                "Отмена",
                                null
                        )
                        .setPositiveButton(
                                "Далее",
                                null
                        )
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE
                        )
                        .setOnClickListener(v -> {
                            int position =
                                    dialog
                                            .getListView()
                                            .getCheckedItemPosition();

                            if (position < 0 ||
                                    position >= groups.size()) {
                                Toast.makeText(
                                        this,
                                        "Выбери группу.",
                                        Toast.LENGTH_SHORT
                                ).show();
                                return;
                            }

                            String group =
                                    groups.get(position);

                            ScheduleStore
                                    .setPendingSelectedGroup(
                                            this,
                                            group
                                    );

                            dialog.dismiss();
                            renderSettingsScreen();
                            showPendingReview();
                        })
        );

        dialog.show();
    }

    private void showPendingActions() {
        String group =
                ScheduleStore
                        .loadPendingSelectedGroup(this)
                        .trim();

        String[] items = group.isEmpty()
                ? new String[]{
                "Выбрать группу",
                "Проверить расписание",
                "Отменить новый импорт"
        }
                : new String[]{
                "Проверить и использовать",
                "Сменить группу",
                "Отменить новый импорт"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Новый PDF")
                .setItems(
                        items,
                        (dialog, which) -> {
                            if (group.isEmpty()) {
                                if (which == 0) {
                                    showPendingGroupPicker();
                                } else if (which == 1) {
                                    showScheduleCheck();
                                } else {
                                    confirmDiscardPending();
                                }
                            } else {
                                if (which == 0) {
                                    showPendingReview();
                                } else if (which == 1) {
                                    showPendingGroupPicker();
                                } else {
                                    confirmDiscardPending();
                                }
                            }
                        }
                )
                .setNegativeButton(
                        "Закрыть",
                        null
                )
                .show();
    }

    private void confirmDiscardPending() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Отменить новый импорт?")
                .setMessage(
                        "Активное расписание останется без изменений."
                )
                .setNegativeButton(
                        "Нет",
                        null
                )
                .setPositiveButton(
                        "Отменить импорт",
                        (dialog, which) -> {
                            ScheduleStore
                                    .discardPending(this);
                            renderAll();
                        }
                )
                .show();
    }

    private void showPendingReview() {
        String group =
                ScheduleStore
                        .loadPendingSelectedGroup(this)
                        .trim();

        if (group.isEmpty()) {
            showPendingGroupPicker();
            return;
        }

        List<ScheduleEvent> events =
                ScheduleStore.loadPendingEvents(this);

        List<ScheduleValidationIssue> issues =
                ScheduleStore
                        .loadPendingValidationIssues(this);

        int warnings = 0;

        for (ScheduleValidationIssue issue : issues) {
            if (issue.severity ==
                    ScheduleValidationIssue.Severity.WARNING) {
                warnings++;
            }
        }

        final int warningCount = warnings;

        LinearLayout content =
                new LinearLayout(this);
        content.setOrientation(
                LinearLayout.VERTICAL
        );
        content.setPadding(
                dp(18),
                dp(4),
                dp(18),
                0
        );

        TextView summary = text(
                group +
                        (warningCount > 0
                                ? "\n⚠ " +
                                warningCount +
                                " предупреждений проверки"
                                : "\n✓ Автоматическая проверка пройдена"),
                14,
                warningCount > 0 ? WARN : MUTED,
                false
        );
        summary.setPadding(
                0,
                0,
                0,
                dp(10)
        );
        content.addView(summary);

        List<LocalDate> dates =
                datesForGroup(events, group);

        final int[] index = {
                initialReviewIndex(dates)
        };

        TextView dateTitle = text(
                "",
                16,
                TEXT,
                true
        );
        dateTitle.setGravity(Gravity.CENTER);

        TextView schedule = text(
                "",
                14,
                TEXT,
                false
        );
        schedule.setLineSpacing(
                0f,
                1.1f
        );

        LinearLayout nav =
                new LinearLayout(this);
        nav.setOrientation(
                LinearLayout.HORIZONTAL
        );

        TextView prev = smallAction("‹");
        TextView next = smallAction("›");

        nav.addView(
                prev,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(44)
                )
        );
        nav.addView(
                dateTitle,
                new LinearLayout.LayoutParams(
                        0,
                        dp(44),
                        1f
                )
        );
        nav.addView(
                next,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(44)
                )
        );

        content.addView(nav);
        content.addView(schedule);

        Runnable update = () -> {
            if (dates.isEmpty()) {
                dateTitle.setText("Нет дат");
                schedule.setText(
                        "Для выбранной группы занятия не найдены."
                );
                return;
            }

            LocalDate date =
                    dates.get(index[0]);

            dateTitle.setText(
                    formatRuDate(date)
            );

            schedule.setText(
                    formatDayForEvents(
                            date,
                            ScheduleStore
                                    .pendingForDateAndGroup(
                                            this,
                                            date,
                                            group
                                    )
                    )
            );
        };

        prev.setOnClickListener(v -> {
            if (dates.isEmpty()) return;

            index[0] =
                    Math.max(
                            0,
                            index[0] - 1
                    );
            update.run();
        });

        next.setOnClickListener(v -> {
            if (dates.isEmpty()) return;

            index[0] =
                    Math.min(
                            dates.size() - 1,
                            index[0] + 1
                    );
            update.run();
        });

        summary.setOnClickListener(v -> {
            if (warningCount > 0) {
                showTechnicalDetails(true);
            }
        });

        update.run();

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Проверь расписание")
                        .setView(content)
                        .setNegativeButton(
                                "Сменить группу",
                                null
                        )
                        .setPositiveButton(
                                "Использовать расписание",
                                null
                        )
                        .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(
                            AlertDialog.BUTTON_NEGATIVE
                    )
                    .setOnClickListener(v -> {
                        dialog.dismiss();
                        showPendingGroupPicker();
                    });

            dialog.getButton(
                            AlertDialog.BUTTON_POSITIVE
                    )
                    .setOnClickListener(v -> {
                        if (!ScheduleStore
                                .pendingCanActivate(this)) {
                            Toast.makeText(
                                    this,
                                    "Этот PDF нельзя активировать.",
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }

                        activatePending(group);
                        dialog.dismiss();
                    });
        });

        dialog.show();
    }

    private void activatePending(String group) {
        ScheduleStore.activatePending(this);

        prefs.edit()
                .putString(
                        NotificationScheduler.KEY_GROUP,
                        group
                )
                .apply();

        NotificationScheduler
                .scheduleAll(this);

        currentScreen =
                AppState.Screen.SCHEDULE;

        selectedDate =
                clampDateToActiveRange(
                        LocalDate.now()
                );

        renderAll();

        Toast.makeText(
                this,
                "Расписание активировано.",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showActiveGroupPicker() {
        List<String> groups =
                ScheduleStore.loadGroups(this);

        if (groups.isEmpty()) {
            Toast.makeText(
                    this,
                    "Сначала импортируй расписание.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        String current = activeGroup();
        int checked = -1;

        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i)
                    .equalsIgnoreCase(current)) {
                checked = i;
                break;
            }
        }

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Моя группа")
                        .setSingleChoiceItems(
                                groups.toArray(
                                        new String[0]
                                ),
                                checked,
                                null
                        )
                        .setNegativeButton(
                                "Отмена",
                                null
                        )
                        .setPositiveButton(
                                "Сохранить",
                                null
                        )
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE
                        )
                        .setOnClickListener(v -> {
                            int position =
                                    dialog
                                            .getListView()
                                            .getCheckedItemPosition();

                            if (position < 0 ||
                                    position >= groups.size()) {
                                Toast.makeText(
                                        this,
                                        "Выбери группу.",
                                        Toast.LENGTH_SHORT
                                ).show();
                                return;
                            }

                            prefs.edit()
                                    .putString(
                                            NotificationScheduler.KEY_GROUP,
                                            groups.get(position)
                                    )
                                    .apply();

                            NotificationScheduler
                                    .scheduleAll(this);

                            dialog.dismiss();
                            renderAll();
                        })
        );

        dialog.show();
    }

    // ---------------------------------------------------------------------
    // Schedule check
    // ---------------------------------------------------------------------

    private void showScheduleCheck() {
        if (ScheduleStore.hasPending(this)) {
            String group =
                    ScheduleStore
                            .loadPendingSelectedGroup(this)
                            .trim();

            if (group.isEmpty()) {
                showPendingGroupPicker();
                return;
            }

            showScheduleBrowser(
                    true,
                    group
            );
            return;
        }

        if (!ScheduleStore.hasData(this)) {
            Toast.makeText(
                    this,
                    "Сначала импортируй расписание.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        String group = activeGroup();

        if (group.isEmpty()) {
            showActiveGroupPicker();
            return;
        }

        showScheduleBrowser(
                false,
                group
        );
    }

    private void showScheduleBrowser(
            boolean pending,
            String group
    ) {
        List<ScheduleEvent> events =
                pending
                        ? ScheduleStore.loadPendingEvents(this)
                        : ScheduleStore.loadEvents(this);

        List<LocalDate> dates =
                datesForGroup(events, group);

        if (dates.isEmpty()) {
            Toast.makeText(
                    this,
                    "Для этой группы занятия не найдены.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        final int[] index = {
                initialReviewIndex(dates)
        };

        LinearLayout content =
                new LinearLayout(this);
        content.setOrientation(
                LinearLayout.VERTICAL
        );
        content.setPadding(
                dp(18),
                dp(4),
                dp(18),
                0
        );

        TextView groupTitle = text(
                group,
                15,
                MUTED,
                true
        );
        groupTitle.setPadding(
                0,
                0,
                0,
                dp(8)
        );
        content.addView(groupTitle);

        LinearLayout nav =
                new LinearLayout(this);
        nav.setOrientation(
                LinearLayout.HORIZONTAL
        );

        TextView prev = smallAction("‹");
        TextView dateTitle = text(
                "",
                16,
                TEXT,
                true
        );
        dateTitle.setGravity(Gravity.CENTER);
        TextView next = smallAction("›");

        nav.addView(
                prev,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(44)
                )
        );
        nav.addView(
                dateTitle,
                new LinearLayout.LayoutParams(
                        0,
                        dp(44),
                        1f
                )
        );
        nav.addView(
                next,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(44)
                )
        );

        content.addView(nav);

        TextView body = text(
                "",
                14,
                TEXT,
                false
        );
        content.addView(body);

        List<ScheduleValidationIssue> issues =
                pending
                        ? ScheduleStore
                        .loadPendingValidationIssues(this)
                        : ScheduleStore
                        .loadActiveValidationIssues(this);

        int warningCount = 0;

        for (ScheduleValidationIssue issue : issues) {
            if (issue.severity ==
                    ScheduleValidationIssue.Severity.WARNING) {
                warningCount++;
            }
        }

        if (warningCount > 0) {
            TextView details = text(
                    "⚠ Некоторые элементы были восстановлены автоматически • подробнее",
                    12,
                    WARN,
                    false
            );
            details.setPadding(
                    0,
                    dp(12),
                    0,
                    0
            );
            details.setOnClickListener(v ->
                    showTechnicalDetails(pending)
            );
            content.addView(details);
        }

        Runnable update = () -> {
            LocalDate date =
                    dates.get(index[0]);

            dateTitle.setText(
                    formatRuDate(date)
            );

            List<ScheduleEvent> day =
                    pending
                            ? ScheduleStore
                            .pendingForDateAndGroup(
                                    this,
                                    date,
                                    group
                            )
                            : ScheduleStore
                            .forDateAndGroup(
                                    this,
                                    date,
                                    group
                            );

            body.setText(
                    formatDayForEvents(
                            date,
                            day
                    )
            );
        };

        prev.setOnClickListener(v -> {
            index[0] =
                    Math.max(
                            0,
                            index[0] - 1
                    );
            update.run();
        });

        next.setOnClickListener(v -> {
            index[0] =
                    Math.min(
                            dates.size() - 1,
                            index[0] + 1
                    );
            update.run();
        });

        update.run();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Проверить расписание")
                .setView(content)
                .setPositiveButton(
                        "Закрыть",
                        null
                )
                .show();
    }

    private void showTechnicalDetails(
            boolean pending
    ) {
        List<ScheduleValidationIssue> issues =
                pending
                        ? ScheduleStore
                        .loadPendingValidationIssues(this)
                        : ScheduleStore
                        .loadActiveValidationIssues(this);

        List<String> parserWarnings =
                pending
                        ? ScheduleStore
                        .loadPendingWarnings(this)
                        : ScheduleStore
                        .loadWarnings(this);

        List<String> diagnostics =
                pending
                        ? ScheduleStore
                        .loadPendingDiagnostics(this)
                        : ScheduleStore
                        .loadDiagnostics(this);

        StringBuilder out =
                new StringBuilder();

        for (ScheduleValidationIssue issue : issues) {
            if (out.length() > 0) {
                out.append("\n");
            }

            out.append(
                    issue.severity ==
                            ScheduleValidationIssue.Severity.ERROR
                            ? "✕ "
                            : "⚠ "
            ).append(issue.message);
        }

        if (!parserWarnings.isEmpty()) {
            out.append("\n\nВосстановление:");
            for (String warning : parserWarnings) {
                out.append("\n• ")
                        .append(warning);
            }
        }

        if (!diagnostics.isEmpty()) {
            out.append("\n\nТехническая информация:");
            for (String diagnostic : diagnostics) {
                out.append("\n• ")
                        .append(diagnostic);
            }
        }

        if (out.length() == 0) {
            out.append(
                    "Дополнительных замечаний нет."
            );
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Подробнее")
                .setMessage(out.toString())
                .setPositiveButton(
                        "Закрыть",
                        null
                )
                .show();
    }

    // ---------------------------------------------------------------------
    // Notification settings
    // ---------------------------------------------------------------------

    private void saveNotificationBoolean(
            String key,
            boolean value
    ) {
        if (updatingNotificationUi) return;

        NotificationPrefs.load(this);

        boolean old =
                prefs.getBoolean(key, false);

        prefs.edit()
                .putBoolean(key, value)
                .apply();

        String error =
                NotificationPrefs.validate(
                        NotificationPrefs.load(this)
                );

        if (!error.isEmpty()) {
            prefs.edit()
                    .putBoolean(key, old)
                    .apply();

            Toast.makeText(
                    this,
                    error,
                    Toast.LENGTH_LONG
            ).show();
        } else if (ScheduleStore.hasData(this)) {
            NotificationScheduler
                    .scheduleAll(this);
        }

        renderSettingsScreen();
    }

    private void saveNotificationInt(
            String key,
            int value
    ) {
        NotificationPrefs.load(this);

        int old =
                prefs.getInt(key, 0);

        prefs.edit()
                .putInt(key, value)
                .apply();

        String error =
                NotificationPrefs.validate(
                        NotificationPrefs.load(this)
                );

        if (!error.isEmpty()) {
            prefs.edit()
                    .putInt(key, old)
                    .apply();

            Toast.makeText(
                    this,
                    error,
                    Toast.LENGTH_LONG
            ).show();
        } else if (ScheduleStore.hasData(this)) {
            NotificationScheduler
                    .scheduleAll(this);
        }

        renderSettingsScreen();
    }

    private void showDurationEditor(
            String title,
            int currentMinutes,
            int maxMinutes,
            IntConsumer onSave
    ) {
        LinearLayout content =
                new LinearLayout(this);
        content.setOrientation(
                LinearLayout.VERTICAL
        );
        content.setPadding(
                dp(18),
                dp(4),
                dp(18),
                0
        );

        TextView help = text(
                "Можно ввести любое значение.",
                13,
                MUTED,
                false
        );
        help.setPadding(
                0,
                0,
                0,
                dp(10)
        );
        content.addView(help);

        LinearLayout fields =
                new LinearLayout(this);
        fields.setOrientation(
                LinearLayout.HORIZONTAL
        );

        EditText hours =
                numberField(
                        "Часы",
                        currentMinutes / 60
                );

        EditText minutes =
                numberField(
                        "Минуты",
                        currentMinutes % 60
                );

        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                );
        hp.setMarginEnd(dp(5));

        LinearLayout.LayoutParams mp =
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f
                );
        mp.setMarginStart(dp(5));

        fields.addView(hours, hp);
        fields.addView(minutes, mp);
        content.addView(fields);

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setView(content)
                        .setNegativeButton(
                                "Отмена",
                                null
                        )
                        .setPositiveButton(
                                "Сохранить",
                                null
                        )
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE
                        )
                        .setOnClickListener(v -> {
                            Integer h =
                                    parseInt(hours);
                            Integer m =
                                    parseInt(minutes);

                            if (h == null ||
                                    m == null ||
                                    h < 0 ||
                                    m < 0 ||
                                    m > 59) {
                                Toast.makeText(
                                        this,
                                        "Укажи часы и минуты корректно.",
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }

                            int total =
                                    h * 60 + m;

                            if (total < 0 ||
                                    total > maxMinutes) {
                                Toast.makeText(
                                        this,
                                        "Слишком большое значение.",
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }

                            onSave.accept(total);
                            dialog.dismiss();
                        })
        );

        dialog.show();
    }

    private void showLongBreakEditor() {
        NotificationPrefs.Config config =
                NotificationPrefs.load(this);

        LinearLayout content =
                new LinearLayout(this);
        content.setOrientation(
                LinearLayout.VERTICAL
        );
        content.setPadding(
                dp(18),
                dp(4),
                dp(18),
                0
        );

        RadioGroup modes =
                new RadioGroup(this);
        modes.setOrientation(
                RadioGroup.VERTICAL
        );

        RadioButton before =
                new RadioButton(this);
        before.setText("До 3-й пары");
        before.setTextColor(TEXT);
        before.setId(View.generateViewId());

        RadioButton after =
                new RadioButton(this);
        after.setText(
                "После начала большой перемены"
        );
        after.setTextColor(TEXT);
        after.setId(View.generateViewId());

        modes.addView(before);
        modes.addView(after);

        modes.check(
                NotificationPrefs.MODE_AFTER_START
                        .equals(config.longBreakMode)
                        ? after.getId()
                        : before.getId()
        );

        content.addView(modes);

        EditText minutes =
                numberField(
                        "Минуты (0–40)",
                        config.longBreakOffsetMinutes
                );
        content.addView(minutes);

        TextView preview = text(
                "",
                13,
                MUTED,
                false
        );
        preview.setPadding(
                0,
                dp(8),
                0,
                0
        );
        content.addView(preview);

        Runnable updatePreview = () -> {
            Integer value =
                    parseInt(minutes);

            if (value == null) value = 0;

            value = Math.max(
                    0,
                    Math.min(40, value)
            );

            boolean afterMode =
                    modes.getCheckedRadioButtonId() ==
                            after.getId();

            LocalTime when =
                    afterMode
                            ? LocalTime.of(12, 10)
                            .plusMinutes(value)
                            : LocalTime.of(12, 50)
                            .minusMinutes(value);

            preview.setText(
                    "Большая перемена: 12:10–12:50\nУведомление придёт в " +
                            when
            );
        };

        minutes.addTextChangedListener(
                new SimpleTextWatcher(
                        updatePreview
                )
        );

        modes.setOnCheckedChangeListener(
                (group, checkedId) ->
                        updatePreview.run()
        );

        updatePreview.run();

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Большая перемена")
                        .setView(content)
                        .setNegativeButton(
                                "Отмена",
                                null
                        )
                        .setPositiveButton(
                                "Сохранить",
                                null
                        )
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE
                        )
                        .setOnClickListener(v -> {
                            Integer offset =
                                    parseInt(minutes);

                            if (offset == null ||
                                    offset < 0 ||
                                    offset > 40) {
                                Toast.makeText(
                                        this,
                                        "Допустимо от 0 до 40 минут.",
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }

                            String mode =
                                    modes.getCheckedRadioButtonId() ==
                                            after.getId()
                                            ? NotificationPrefs.MODE_AFTER_START
                                            : NotificationPrefs.MODE_BEFORE_NEXT;

                            prefs.edit()
                                    .putString(
                                            NotificationPrefs.KEY_LONG_BREAK_MODE,
                                            mode
                                    )
                                    .putInt(
                                            NotificationPrefs.KEY_LONG_BREAK_OFFSET,
                                            offset
                                    )
                                    .apply();

                            if (ScheduleStore.hasData(this)) {
                                NotificationScheduler
                                        .scheduleAll(this);
                            }

                            dialog.dismiss();
                            renderSettingsScreen();
                        })
        );

        dialog.show();
    }

    private void showTomorrowTimePicker() {
        NotificationPrefs.Config config =
                NotificationPrefs.load(this);

        TimePickerDialog dialog =
                new TimePickerDialog(
                        this,
                        (view, hour, minute) -> {
                            prefs.edit()
                                    .putInt(
                                            NotificationPrefs.KEY_TOMORROW_HOUR,
                                            hour
                                    )
                                    .putInt(
                                            NotificationPrefs.KEY_TOMORROW_MINUTE,
                                            minute
                                    )
                                    .apply();

                            if (ScheduleStore.hasData(this)) {
                                NotificationScheduler
                                        .scheduleAll(this);
                            }

                            renderSettingsScreen();
                        },
                        config.tomorrowHour,
                        config.tomorrowMinute,
                        true
                );

        dialog.setTitle(
                "Пары на завтра"
        );
        dialog.show();
    }

    // ---------------------------------------------------------------------
    // Notification tests
    // ---------------------------------------------------------------------

    private void showNotificationTestPicker() {
        if (!ScheduleStore.hasData(this) ||
                activeGroup().isEmpty()) {
            Toast.makeText(
                    this,
                    "Для теста сначала активируй расписание и выбери группу.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        String[] names = {
                "Следующая пара",
                "Повтор",
                "Большая перемена",
                "Пары на завтра"
        };

        String[] kinds = {
                NotificationScheduler.KIND_NEXT,
                NotificationScheduler.KIND_REPEAT,
                NotificationScheduler.KIND_LONG_BREAK,
                "tomorrow"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Какое уведомление проверить?")
                .setItems(
                        names,
                        (dialog, which) ->
                                showNotificationTestPreview(
                                        kinds[which]
                                )
                )
                .setNegativeButton(
                        "Закрыть",
                        null
                )
                .show();
    }

    private void showNotificationTestPreview(
            String kind
    ) {
        if (!notificationPermissionGranted()) {
            pendingTestKind = kind;
            requestNotificationPermission();
            return;
        }

        if ("tomorrow".equals(kind)) {
            LocalDate tomorrow =
                    LocalDate.now().plusDays(1);

            List<ScheduleEvent> events =
                    ScheduleStore
                            .forDateAndGroup(
                                    this,
                                    tomorrow,
                                    activeGroup()
                            );

            String text =
                    events.isEmpty()
                            ? "На завтра занятий нет."
                            : "Будут использованы реальные занятия группы " +
                            activeGroup() +
                            " на " +
                            formatRuDate(tomorrow) +
                            ".";

            showTestDispatchDialog(
                    kind,
                    null,
                    text
            );
            return;
        }

        ScheduleEvent event =
                findTestEvent(kind);

        if (event == null) {
            Toast.makeText(
                    this,
                    "В расписании нет подходящего занятия для этого теста.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        NotificationPrefs.Config config =
                NotificationPrefs.load(this);

        int offset;

        if (NotificationScheduler.KIND_REPEAT
                .equals(kind)) {
            offset = config.repeatOffsetMinutes;
        } else if (
                NotificationScheduler.KIND_LONG_BREAK
                        .equals(kind)
        ) {
            offset = config.longBreakOffsetMinutes;
        } else {
            offset = config.nextOffsetMinutes;
        }

        NotificationRenderer.Rendered rendered =
                new NotificationRenderer()
                        .renderClass(
                                event,
                                kind,
                                offset
                        );

        showTestDispatchDialog(
                kind,
                event,
                rendered.title +
                        "\n\n" +
                        rendered.body
        );
    }

    private void showTestDispatchDialog(
            String kind,
            ScheduleEvent event,
            String preview
    ) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Проверка уведомления")
                .setMessage(preview)
                .setNegativeButton(
                        "Закрыть",
                        null
                )
                .setNeutralButton(
                        "Через 1 минуту",
                        (dialog, which) -> {
                            NotificationScheduler
                                    .scheduleTestAfterOneMinute(
                                            this,
                                            kind,
                                            event
                                    );

                            Toast.makeText(
                                    this,
                                    "Тест запланирован.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                )
                .setPositiveButton(
                        "Показать сейчас",
                        (dialog, which) ->
                                dispatchTestNotification(
                                        kind,
                                        event
                                )
                )
                .show();
    }

    private void dispatchTestNotification(
            String kind,
            ScheduleEvent event
    ) {
        Intent intent =
                new Intent(
                        this,
                        NotificationReceiver.class
                )
                        .setAction(
                                NotificationScheduler.ACTION_TEST
                        )
                        .putExtra(
                                NotificationScheduler.EXTRA_KIND,
                                kind
                        )
                        .putExtra(
                                NotificationScheduler.EXTRA_EVENT_KEY,
                                event == null
                                        ? ""
                                        : event.stableKey()
                        );

        sendBroadcast(intent);
    }

    private ScheduleEvent findTestEvent(
            String kind
    ) {
        String group = activeGroup();

        List<ScheduleEvent> events =
                new ArrayList<>();

        for (ScheduleEvent event :
                ScheduleStore.loadEvents(this)) {
            if (!event.belongsTo(group)) continue;
            if (isSelfStudy(event)) continue;
            events.add(event);
        }

        events.sort(
                Comparator.comparing(
                        ScheduleEvent::startDateTime
                )
        );

        if (NotificationScheduler.KIND_LONG_BREAK
                .equals(kind)) {
            for (ScheduleEvent third : events) {
                if (third.pairNumber != 3 ||
                        !third.start.equals(
                                LocalTime.of(12, 50)
                        )) {
                    continue;
                }

                for (ScheduleEvent second : events) {
                    if (second.date.equals(third.date) &&
                            second.pairNumber == 2 &&
                            second.end.equals(
                                    LocalTime.of(12, 10)
                            )) {
                        return third;
                    }
                }
            }

            return null;
        }

        LocalDateTime now =
                LocalDateTime.now();

        for (ScheduleEvent event : events) {
            if (!event.startDateTime()
                    .isBefore(now)) {
                return event;
            }
        }

        return events.isEmpty()
                ? null
                : events.get(events.size() - 1);
    }

    // ---------------------------------------------------------------------
    // Clock
    // ---------------------------------------------------------------------

    private void startUiClock() {
        uiHandler.removeCallbacks(uiTick);
        uiHandler.postDelayed(
                uiTick,
                30_000L
        );
    }

    private void stopUiClock() {
        uiHandler.removeCallbacks(uiTick);
    }

    private void refreshTimeSensitiveState() {
        LocalDate today = LocalDate.now();

        if (!today.equals(lastClockDate)) {
            boolean followedToday =
                    selectedDate.equals(lastClockDate);

            lastClockDate = today;

            if (followedToday) {
                selectedDate =
                        clampDateToActiveRange(today);
            }

            if (currentScreen ==
                    AppState.Screen.SCHEDULE) {
                renderScheduleScreen();
            }
            return;
        }

        if (currentScreen !=
                AppState.Screen.SCHEDULE) {
            return;
        }

        if (!ScheduleStore.hasData(this) ||
                activeGroup().isEmpty() ||
                currentStatusCard == null) {
            return;
        }

        List<ScheduleEvent> events =
                ScheduleStore.loadEvents(this);

        if (events.isEmpty()) return;

        updateCurrentStatus(
                events,
                activeGroup(),
                NotificationScheduler
                        .scheduleStartDate(events),
                NotificationScheduler
                        .scheduleEndDate(events)
        );
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                !notificationPermissionGranted()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    REQUEST_NOTIFICATIONS
            );
        }
    }

    private boolean notificationPermissionGranted() {
        return Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    private boolean exactAlarmsAllowed() {
        if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.S) {
            return true;
        }

        AlarmManager manager =
                (AlarmManager) getSystemService(
                        ALARM_SERVICE
                );

        return manager.canScheduleExactAlarms();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode !=
                REQUEST_NOTIFICATIONS) {
            return;
        }

        if (ScheduleStore.hasData(this)) {
            NotificationScheduler
                    .scheduleAll(this);
        }

        boolean granted =
                grantResults.length > 0 &&
                        grantResults[0] ==
                                PackageManager.PERMISSION_GRANTED;

        if (granted &&
                pendingTestKind != null) {
            String kind = pendingTestKind;
            pendingTestKind = null;
            showNotificationTestPreview(kind);
        } else {
            pendingTestKind = null;
        }

        if (currentScreen ==
                AppState.Screen.SETTINGS) {
            renderSettingsScreen();
        }
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

    private LinearLayout settingsSection(
            String title
    ) {
        LinearLayout section = surface();

        TextView header = text(
                title,
                12,
                MUTED,
                true
        );
        header.setPadding(
                0,
                0,
                0,
                dp(6)
        );

        section.addView(header);
        return section;
    }

    private LinearLayout surface() {
        LinearLayout layout =
                new LinearLayout(this);
        layout.setOrientation(
                LinearLayout.VERTICAL
        );
        layout.setPadding(
                dp(15),
                dp(14),
                dp(15),
                dp(14)
        );

        GradientDrawable bg =
                new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), BORDER);

        layout.setBackground(bg);
        return layout;
    }

    private TextView settingRow(
            String title,
            String value
    ) {
        TextView row = text(
                title +
                        "\n   " +
                        value,
                15,
                TEXT,
                false
        );

        row.setPadding(
                dp(2),
                dp(10),
                dp(2),
                dp(10)
        );

        row.setClickable(true);
        row.setFocusable(true);

        return row;
    }

    private MaterialSwitch switchRow(
            LinearLayout parent,
            String title,
            boolean checked,
            java.util.function.Consumer<Boolean> listener
    ) {
        MaterialSwitch row = new MaterialSwitch(this);
        row.setText(title);
        row.setTextColor(TEXT);
        row.setTextSize(15);
        row.setPadding(
                dp(2),
                dp(10),
                dp(2),
                dp(8)
        );
        row.setChecked(checked);

        row.setOnCheckedChangeListener(
                (buttonView, value) -> {
                    if (!updatingNotificationUi) {
                        listener.accept(value);
                    }
                }
        );

        parent.addView(
                row,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        return row;
    }

    private TextView infoCard(String value) {
        TextView card = text(
                value,
                16,
                TEXT,
                false
        );

        card.setPadding(
                dp(16),
                dp(15),
                dp(16),
                dp(15)
        );

        GradientDrawable bg =
                new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), BORDER);

        card.setBackground(bg);

        return card;
    }

    private TextView smallAction(String label) {
        TextView view = text(
                label,
                24,
                TEXT,
                false
        );

        view.setGravity(Gravity.CENTER);
        view.setClickable(true);

        return view;
    }

    private TextView text(
            String value,
            int sp,
            int color,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);

        if (bold) {
            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    private void addDivider(
            LinearLayout parent
    ) {
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(1)
                );

        parent.addView(divider, params);
    }

    private void addGap(
            LinearLayout parent,
            int value
    ) {
        View gap = new View(this);
        parent.addView(
                gap,
                new LinearLayout.LayoutParams(
                        1,
                        dp(value)
                )
        );
    }

    private EditText numberField(
            String hint,
            int value
    ) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(
                String.valueOf(value)
        );
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setInputType(
                InputType.TYPE_CLASS_NUMBER
        );
        field.setSingleLine(true);

        return field;
    }

    private Integer parseInt(
            EditText field
    ) {
        String raw =
                field.getText()
                        .toString()
                        .trim();

        if (raw.isEmpty()) return null;

        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<ScheduleEvent> eventsForPair(
            List<ScheduleEvent> events,
            int pair
    ) {
        List<ScheduleEvent> result =
                new ArrayList<>();

        for (ScheduleEvent event : events) {
            if (event.pairNumber == pair &&
                    !isSelfStudy(event)) {
                result.add(event);
            }
        }

        result.sort(
                Comparator.comparing(
                        event -> event.start
                )
        );

        return result;
    }

    private boolean isSelfStudyOnly(
            List<ScheduleEvent> events
    ) {
        if (events.isEmpty()) return false;

        for (ScheduleEvent event : events) {
            if (!isSelfStudy(event)) {
                return false;
            }
        }

        return true;
    }

    private boolean isSelfStudy(
            ScheduleEvent event
    ) {
        return event.cleanSubject()
                .toLowerCase(Locale.ROOT)
                .contains(
                        "самостоятельной работы"
                );
    }

    private String pairLabel(int pair) {
        switch (pair) {
            case 1:
                return "1-я пара";
            case 2:
                return "2-я пара";
            case 3:
                return "3-я пара";
            case 4:
                return "4-я пара";
            default:
                return "5-я пара";
        }
    }

    private String formatDayForEvents(
            LocalDate date,
            List<ScheduleEvent> events
    ) {
        if (isSelfStudyOnly(events)) {
            return "День самостоятельной работы";
        }

        StringBuilder out =
                new StringBuilder();

        for (int pair = 1; pair <= 5; pair++) {
            if (out.length() > 0) {
                out.append("\n\n");
            }

            List<ScheduleEvent> pairEvents =
                    eventsForPair(events, pair);

            out.append(pairLabel(pair))
                    .append(" — ");

            if (pairEvents.isEmpty()) {
                out.append("нет пары");
                continue;
            }

            for (int i = 0; i < pairEvents.size(); i++) {
                ScheduleEvent event =
                        pairEvents.get(i);

                if (i > 0) {
                    out.append("\n   + ");
                }

                out.append(event.cleanSubject())
                        .append("\n   ")
                        .append(event.start)
                        .append("–")
                        .append(event.end);

                if (!event.room.isBlank()) {
                    out.append(" • каб. ")
                            .append(event.room);
                }

                if (!event.teacher.isBlank()) {
                    out.append("\n   ")
                            .append(event.teacher);
                }
            }
        }

        return out.toString();
    }

    private List<LocalDate> datesForGroup(
            List<ScheduleEvent> events,
            String group
    ) {
        List<LocalDate> dates =
                new ArrayList<>();

        for (ScheduleEvent event : events) {
            if (!event.belongsTo(group)) {
                continue;
            }

            if (!dates.contains(event.date)) {
                dates.add(event.date);
            }
        }

        dates.sort(LocalDate::compareTo);
        return dates;
    }

    private int initialReviewIndex(
            List<LocalDate> dates
    ) {
        if (dates.isEmpty()) return 0;

        LocalDate today = LocalDate.now();

        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(today)) {
                return i;
            }
        }

        return dates.size() - 1;
    }

    private LocalDate clampDateToActiveRange(
            LocalDate value
    ) {
        List<ScheduleEvent> events =
                ScheduleStore.loadEvents(this);

        if (events.isEmpty()) return value;

        LocalDate start =
                NotificationScheduler
                        .scheduleStartDate(events);

        LocalDate end =
                NotificationScheduler
                        .scheduleEndDate(events);

        if (value.isBefore(start)) return start;
        if (value.isAfter(end)) return end;

        return value;
    }

    private String activeGroup() {
        String group =
                prefs.getString(
                                NotificationScheduler.KEY_GROUP,
                                ""
                        )
                        .trim();

        if (group.isEmpty()) return "";

        for (String available :
                ScheduleStore.loadGroups(this)) {
            if (available.equalsIgnoreCase(group)) {
                return available;
            }
        }

        return "";
    }

    private String formatRuDate(
            LocalDate date
    ) {
        return date.format(
                DateTimeFormatter.ofPattern(
                        "d MMMM",
                        new Locale("ru")
                )
        );
    }

    private String safeError(Throwable error) {
        String message = error.getMessage();

        if (message == null ||
                message.isBlank()) {
            return error
                    .getClass()
                    .getSimpleName();
        }

        if (message.length() > 260) {
            return message.substring(0, 260) +
                    "…";
        }

        return message;
    }

    private int dp(int value) {
        return Math.round(
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density
        );
    }

    private static final class SimpleTextWatcher
            implements TextWatcher {
        private final Runnable callback;

        SimpleTextWatcher(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(
                CharSequence s,
                int start,
                int count,
                int after
        ) {}

        @Override
        public void onTextChanged(
                CharSequence s,
                int start,
                int before,
                int count
        ) {
            callback.run();
        }

        @Override
        public void afterTextChanged(
                Editable s
        ) {}
    }
}
