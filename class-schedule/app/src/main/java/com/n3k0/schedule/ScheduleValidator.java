package com.n3k0.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ScheduleValidator {
    public static final class ValidationResult {
        public final List<ScheduleValidationIssue> issues;
        public final int errorCount;
        public final int warningCount;

        ValidationResult(List<ScheduleValidationIssue> issues) {
            this.issues = new ArrayList<>(issues);

            int errors = 0;
            int warnings = 0;

            for (ScheduleValidationIssue issue : issues) {
                if (issue.severity ==
                        ScheduleValidationIssue.Severity.ERROR) {
                    errors++;
                } else {
                    warnings++;
                }
            }

            errorCount = errors;
            warningCount = warnings;
        }

        public boolean canActivate() {
            return errorCount == 0;
        }
    }

    private static final LocalTime[] STANDARD_STARTS =
            new LocalTime[]{
                    LocalTime.of(9, 0),
                    LocalTime.of(10, 40),
                    LocalTime.of(12, 50),
                    LocalTime.of(14, 30),
                    LocalTime.of(16, 10)
            };

    private static final LocalTime[] STANDARD_ENDS =
            new LocalTime[]{
                    LocalTime.of(10, 30),
                    LocalTime.of(12, 10),
                    LocalTime.of(14, 20),
                    LocalTime.of(16, 0),
                    LocalTime.of(17, 40)
            };

    public ValidationResult validate(
            ScheduleParser.ParseResult result
    ) {
        List<ScheduleValidationIssue> issues =
                new ArrayList<>();

        // Blocking errors are deliberately limited to cases where there is
        // no usable schedule at all. Heuristic parser anomalies must not
        // trap the user in an unactivatable import.
        if (result.groups == null || result.groups.isEmpty()) {
            issues.add(error(
                    "GROUPS_MISSING",
                    "В PDF не удалось найти ни одной учебной группы."
            ));
        }

        if (result.events == null || result.events.isEmpty()) {
            issues.add(error(
                    "EVENTS_MISSING",
                    "В PDF не удалось найти ни одного занятия."
            ));
            return new ValidationResult(issues);
        }

        Set<String> knownGroups =
                new LinkedHashSet<>(result.groups);

        Map<String, Integer> eventsPerGroup =
                new HashMap<>();

        for (String group : result.groups) {
            eventsPerGroup.put(group, 0);
        }

        LocalDate minDate = null;
        LocalDate maxDate = null;

        int invalidPairCount = 0;
        int invalidTimeCount = 0;
        int eventWithoutGroupCount = 0;
        int unknownGroupCount = 0;
        int conflictingSlotCount = 0;
        int missingRoomCount = 0;
        int nonStandardTimeCount = 0;
        int nonContiguousMergedCount = 0;

        Map<String, ScheduleEvent> occupiedGroupSlots =
                new HashMap<>();

        for (ScheduleEvent event : result.events) {
            if (event.date == null) {
                issues.add(error(
                        "DATES_MISSING",
                        "Не удалось определить даты занятий."
                ));
                continue;
            }

            if (minDate == null ||
                    event.date.isBefore(minDate)) {
                minDate = event.date;
            }

            if (maxDate == null ||
                    event.date.isAfter(maxDate)) {
                maxDate = event.date;
            }

            if (event.pairNumber < 1 ||
                    event.pairNumber > 5) {
                invalidPairCount++;
            }

            if (event.start == null ||
                    event.end == null ||
                    !event.end.isAfter(event.start)) {
                invalidTimeCount++;
            }

            if (event.groups.isEmpty()) {
                eventWithoutGroupCount++;
            }

            for (String group : event.groups) {
                if (!knownGroups.contains(group)) {
                    unknownGroupCount++;
                } else {
                    eventsPerGroup.put(
                            group,
                            eventsPerGroup.getOrDefault(group, 0) + 1
                    );
                }

                String slotKey =
                        group.toLowerCase(Locale.ROOT) +
                                "|" +
                                event.date +
                                "|" +
                                event.pairNumber;

                ScheduleEvent previous =
                        occupiedGroupSlots.get(slotKey);

                if (previous != null &&
                        !sameEvent(previous, event)) {
                    conflictingSlotCount++;
                } else {
                    occupiedGroupSlots.put(slotKey, event);
                }
            }

            if (event.room.isBlank() &&
                    !isSelfStudy(event)) {
                missingRoomCount++;
            }

            if (event.pairNumber >= 1 &&
                    event.pairNumber <= 5 &&
                    !isSelfStudy(event) &&
                    event.start != null &&
                    event.end != null) {
                LocalTime standardStart =
                        STANDARD_STARTS[event.pairNumber - 1];
                LocalTime standardEnd =
                        STANDARD_ENDS[event.pairNumber - 1];

                if (!event.start.equals(standardStart) ||
                        !event.end.equals(standardEnd)) {
                    nonStandardTimeCount++;
                }
            }

            if (event.groups.size() > 1 &&
                    !groupsAreContiguous(
                            result.groups,
                            event.groups
                    )) {
                nonContiguousMergedCount++;
            }
        }

        if (minDate == null || maxDate == null) {
            if (!hasCode(issues, "DATES_MISSING")) {
                issues.add(error(
                        "DATES_MISSING",
                        "Не удалось определить диапазон дат расписания."
                ));
            }
        }

        for (String group : result.groups) {
            int count =
                    eventsPerGroup.getOrDefault(group, 0);

            if (count == 0) {
                issues.add(warning(
                        "EMPTY_GROUP",
                        "Для группы «" + group +
                                "» не найдено занятий. Проверь эту колонку."
                ));
            } else if (count < 5) {
                issues.add(warning(
                        "SPARSE_GROUP",
                        "Для группы «" + group +
                                "» распознано только " +
                                count +
                                " занятий."
                ));
            }
        }

        addCountWarning(
                issues,
                "INVALID_PAIR",
                invalidPairCount,
                "занятий имеют необычный номер пары"
        );

        addCountWarning(
                issues,
                "INVALID_TIME",
                invalidTimeCount,
                "занятий имеют некорректный интервал времени"
        );

        addCountWarning(
                issues,
                "EVENT_WITHOUT_GROUP",
                eventWithoutGroupCount,
                "занятий не привязаны к группе"
        );

        addCountWarning(
                issues,
                "UNKNOWN_GROUP",
                unknownGroupCount,
                "привязок относятся к неизвестной группе"
        );

        addCountWarning(
                issues,
                "CONFLICTING_SLOT",
                conflictingSlotCount,
                "слотов содержат больше одного различающегося фрагмента"
        );

        if (minDate != null &&
                maxDate != null &&
                minDate.getMonth() != maxDate.getMonth()) {
            issues.add(warning(
                    "MULTI_MONTH_RANGE",
                    "Распознанный диапазон затрагивает несколько месяцев: " +
                            minDate + " — " + maxDate + "."
            ));
        }

        int expectedFloor =
                Math.max(20, result.groups.size() * 8);

        if (result.events.size() < expectedFloor) {
            issues.add(warning(
                    "SUSPICIOUSLY_FEW_EVENTS",
                    "Распознано только " +
                            result.events.size() +
                            " занятий для " +
                            result.groups.size() +
                            " групп."
            ));
        }

        if (missingRoomCount > 0) {
            issues.add(warning(
                    "ROOMS_MISSING",
                    "У " +
                            missingRoomCount +
                            " занятий не распознан кабинет."
            ));
        }

        if (nonStandardTimeCount > 0) {
            issues.add(warning(
                    "NON_STANDARD_TIMES",
                    nonStandardTimeCount +
                            " занятий используют нестандартное время."
            ));
        }

        if (nonContiguousMergedCount > 0) {
            issues.add(warning(
                    "NON_CONTIGUOUS_MERGED_GROUPS",
                    nonContiguousMergedCount +
                            " объединённых занятий относятся к несмежным группам."
            ));
        }

        if (result.warnings != null &&
                !result.warnings.isEmpty()) {
            issues.add(warning(
                    "PARSER_RECOVERY",
                    "Парсер автоматически восстановил " +
                            result.warnings.size() +
                            " фрагментов таблицы."
            ));
        }

        return new ValidationResult(issues);
    }

    private void addCountWarning(
            List<ScheduleValidationIssue> issues,
            String code,
            int count,
            String suffix
    ) {
        if (count <= 0) return;

        issues.add(warning(
                code,
                count + " " + suffix + "."
        ));
    }

    private boolean hasCode(
            List<ScheduleValidationIssue> issues,
            String code
    ) {
        for (ScheduleValidationIssue issue : issues) {
            if (code.equals(issue.code)) return true;
        }
        return false;
    }

    private boolean sameEvent(
            ScheduleEvent a,
            ScheduleEvent b
    ) {
        return a.start.equals(b.start)
                && a.end.equals(b.end)
                && a.cleanSubject()
                .equalsIgnoreCase(b.cleanSubject())
                && a.room.equalsIgnoreCase(b.room)
                && a.teacher.equalsIgnoreCase(b.teacher);
    }

    private boolean groupsAreContiguous(
            List<String> allGroups,
            List<String> eventGroups
    ) {
        List<Integer> indexes =
                new ArrayList<>();

        for (String group : eventGroups) {
            int index = allGroups.indexOf(group);
            if (index >= 0) indexes.add(index);
        }

        if (indexes.size() != eventGroups.size()) {
            return false;
        }

        indexes.sort(Integer::compareTo);

        for (int i = 1; i < indexes.size(); i++) {
            if (indexes.get(i) !=
                    indexes.get(i - 1) + 1) {
                return false;
            }
        }

        return true;
    }

    private boolean isSelfStudy(ScheduleEvent event) {
        return event.cleanSubject()
                .toLowerCase(Locale.ROOT)
                .contains("самостоятельной работы");
    }

    private ScheduleValidationIssue error(
            String code,
            String message
    ) {
        return new ScheduleValidationIssue(
                ScheduleValidationIssue.Severity.ERROR,
                code,
                message
        );
    }

    private ScheduleValidationIssue warning(
            String code,
            String message
    ) {
        return new ScheduleValidationIssue(
                ScheduleValidationIssue.Severity.WARNING,
                code,
                message
        );
    }
}
