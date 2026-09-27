package com.n3k0.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NotificationConflictResolver {
    public List<PlannedNotification> resolve(
            List<PlannedNotification> input
    ) {
        Map<String, PlannedNotification> bySlot =
                new LinkedHashMap<>();

        for (PlannedNotification candidate : input) {
            String key = candidate.conflictKey();
            PlannedNotification current =
                    bySlot.get(key);

            if (current == null) {
                bySlot.put(key, candidate);
            } else {
                bySlot.put(
                        key,
                        current.mergeWith(candidate)
                );
            }
        }

        List<PlannedNotification> result =
                new ArrayList<>(bySlot.values());

        result.sort(
                Comparator
                        .comparing(
                                (PlannedNotification p) ->
                                        p.triggerAt
                        )
                        .thenComparing(
                                p -> p.event.startDateTime()
                        )
                        .thenComparingInt(
                                p -> -p.priority()
                        )
        );

        return result;
    }
}
