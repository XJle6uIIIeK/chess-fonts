package com.n3k0.schedule;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NotificationScheduler {
    public static final String SETTINGS = "app_settings";
    public static final String KEY_GROUP = "group";

    // Legacy keys are intentionally kept for migration from older builds.
    public static final String KEY_REMINDER_10 = "reminder_10";
    public static final String KEY_TOMORROW_20 = "tomorrow_20";

    private static final String KEY_SCHEDULED_IDS = "scheduled_alarm_ids";

    public static final String CHANNEL_ID = "schedule_reminders";

    public static final String ACTION_CLASS =
            "com.n3k0.schedule.CLASS_REMINDER";
    public static final String ACTION_TOMORROW =
            "com.n3k0.schedule.TOMORROW_SUMMARY";
    public static final String ACTION_REFRESH =
            "com.n3k0.schedule.REFRESH_PLAN";
    public static final String ACTION_EXPIRY =
            "com.n3k0.schedule.SCHEDULE_EXPIRY";
    public static final String ACTION_TEST =
            "com.n3k0.schedule.TEST_NOTIFICATION";

    public static final String EXTRA_KIND = "reminder_kind";
    public static final String EXTRA_OFFSET = "reminder_offset";
    public static final String EXTRA_EVENT_KEY = "event_key";
    public static final String EXTRA_END_DATE = "schedule_end_date";
    public static final String EXTRA_MERGED = "merged_notification";

    public static final String KIND_NEXT = "next";
    public static final String KIND_REPEAT = "repeat";
    public static final String KIND_LONG_BREAK = "long_break";

    public static final int TOMORROW_REQUEST = 900_001;
    public static final int REFRESH_REQUEST = 900_002;
    public static final int EXPIRY_REQUEST = 900_003;
    public static final int TEST_DELAY_REQUEST = 900_004;

    private static final int ROLLING_WINDOW_DAYS = 14;

    private NotificationScheduler() {}

    public static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    context.getSystemService(NotificationManager.class);

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Расписание занятий",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(
                    "Напоминания о парах, большой перемене и расписании на завтра"
            );
            manager.createNotificationChannel(channel);
        }
    }

    public static void scheduleAll(Context context) {
        createChannel(context);
        cancelStoredAlarms(context);

        String group = settings(context)
                .getString(KEY_GROUP, "")
                .trim();

        if (group.isEmpty() ||
                !ScheduleStore.hasData(context) ||
                !ScheduleStore.isActiveParserCurrent(context)) {
            return;
        }

        List<ScheduleEvent> allEvents = ScheduleStore.loadEvents(context);
        if (allEvents.isEmpty()) return;

        NotificationPrefs.Config config = NotificationPrefs.load(context);
        LocalDateTime now = LocalDateTime.now();

        LocalDate endDate = scheduleEndDate(allEvents);
        LocalDateTime rollingEnd = now.plusDays(ROLLING_WINDOW_DAYS);
        LocalDateTime scheduleEnd = endDate.atTime(23, 59, 59);

        LocalDateTime until =
                rollingEnd.isBefore(scheduleEnd) ? rollingEnd : scheduleEnd;

        NotificationPlanner planner = new NotificationPlanner();
        List<PlannedNotification> plan = planner.plan(
                allEvents,
                group,
                config,
                now,
                until
        );

        List<Integer> ids = new ArrayList<>();

        for (PlannedNotification notification : plan) {
            int request = requestCode(notification);

            Intent intent = new Intent(context, NotificationReceiver.class)
                    .setAction(ACTION_CLASS)
                    .putExtra(EXTRA_EVENT_KEY, notification.event.stableKey())
                    .putExtra(EXTRA_KIND, notification.kind())
                    .putExtra(EXTRA_OFFSET, notification.offsetMinutes)
                    .putExtra(EXTRA_MERGED, notification.isMerged());

            schedule(
                    context,
                    request,
                    notification.triggerAt,
                    intent
            );
            ids.add(request);
        }

        if (config.tomorrowEnabled &&
                scheduleTomorrowSummary(context, now, endDate)) {
            ids.add(TOMORROW_REQUEST);
        }

        if (scheduleRefresh(context, now, endDate)) {
            ids.add(REFRESH_REQUEST);
        }

        if (scheduleExpiryWarning(context, now, endDate)) {
            ids.add(EXPIRY_REQUEST);
        }

        persistIds(context, ids);
    }

    public static boolean scheduleTomorrowSummary(Context context) {
        List<ScheduleEvent> events = ScheduleStore.loadEvents(context);
        if (events.isEmpty()) return false;

        return scheduleTomorrowSummary(
                context,
                LocalDateTime.now(),
                scheduleEndDate(events)
        );
    }

    private static boolean scheduleTomorrowSummary(
            Context context,
            LocalDateTime now,
            LocalDate endDate
    ) {
        NotificationPrefs.Config config = NotificationPrefs.load(context);
        if (!config.tomorrowEnabled) return false;

        LocalDateTime when = now.toLocalDate()
                .atTime(config.tomorrowHour, config.tomorrowMinute);

        if (!when.isAfter(now)) {
            when = when.plusDays(1);
        }

        // The notification fired on date D describes D+1.
        LocalDate targetDate = when.toLocalDate().plusDays(1);
        if (targetDate.isAfter(endDate)) {
            return false;
        }

        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_TOMORROW);

        schedule(context, TOMORROW_REQUEST, when, intent);
        return true;
    }

    private static boolean scheduleRefresh(
            Context context,
            LocalDateTime now,
            LocalDate endDate
    ) {
        if (now.toLocalDate().isAfter(endDate)) return false;

        LocalDateTime when = now.toLocalDate().atTime(3, 0);
        if (!when.isAfter(now)) when = when.plusDays(1);

        if (when.toLocalDate().isAfter(endDate)) return false;

        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_REFRESH);

        schedule(context, REFRESH_REQUEST, when, intent);
        return true;
    }

    private static boolean scheduleExpiryWarning(
            Context context,
            LocalDateTime now,
            LocalDate endDate
    ) {
        LocalDateTime when = endDate
                .minusDays(2)
                .atTime(18, 0);

        if (!when.isAfter(now)) return false;

        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_EXPIRY)
                .putExtra(EXTRA_END_DATE, endDate.toString());

        schedule(context, EXPIRY_REQUEST, when, intent);
        return true;
    }

    public static void scheduleTestAfterOneMinute(
            Context context,
            String kind,
            ScheduleEvent event
    ) {
        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_TEST)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(
                        EXTRA_EVENT_KEY,
                        event == null ? "" : event.stableKey()
                )
                .putExtra("delayed_test", true);

        schedule(
                context,
                TEST_DELAY_REQUEST,
                LocalDateTime.now().plusMinutes(1),
                intent
        );
    }

    private static int requestCode(PlannedNotification notification) {
        String key =
                notification.kind() +
                        "|" +
                        notification.event.stableKey() +
                        "|" +
                        notification.triggerAt;

        return 100_000 + Math.floorMod(key.hashCode(), 700_000);
    }

    private static void schedule(
            Context context,
            int requestCode,
            LocalDateTime when,
            Intent intent
    ) {
        AlarmManager alarm =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long millis = when
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    millis,
                    pendingIntent
            );
        } else {
            alarm.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    millis,
                    pendingIntent
            );
        }
    }

    private static void cancelStoredAlarms(Context context) {
        SharedPreferences prefs = settings(context);
        Set<String> raw =
                prefs.getStringSet(KEY_SCHEDULED_IDS, new HashSet<>());

        AlarmManager alarm =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        String[] actions = new String[]{
                ACTION_CLASS,
                ACTION_TOMORROW,
                ACTION_REFRESH,
                ACTION_EXPIRY,
                ACTION_TEST
        };

        for (String value : raw) {
            try {
                int id = Integer.parseInt(value);

                for (String action : actions) {
                    Intent intent = new Intent(
                            context,
                            NotificationReceiver.class
                    ).setAction(action);

                    PendingIntent pendingIntent =
                            PendingIntent.getBroadcast(
                                    context,
                                    id,
                                    intent,
                                    PendingIntent.FLAG_NO_CREATE |
                                            PendingIntent.FLAG_IMMUTABLE
                            );

                    if (pendingIntent != null) {
                        alarm.cancel(pendingIntent);
                        pendingIntent.cancel();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        prefs.edit().remove(KEY_SCHEDULED_IDS).apply();
    }

    private static void persistIds(
            Context context,
            List<Integer> ids
    ) {
        Set<String> values = new HashSet<>();
        for (int id : ids) values.add(String.valueOf(id));

        settings(context)
                .edit()
                .putStringSet(KEY_SCHEDULED_IDS, values)
                .apply();
    }

    public static LocalDate scheduleEndDate(
            List<ScheduleEvent> events
    ) {
        LocalDate end = events.get(0).date;

        for (ScheduleEvent event : events) {
            if (event.date.isAfter(end)) {
                end = event.date;
            }
        }

        return end;
    }

    public static LocalDate scheduleStartDate(
            List<ScheduleEvent> events
    ) {
        LocalDate start = events.get(0).date;

        for (ScheduleEvent event : events) {
            if (event.date.isBefore(start)) {
                start = event.date;
            }
        }

        return start;
    }
}
