package com.n3k0.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ScheduleNormalizer {
    public static final class Result {
        public final ScheduleParser.ParseResult parseResult;
        public final int removedExactDuplicates;
        public final int mergedFragments;
        public final int unresolvedConflicts;

        Result(
                ScheduleParser.ParseResult parseResult,
                int removedExactDuplicates,
                int mergedFragments,
                int unresolvedConflicts
        ) {
            this.parseResult = parseResult;
            this.removedExactDuplicates = removedExactDuplicates;
            this.mergedFragments = mergedFragments;
            this.unresolvedConflicts = unresolvedConflicts;
        }
    }

    private static final class GroupSlot {
        final String group;
        final ScheduleEvent event;

        GroupSlot(String group, ScheduleEvent event) {
            this.group = group;
            this.event = event;
        }
    }

    public Result normalize(ScheduleParser.ParseResult input) {
        List<GroupSlot> expanded = new ArrayList<>();
        int exactDuplicates = 0;
        int mergedFragments = 0;
        int unresolvedConflicts = 0;

        Set<String> seenExact = new LinkedHashSet<>();

        for (ScheduleEvent event : input.events) {
            for (String group : event.groups) {
                ScheduleEvent single = new ScheduleEvent(
                        event.date,
                        event.pairNumber,
                        event.start,
                        event.end,
                        normalizeText(event.subject),
                        normalizeText(event.teacher),
                        normalizeText(event.room),
                        Collections.singletonList(group),
                        event.sourcePage
                );

                String exactKey =
                        slotKey(group, single) + "|" +
                                identity(single);

                if (!seenExact.add(exactKey)) {
                    exactDuplicates++;
                    continue;
                }

                expanded.add(new GroupSlot(group, single));
            }
        }

        Map<String, List<GroupSlot>> bySlot = new LinkedHashMap<>();
        for (GroupSlot item : expanded) {
            bySlot.computeIfAbsent(
                    slotKey(item.group, item.event),
                    key -> new ArrayList<>()
            ).add(item);
        }

        List<GroupSlot> normalizedSingles = new ArrayList<>();

        for (List<GroupSlot> slotItems : bySlot.values()) {
            List<GroupSlot> collapsed = new ArrayList<>();

            for (GroupSlot candidate : slotItems) {
                boolean merged = false;

                for (int i = 0; i < collapsed.size(); i++) {
                    GroupSlot current = collapsed.get(i);

                    if (canMerge(current.event, candidate.event)) {
                        ScheduleEvent richer =
                                merge(current.event, candidate.event);

                        collapsed.set(
                                i,
                                new GroupSlot(current.group, richer)
                        );
                        mergedFragments++;
                        merged = true;
                        break;
                    }
                }

                if (!merged) {
                    collapsed.add(candidate);
                }
            }

            if (collapsed.size() > 1) {
                unresolvedConflicts += collapsed.size() - 1;
            }

            normalizedSingles.addAll(collapsed);
        }

        Map<String, List<String>> groupsByIdentity =
                new LinkedHashMap<>();
        Map<String, ScheduleEvent> exemplarByIdentity =
                new LinkedHashMap<>();

        for (GroupSlot item : normalizedSingles) {
            String key = identityWithSlot(item.event);
            groupsByIdentity
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(item.group);
            exemplarByIdentity.putIfAbsent(key, item.event);
        }

        List<ScheduleEvent> normalizedEvents = new ArrayList<>();

        for (Map.Entry<String, ScheduleEvent> entry :
                exemplarByIdentity.entrySet()) {
            ScheduleEvent exemplar = entry.getValue();

            List<String> groups =
                    new ArrayList<>(groupsByIdentity.get(entry.getKey()));

            groups.sort(Comparator.comparingInt(
                    group -> {
                        int index = input.groups.indexOf(group);
                        return index < 0 ? Integer.MAX_VALUE : index;
                    }
            ));

            normalizedEvents.add(new ScheduleEvent(
                    exemplar.date,
                    exemplar.pairNumber,
                    exemplar.start,
                    exemplar.end,
                    exemplar.subject,
                    exemplar.teacher,
                    exemplar.room,
                    groups,
                    exemplar.sourcePage
            ));
        }

        normalizedEvents.sort(
                Comparator.comparing(ScheduleEvent::startDateTime)
                        .thenComparingInt(event -> event.pairNumber)
                        .thenComparing(event -> event.subject)
        );

        List<String> diagnostics =
                new ArrayList<>();

        for (String diagnostic : input.diagnostics) {
            if (diagnostic == null) continue;
            if (diagnostic.startsWith("Нормализация:")) continue;
            diagnostics.add(diagnostic);
        }

        diagnostics.add(
                "Нормализация: точных дублей удалено " +
                        exactDuplicates +
                        ", фрагментов объединено " +
                        mergedFragments +
                        ", спорных слотов осталось " +
                        unresolvedConflicts +
                        "."
        );

        ScheduleParser.ParseResult result =
                new ScheduleParser.ParseResult(
                        normalizedEvents,
                        input.groups,
                        input.warnings,
                        diagnostics,
                        input.pageCount
                );

        return new Result(
                result,
                exactDuplicates,
                mergedFragments,
                unresolvedConflicts
        );
    }

    private boolean canMerge(
            ScheduleEvent a,
            ScheduleEvent b
    ) {
        if (!a.date.equals(b.date)) return false;
        if (a.pairNumber != b.pairNumber) return false;
        if (!a.start.equals(b.start) || !a.end.equals(b.end)) return false;

        String as = canonicalSubject(a.subject);
        String bs = canonicalSubject(b.subject);

        boolean subjectCompatible =
                as.equals(bs) ||
                        (!as.isBlank() &&
                                !bs.isBlank() &&
                                (as.contains(bs) || bs.contains(as)));

        if (!subjectCompatible) return false;

        if (!compatibleField(a.teacher, b.teacher)) return false;
        if (!compatibleField(a.room, b.room)) return false;

        return true;
    }

    private ScheduleEvent merge(
            ScheduleEvent a,
            ScheduleEvent b
    ) {
        String subject = richer(a.subject, b.subject);
        String teacher = richer(a.teacher, b.teacher);
        String room = richer(a.room, b.room);

        return new ScheduleEvent(
                a.date,
                a.pairNumber,
                a.start,
                a.end,
                subject,
                teacher,
                room,
                a.groups,
                Math.min(a.sourcePage, b.sourcePage)
        );
    }

    private boolean compatibleField(String a, String b) {
        String ca = canonicalField(a);
        String cb = canonicalField(b);

        return ca.isBlank() ||
                cb.isBlank() ||
                ca.equals(cb);
    }

    private String richer(String a, String b) {
        String left = normalizeText(a);
        String right = normalizeText(b);

        if (left.isBlank()) return right;
        if (right.isBlank()) return left;

        return right.length() > left.length()
                ? right
                : left;
    }

    private String slotKey(
            String group,
            ScheduleEvent event
    ) {
        return group.toLowerCase(Locale.ROOT) +
                "|" +
                event.date +
                "|" +
                event.pairNumber;
    }

    private String identity(ScheduleEvent event) {
        return event.start +
                "|" +
                event.end +
                "|" +
                canonicalSubject(event.subject) +
                "|" +
                canonicalField(event.teacher) +
                "|" +
                canonicalField(event.room);
    }

    private String identityWithSlot(ScheduleEvent event) {
        return event.date +
                "|" +
                event.pairNumber +
                "|" +
                identity(event);
    }

    private String canonicalSubject(String value) {
        return normalizeText(value)
                .toLowerCase(Locale.ROOT)
                .replace("(л)", "")
                .replace("(с)", "")
                .replaceAll("[^a-zа-яё0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String canonicalField(String value) {
        return normalizeText(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zа-яё0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeText(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }
}
