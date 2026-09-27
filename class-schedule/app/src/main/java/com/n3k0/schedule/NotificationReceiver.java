package com.n3k0.schedule;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NotificationReceiver extends BroadcastReceiver {
    private final NotificationRenderer renderer = new NotificationRenderer();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // Maintenance alarms must work even when notification permission is off.
        if (NotificationScheduler.ACTION_REFRESH.equals(action)) {
            NotificationScheduler.scheduleAll(context);
            return;
        }

        NotificationScheduler.createChannel(context);

        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (NotificationScheduler.ACTION_CLASS.equals(action)) {
            showClassReminder(
                    context,
                    intent.getStringExtra(NotificationScheduler.EXTRA_EVENT_KEY),
                    intent.getStringExtra(NotificationScheduler.EXTRA_KIND),
                    intent.getIntExtra(NotificationScheduler.EXTRA_OFFSET, 0),
                    intent.getBooleanExtra(NotificationScheduler.EXTRA_MERGED, false)
            );
        } else if (NotificationScheduler.ACTION_TOMORROW.equals(action)) {
            showTomorrow(context);
            NotificationScheduler.scheduleTomorrowSummary(context);
        } else if (NotificationScheduler.ACTION_EXPIRY.equals(action)) {
            showExpiry(
                    context,
                    intent.getStringExtra(NotificationScheduler.EXTRA_END_DATE)
            );
        } else if (NotificationScheduler.ACTION_TEST.equals(action)) {
            showTest(
                    context,
                    intent.getStringExtra(NotificationScheduler.EXTRA_KIND),
                    intent.getStringExtra(NotificationScheduler.EXTRA_EVENT_KEY)
            );
        }
    }

    private void showClassReminder(
            Context context,
            String key,
            String kind,
            int offset,
            boolean merged
    ) {
        ScheduleEvent target = findEvent(context, key);
        if (target == null) return;

        NotificationRenderer.Rendered rendered =
                renderer.renderClass(
                        target,
                        kind,
                        offset,
                        merged
                );

        int id = 100_000 + Math.floorMod(
                (kind + "|" + target.stableKey()).hashCode(),
                700_000
        );

        notify(
                context,
                id,
                rendered.title,
                rendered.body
        );
    }

    private void showTest(
            Context context,
            String kind,
            String eventKey
    ) {
        if ("tomorrow".equals(kind)) {
            showTomorrow(context);
            return;
        }

        ScheduleEvent event = findEvent(context, eventKey);
        if (event == null) {
            notify(
                    context,
                    990_001,
                    "Нет данных для теста",
                    "В активном расписании не найдено подходящее занятие."
            );
            return;
        }

        NotificationPrefs.Config config =
                NotificationPrefs.load(context);

        int offset;
        if (NotificationScheduler.KIND_REPEAT.equals(kind)) {
            offset = config.repeatOffsetMinutes;
        } else if (NotificationScheduler.KIND_LONG_BREAK.equals(kind)) {
            offset = config.longBreakOffsetMinutes;
        } else {
            offset = config.nextOffsetMinutes;
        }

        NotificationRenderer.Rendered rendered =
                renderer.renderClass(event, kind, offset);

        notify(
                context,
                990_010 + Math.floorMod(kind.hashCode(), 50),
                "ТЕСТ • " + rendered.title,
                rendered.body
        );
    }

    private ScheduleEvent findEvent(Context context, String key) {
        if (key == null || key.isBlank()) return null;

        for (ScheduleEvent event : ScheduleStore.loadEvents(context)) {
            if (event.stableKey().equals(key)) {
                return event;
            }
        }
        return null;
    }

    private void showTomorrow(Context context) {
        String group = NotificationScheduler.settings(context)
                .getString(NotificationScheduler.KEY_GROUP, "")
                .trim();

        if (group.isEmpty()) return;

        List<ScheduleEvent> all = ScheduleStore.loadEvents(context);
        if (all.isEmpty()) return;

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate endDate = NotificationScheduler.scheduleEndDate(all);

        if (tomorrow.isAfter(endDate)) {
            notify(
                    context,
                    NotificationScheduler.TOMORROW_REQUEST,
                    "Расписание закончилось",
                    "Последняя загруженная дата — " +
                            formatDate(endDate) +
                            ". Импортируй расписание на следующий месяц."
            );
            return;
        }

        List<ScheduleEvent> events =
                ScheduleStore.forDateAndGroup(context, tomorrow, group);

        if (events.isEmpty()) {
            notify(
                    context,
                    NotificationScheduler.TOMORROW_REQUEST,
                    "Завтра занятий нет",
                    group
            );
            return;
        }

        boolean selfStudyOnly = true;
        for (ScheduleEvent event : events) {
            if (!isSelfStudy(event)) {
                selfStudyOnly = false;
                break;
            }
        }

        if (selfStudyOnly) {
            notify(
                    context,
                    NotificationScheduler.TOMORROW_REQUEST,
                    "День самостоятельной работы",
                    "Завтра"
            );
            return;
        }

        List<ScheduleEvent> actual = new ArrayList<>();
        for (ScheduleEvent event : events) {
            if (!isSelfStudy(event)) actual.add(event);
        }

        if (actual.isEmpty()) {
            notify(
                    context,
                    NotificationScheduler.TOMORROW_REQUEST,
                    "Завтра занятий нет",
                    group
            );
            return;
        }

        ScheduleEvent first = actual.get(0);
        LocalTime standard = standardStart(first.pairNumber);

        String title;
        if (first.start.equals(standard)) {
            title = "Завтра к " +
                    first.pairNumber +
                    "-й паре — " +
                    first.start;
        } else {
            title = "Завтра первое занятие — " + first.start;
        }

        StringBuilder body = new StringBuilder();

        for (int pair = 1; pair <= 5; pair++) {
            if (body.length() > 0) body.append("\n");

            ScheduleEvent event = eventForPair(actual, pair);
            body.append(pair).append("-я пара — ");

            if (event == null) {
                body.append("нет пары");
            } else {
                body.append(event.cleanSubject());

                if (!event.room.isBlank()) {
                    body.append(", каб. ").append(event.room);
                }

                body.append(" • ").append(event.start);
            }
        }

        notify(
                context,
                NotificationScheduler.TOMORROW_REQUEST,
                title,
                body.toString()
        );
    }

    private void showExpiry(Context context, String rawDate) {
        LocalDate endDate;

        try {
            endDate = LocalDate.parse(rawDate);
        } catch (Exception ignored) {
            List<ScheduleEvent> events = ScheduleStore.loadEvents(context);
            if (events.isEmpty()) return;
            endDate = NotificationScheduler.scheduleEndDate(events);
        }

        notify(
                context,
                NotificationScheduler.EXPIRY_REQUEST,
                "Расписание скоро закончится",
                "Последняя загруженная дата — " +
                        formatDate(endDate) +
                        ". Импортируй PDF на следующий месяц, чтобы уведомления продолжили работать."
        );
    }

    private String formatDate(LocalDate date) {
        return date.format(
                DateTimeFormatter.ofPattern(
                        "d MMMM",
                        new Locale("ru")
                )
        );
    }

    private ScheduleEvent eventForPair(
            List<ScheduleEvent> events,
            int pair
    ) {
        for (ScheduleEvent event : events) {
            if (event.pairNumber == pair && !isSelfStudy(event)) {
                return event;
            }
        }
        return null;
    }

    private boolean isSelfStudy(ScheduleEvent event) {
        return event.cleanSubject()
                .toLowerCase(new Locale("ru"))
                .contains("самостоятельной работы");
    }

    private LocalTime standardStart(int pair) {
        switch (pair) {
            case 1:
                return LocalTime.of(9, 0);
            case 2:
                return LocalTime.of(10, 40);
            case 3:
                return LocalTime.of(12, 50);
            case 4:
                return LocalTime.of(14, 30);
            default:
                return LocalTime.of(16, 10);
        }
    }

    private void notify(
            Context context,
            int id,
            String title,
            String text
    ) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                );

        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                42,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(
                                context,
                                NotificationScheduler.CHANNEL_ID
                        )
                        : new Notification.Builder(context);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text.replace('\n', ' '))
                .setStyle(
                        new Notification.BigTextStyle()
                                .bigText(text)
                )
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH);

        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                );

        manager.notify(id, builder.build());
    }
}
