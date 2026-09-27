package com.n3k0.schedule;

import android.content.Context;

import java.time.LocalDate;
import java.util.List;

public final class AppState {
    public enum Mode {
        EMPTY,
        IMPORTING,
        IMPORT_REVIEW,
        READY,
        EXPIRED,
        IMPORT_REVIEW_WITH_ACTIVE
    }

    public enum Screen {
        SCHEDULE,
        SETTINGS
    }

    public final Mode mode;
    public final boolean hasActiveSchedule;
    public final boolean hasPendingSchedule;
    public final String activeGroup;
    public final String pendingGroup;

    private AppState(
            Mode mode,
            boolean hasActiveSchedule,
            boolean hasPendingSchedule,
            String activeGroup,
            String pendingGroup
    ) {
        this.mode = mode;
        this.hasActiveSchedule = hasActiveSchedule;
        this.hasPendingSchedule = hasPendingSchedule;
        this.activeGroup = activeGroup == null ? "" : activeGroup.trim();
        this.pendingGroup = pendingGroup == null ? "" : pendingGroup.trim();
    }

    public static AppState resolve(
            Context context,
            boolean importing
    ) {
        boolean active = ScheduleStore.hasData(context);
        boolean pending = ScheduleStore.hasPending(context);

        String activeGroup = NotificationScheduler.settings(context)
                .getString(NotificationScheduler.KEY_GROUP, "")
                .trim();
        String pendingGroup =
                ScheduleStore.loadPendingSelectedGroup(context).trim();

        boolean expired = false;

        if (active) {
            List<ScheduleEvent> events =
                    ScheduleStore.loadEvents(context);

            if (!events.isEmpty()) {
                LocalDate end =
                        NotificationScheduler
                                .scheduleEndDate(events);

                expired =
                        LocalDate.now().isAfter(end);
            }
        }

        Mode mode = resolveMode(
                importing,
                active,
                pending,
                expired
        );

        return new AppState(
                mode,
                active,
                pending,
                activeGroup,
                pendingGroup
        );
    }

    public static Mode resolveMode(
            boolean importing,
            boolean active,
            boolean pending,
            boolean expired
    ) {
        if (importing) return Mode.IMPORTING;
        if (active && pending) {
            return Mode.IMPORT_REVIEW_WITH_ACTIVE;
        }
        if (pending) return Mode.IMPORT_REVIEW;
        if (active && expired) return Mode.EXPIRED;
        if (active) return Mode.READY;
        return Mode.EMPTY;
    }
}
