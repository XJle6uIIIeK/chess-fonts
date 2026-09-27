package com.n3k0.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves one calendar date per physical day block in the timetable.
 *
 * Date labels in the source PDF are rotated and can disappear when a day is
 * split by a page break. The resolver therefore works on the complete global
 * sequence of day blocks instead of assigning dates page by page.
 */
public final class ScheduleCalendarResolver {
    public List<LocalDate> resolve(
            List<LocalDate> explicitDates,
            int dayBlockCount
    ) {
        if (dayBlockCount <= 0) {
            throw new IllegalArgumentException(
                    "В таблице не найдено блоков дней"
            );
        }

        LinkedHashSet<LocalDate> unique =
                new LinkedHashSet<>(
                        explicitDates == null
                                ? Collections.emptyList()
                                : explicitDates
                );

        List<LocalDate> explicit =
                new ArrayList<>(unique);

        Collections.sort(explicit);

        if (explicit.isEmpty()) {
            throw new IllegalArgumentException(
                    "В таблице не найдено ни одной явной даты"
            );
        }

        if (explicit.size() == dayBlockCount) {
            return explicit;
        }

        LocalDate start = explicit.get(0);
        LocalDate end =
                explicit.get(
                        explicit.size() - 1
                );

        Set<DayOfWeek> explicitWeekdays =
                new LinkedHashSet<>();

        for (LocalDate date : explicit) {
            explicitWeekdays.add(
                    date.getDayOfWeek()
            );
        }

        List<DayOfWeek> absentWeekdays =
                new ArrayList<>();

        for (DayOfWeek day :
                DayOfWeek.values()) {
            if (!explicitWeekdays.contains(day)) {
                absentWeekdays.add(day);
            }
        }

        List<List<LocalDate>> candidates =
                new ArrayList<>();

        int subsetCount =
                1 << absentWeekdays.size();

        for (int mask = 0;
             mask < subsetCount;
             mask++) {
            Set<DayOfWeek> excluded =
                    new LinkedHashSet<>();

            for (int i = 0;
                 i < absentWeekdays.size();
                 i++) {
                if ((mask & (1 << i)) != 0) {
                    excluded.add(
                            absentWeekdays.get(i)
                    );
                }
            }

            List<LocalDate> candidate =
                    new ArrayList<>();

            LocalDate cursor = start;

            while (!cursor.isAfter(end)) {
                if (!excluded.contains(
                        cursor.getDayOfWeek()
                )) {
                    candidate.add(cursor);
                }

                cursor = cursor.plusDays(1);
            }

            if (candidate.size() != dayBlockCount) {
                continue;
            }

            if (!candidate.containsAll(explicit)) {
                continue;
            }

            candidates.add(candidate);
        }

        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                    "Не удалось однозначно сопоставить " +
                            dayBlockCount +
                            " блоков дней с явными датами " +
                            start +
                            "–" +
                            end +
                            ". Найдено вариантов: " +
                            candidates.size()
            );
        }

        return candidates.get(0);
    }
}
