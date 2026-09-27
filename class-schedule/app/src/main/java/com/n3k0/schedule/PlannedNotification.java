package com.n3k0.schedule;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class PlannedNotification {
    public enum Type {
        NEXT_CLASS,
        REPEAT,
        LONG_BREAK
    }

    public final Type type;
    public final LocalDateTime triggerAt;
    public final ScheduleEvent event;
    public final int offsetMinutes;
    public final Set<Type> mergedTypes;

    public PlannedNotification(
            Type type,
            LocalDateTime triggerAt,
            ScheduleEvent event,
            int offsetMinutes
    ) {
        this(
                type,
                triggerAt,
                event,
                offsetMinutes,
                EnumSet.of(type)
        );
    }

    private PlannedNotification(
            Type type,
            LocalDateTime triggerAt,
            ScheduleEvent event,
            int offsetMinutes,
            Set<Type> mergedTypes
    ) {
        this.type = type;
        this.triggerAt = triggerAt;
        this.event = event;
        this.offsetMinutes = offsetMinutes;
        this.mergedTypes = Collections.unmodifiableSet(
                EnumSet.copyOf(mergedTypes)
        );
    }

    public PlannedNotification mergeWith(
            PlannedNotification other
    ) {
        PlannedNotification primary =
                other.priority() > priority()
                        ? other
                        : this;

        EnumSet<Type> types =
                EnumSet.copyOf(mergedTypes);
        types.addAll(other.mergedTypes);

        return new PlannedNotification(
                primary.type,
                primary.triggerAt,
                primary.event,
                primary.offsetMinutes,
                types
        );
    }

    public boolean isMerged() {
        return mergedTypes.size() > 1;
    }

    public int priority() {
        switch (type) {
            case LONG_BREAK:
                return 30;
            case REPEAT:
                return 20;
            default:
                return 10;
        }
    }

    public String kind() {
        switch (type) {
            case LONG_BREAK:
                return NotificationScheduler.KIND_LONG_BREAK;
            case REPEAT:
                return NotificationScheduler.KIND_REPEAT;
            default:
                return NotificationScheduler.KIND_NEXT;
        }
    }

    public String conflictKey() {
        return event.stableKey() + "|" + triggerAt;
    }
}
