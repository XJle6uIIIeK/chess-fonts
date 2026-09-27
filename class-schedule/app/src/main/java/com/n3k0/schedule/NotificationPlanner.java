package com.n3k0.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class NotificationPlanner {
    private static final LocalTime BIG_BREAK_START = LocalTime.of(12, 10);
    private static final LocalTime BIG_BREAK_END = LocalTime.of(12, 50);
    private static final int BIG_BREAK_MINUTES = 40;

    private final NotificationConflictResolver conflictResolver =
            new NotificationConflictResolver();

    public List<PlannedNotification> plan(
            List<ScheduleEvent> allEvents,
            String group,
            NotificationPrefs.Config config,
            LocalDateTime now,
            LocalDateTime until
    ) {
        List<ScheduleEvent> events = new ArrayList<>();

        for (ScheduleEvent event : allEvents) {
            if (!event.belongsTo(group)) continue;
            if (isSelfStudy(event)) continue;
            if (event.startDateTime().isBefore(now.minusDays(1))) continue;
            if (event.startDateTime().isAfter(until.plusDays(1))) continue;
            events.add(event);
        }

        events.sort(
                Comparator.comparing(ScheduleEvent::startDateTime)
                        .thenComparingInt(event -> event.pairNumber)
        );

        List<PlannedNotification> raw = new ArrayList<>();

        for (ScheduleEvent event : events) {
            if (config.nextEnabled) {
                addIfInsideWindow(
                        raw,
                        new PlannedNotification(
                                PlannedNotification.Type.NEXT_CLASS,
                                event.startDateTime()
                                        .minusMinutes(config.nextOffsetMinutes),
                                event,
                                config.nextOffsetMinutes
                        ),
                        now,
                        until
                );
            }

            if (config.repeatEnabled &&
                    (!config.nextEnabled ||
                            config.repeatOffsetMinutes < config.nextOffsetMinutes)) {
                addIfInsideWindow(
                        raw,
                        new PlannedNotification(
                                PlannedNotification.Type.REPEAT,
                                event.startDateTime()
                                        .minusMinutes(config.repeatOffsetMinutes),
                                event,
                                config.repeatOffsetMinutes
                        ),
                        now,
                        until
                );
            }
        }

        if (config.longBreakEnabled) {
            addLongBreakNotifications(raw, events, config, now, until);
        }

        return conflictResolver.resolve(raw);
    }

    private void addLongBreakNotifications(
            List<PlannedNotification> out,
            List<ScheduleEvent> events,
            NotificationPrefs.Config config,
            LocalDateTime now,
            LocalDateTime until
    ) {
        int offset = Math.max(
                0,
                Math.min(BIG_BREAK_MINUTES, config.longBreakOffsetMinutes)
        );

        for (ScheduleEvent thirdPair : events) {
            if (thirdPair.pairNumber != 3) continue;
            if (!thirdPair.start.equals(BIG_BREAK_END)) continue;

            ScheduleEvent secondPair = findSecondPair(events, thirdPair.date);
            if (secondPair == null) continue;

            LocalDateTime trigger;
            if (NotificationPrefs.MODE_AFTER_START.equals(config.longBreakMode)) {
                trigger = thirdPair.date
                        .atTime(BIG_BREAK_START)
                        .plusMinutes(offset);
            } else {
                trigger = thirdPair.date
                        .atTime(BIG_BREAK_END)
                        .minusMinutes(offset);
            }

            addIfInsideWindow(
                    out,
                    new PlannedNotification(
                            PlannedNotification.Type.LONG_BREAK,
                            trigger,
                            thirdPair,
                            offset
                    ),
                    now,
                    until
            );
        }
    }

    private ScheduleEvent findSecondPair(
            List<ScheduleEvent> events,
            LocalDate date
    ) {
        for (ScheduleEvent event : events) {
            if (!event.date.equals(date)) continue;
            if (event.pairNumber != 2) continue;
            if (!event.end.equals(BIG_BREAK_START)) continue;
            return event;
        }
        return null;
    }

    private void addIfInsideWindow(
            List<PlannedNotification> out,
            PlannedNotification notification,
            LocalDateTime now,
            LocalDateTime until
    ) {
        if (!notification.triggerAt.isAfter(now)) return;
        if (notification.triggerAt.isAfter(until)) return;
        out.add(notification);
    }

    private boolean isSelfStudy(ScheduleEvent event) {
        return event.cleanSubject()
                .toLowerCase()
                .contains("самостоятельной работы");
    }
}
