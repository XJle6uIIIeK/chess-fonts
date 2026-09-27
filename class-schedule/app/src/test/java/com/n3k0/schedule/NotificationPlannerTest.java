package com.n3k0.schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationPlannerTest {
    private static final String GROUP = "10-кЮРо25-4";

    @Test
    public void nextAndRepeatAreOrderedCorrectly() {
        ScheduleEvent event = event(
                LocalDate.of(2026, 9, 28),
                3,
                LocalTime.of(12, 50),
                LocalTime.of(14, 20),
                "Римское право"
        );

        NotificationPrefs.Config config = config(
                true, 30,
                true, 10,
                false, NotificationPrefs.MODE_BEFORE_NEXT, 10
        );

        List<PlannedNotification> plan = new NotificationPlanner().plan(
                Collections.singletonList(event),
                GROUP,
                config,
                LocalDateTime.of(2026, 9, 28, 9, 0),
                LocalDateTime.of(2026, 9, 29, 0, 0)
        );

        assertEquals(2, plan.size());
        assertEquals(
                PlannedNotification.Type.NEXT_CLASS,
                plan.get(0).type
        );
        assertEquals(
                LocalDateTime.of(2026, 9, 28, 12, 20),
                plan.get(0).triggerAt
        );
        assertEquals(
                PlannedNotification.Type.REPEAT,
                plan.get(1).type
        );
        assertEquals(
                LocalDateTime.of(2026, 9, 28, 12, 40),
                plan.get(1).triggerAt
        );
    }

    @Test
    public void invalidRepeatEarlierThanMainIsNotPlanned() {
        ScheduleEvent event = event(
                LocalDate.of(2026, 9, 28),
                3,
                LocalTime.of(12, 50),
                LocalTime.of(14, 20),
                "Римское право"
        );

        NotificationPrefs.Config config = config(
                true, 10,
                true, 30,
                false, NotificationPrefs.MODE_BEFORE_NEXT, 10
        );

        List<PlannedNotification> plan = new NotificationPlanner().plan(
                Collections.singletonList(event),
                GROUP,
                config,
                LocalDateTime.of(2026, 9, 28, 9, 0),
                LocalDateTime.of(2026, 9, 29, 0, 0)
        );

        assertEquals(1, plan.size());
        assertEquals(
                PlannedNotification.Type.NEXT_CLASS,
                plan.get(0).type
        );
    }

    @Test
    public void longBreakRequiresSecondAndThirdPair() {
        LocalDate date = LocalDate.of(2026, 9, 28);
        ScheduleEvent third = event(
                date,
                3,
                LocalTime.of(12, 50),
                LocalTime.of(14, 20),
                "Конституционное право"
        );

        NotificationPrefs.Config config = config(
                false, 30,
                false, 10,
                true, NotificationPrefs.MODE_BEFORE_NEXT, 10
        );

        List<PlannedNotification> noSecond =
                new NotificationPlanner().plan(
                        Collections.singletonList(third),
                        GROUP,
                        config,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 29, 0, 0)
                );

        assertTrue(noSecond.isEmpty());

        ScheduleEvent second = event(
                date,
                2,
                LocalTime.of(10, 40),
                LocalTime.of(12, 10),
                "Русский язык"
        );

        List<PlannedNotification> withSecond =
                new NotificationPlanner().plan(
                        Arrays.asList(second, third),
                        GROUP,
                        config,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 29, 0, 0)
                );

        assertEquals(1, withSecond.size());
        assertEquals(
                PlannedNotification.Type.LONG_BREAK,
                withSecond.get(0).type
        );
        assertEquals(
                LocalDateTime.of(2026, 9, 28, 12, 40),
                withSecond.get(0).triggerAt
        );
    }

    @Test
    public void longBreakWinsTimestampConflict() {
        LocalDate date = LocalDate.of(2026, 9, 28);
        ScheduleEvent second = event(
                date,
                2,
                LocalTime.of(10, 40),
                LocalTime.of(12, 10),
                "Русский язык"
        );
        ScheduleEvent third = event(
                date,
                3,
                LocalTime.of(12, 50),
                LocalTime.of(14, 20),
                "Конституционное право"
        );

        NotificationPrefs.Config config = config(
                true, 10,
                false, 5,
                true, NotificationPrefs.MODE_BEFORE_NEXT, 10
        );

        List<PlannedNotification> plan =
                new NotificationPlanner().plan(
                        Arrays.asList(second, third),
                        GROUP,
                        config,
                        LocalDateTime.of(2026, 9, 28, 12, 11),
                        LocalDateTime.of(2026, 9, 29, 0, 0)
                );

        assertEquals(1, plan.size());
        assertEquals(
                PlannedNotification.Type.LONG_BREAK,
                plan.get(0).type
        );
        assertTrue(plan.get(0).isMerged());
        assertTrue(
                plan.get(0).mergedTypes.contains(
                        PlannedNotification.Type.NEXT_CLASS
                )
        );
        assertTrue(
                plan.get(0).mergedTypes.contains(
                        PlannedNotification.Type.LONG_BREAK
                )
        );
    }

    @Test
    public void plannerHonorsRollingWindow() {
        List<ScheduleEvent> events = new ArrayList<>();
        events.add(event(
                LocalDate.of(2026, 9, 28),
                1,
                LocalTime.of(9, 0),
                LocalTime.of(10, 30),
                "Сегодня"
        ));
        events.add(event(
                LocalDate.of(2026, 10, 20),
                1,
                LocalTime.of(9, 0),
                LocalTime.of(10, 30),
                "Далеко"
        ));

        NotificationPrefs.Config config = config(
                true, 30,
                false, 10,
                false, NotificationPrefs.MODE_BEFORE_NEXT, 10
        );

        List<PlannedNotification> plan =
                new NotificationPlanner().plan(
                        events,
                        GROUP,
                        config,
                        LocalDateTime.of(2026, 9, 27, 12, 0),
                        LocalDateTime.of(2026, 10, 11, 12, 0)
                );

        assertFalse(plan.isEmpty());
        for (PlannedNotification item : plan) {
            assertTrue(item.event.date.isBefore(LocalDate.of(2026, 10, 12)));
        }
    }

    private NotificationPrefs.Config config(
            boolean nextEnabled,
            int nextOffset,
            boolean repeatEnabled,
            int repeatOffset,
            boolean longBreakEnabled,
            String mode,
            int longBreakOffset
    ) {
        return new NotificationPrefs.Config(
                nextEnabled,
                nextOffset,
                repeatEnabled,
                repeatOffset,
                longBreakEnabled,
                mode,
                longBreakOffset,
                true,
                20,
                0
        );
    }

    private ScheduleEvent event(
            LocalDate date,
            int pair,
            LocalTime start,
            LocalTime end,
            String subject
    ) {
        return new ScheduleEvent(
                date,
                pair,
                start,
                end,
                subject,
                "Преподаватель А.А.",
                "201",
                Collections.singletonList(GROUP),
                0
        );
    }
}
