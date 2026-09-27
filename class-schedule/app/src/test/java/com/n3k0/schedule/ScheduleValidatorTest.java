package com.n3k0.schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScheduleValidatorTest {
    private final ScheduleValidator validator = new ScheduleValidator();

    @Test
    public void validScheduleHasNoBlockingErrors() {
        List<String> groups =
                Arrays.asList("10-кЮРо25-1", "10-кЮРо25-2");

        List<ScheduleEvent> events =
                new ArrayList<>();

        for (int day = 1; day <= 8; day++) {
            LocalDate date =
                    LocalDate.of(2026, 9, day);

            events.add(event(
                    date,
                    1,
                    "Теория государства и права",
                    "201",
                    Collections.singletonList("10-кЮРо25-1")
            ));

            events.add(event(
                    date,
                    2,
                    "Римское право",
                    "202",
                    Collections.singletonList("10-кЮРо25-2")
            ));
        }

        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(events, groups)
                );

        assertTrue(result.canActivate());
        assertTrue(result.errorCount == 0);
    }

    @Test
    public void conflictingLessonsAreWarningsNotBlockingErrors() {
        List<String> groups =
                Arrays.asList("10-кЮРо25-1", "10-кЮРо25-2");

        LocalDate date =
                LocalDate.of(2026, 9, 25);

        List<ScheduleEvent> events = Arrays.asList(
                event(
                        date,
                        2,
                        "Русский язык и культура речи",
                        "201",
                        Collections.singletonList("10-кЮРо25-1")
                ),
                event(
                        date,
                        2,
                        "Римское право",
                        "207",
                        Collections.singletonList("10-кЮРо25-1")
                ),
                event(
                        date,
                        3,
                        "Конституционное право России",
                        "201",
                        Collections.singletonList("10-кЮРо25-2")
                )
        );

        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(events, groups)
                );

        assertTrue(result.canActivate());
        assertTrue(hasCode(
                result,
                "CONFLICTING_SLOT"
        ));
    }

    @Test
    public void emptyGroupIsWarningNotBlockingError() {
        List<String> groups =
                Arrays.asList("10-кЮРо25-1", "10-кЮРо25-2");

        List<ScheduleEvent> events =
                Collections.singletonList(
                        event(
                                LocalDate.of(2026, 9, 25),
                                1,
                                "История России",
                                "201",
                                Collections.singletonList("10-кЮРо25-1")
                        )
                );

        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(events, groups)
                );

        assertTrue(result.canActivate());
        assertTrue(hasCode(
                result,
                "EMPTY_GROUP"
        ));
    }

    @Test
    public void noGroupsBlocksActivation() {
        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(
                                Collections.singletonList(
                                        event(
                                                LocalDate.of(2026, 9, 25),
                                                1,
                                                "История России",
                                                "201",
                                                Collections.singletonList("10-кЮРо25-1")
                                        )
                                ),
                                Collections.emptyList()
                        )
                );

        assertFalse(result.canActivate());
        assertTrue(hasCode(
                result,
                "GROUPS_MISSING"
        ));
    }

    @Test
    public void noEventsBlocksActivation() {
        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(
                                Collections.emptyList(),
                                Collections.singletonList("10-кЮРо25-1")
                        )
                );

        assertFalse(result.canActivate());
        assertTrue(hasCode(
                result,
                "EVENTS_MISSING"
        ));
    }

    @Test
    public void nonContiguousMergedGroupsProduceWarning() {
        List<String> groups = Arrays.asList(
                "10-кЮРо25-1",
                "10-кЮРо25-2",
                "10-кЮРо25-3"
        );

        List<ScheduleEvent> events =
                new ArrayList<>();

        for (int day = 1; day <= 8; day++) {
            events.add(event(
                    LocalDate.of(2026, 9, day),
                    1,
                    "Объединённая лекция",
                    "201",
                    Arrays.asList(
                            "10-кЮРо25-1",
                            "10-кЮРо25-3"
                    )
            ));

            events.add(event(
                    LocalDate.of(2026, 9, day),
                    2,
                    "Семинар",
                    "202",
                    Collections.singletonList("10-кЮРо25-2")
            ));
        }

        ScheduleValidator.ValidationResult result =
                validator.validate(
                        parseResult(events, groups)
                );

        assertTrue(result.canActivate());
        assertTrue(hasCode(
                result,
                "NON_CONTIGUOUS_MERGED_GROUPS"
        ));
    }

    private boolean hasCode(
            ScheduleValidator.ValidationResult result,
            String code
    ) {
        for (ScheduleValidationIssue issue :
                result.issues) {
            if (code.equals(issue.code)) {
                return true;
            }
        }

        return false;
    }

    private ScheduleParser.ParseResult parseResult(
            List<ScheduleEvent> events,
            List<String> groups
    ) {
        return new ScheduleParser.ParseResult(
                events,
                groups,
                Collections.emptyList(),
                Collections.emptyList(),
                9
        );
    }

    private ScheduleEvent event(
            LocalDate date,
            int pair,
            String subject,
            String room,
            List<String> groups
    ) {
        LocalTime start;
        LocalTime end;

        switch (pair) {
            case 1:
                start = LocalTime.of(9, 0);
                end = LocalTime.of(10, 30);
                break;
            case 2:
                start = LocalTime.of(10, 40);
                end = LocalTime.of(12, 10);
                break;
            case 3:
                start = LocalTime.of(12, 50);
                end = LocalTime.of(14, 20);
                break;
            case 4:
                start = LocalTime.of(14, 30);
                end = LocalTime.of(16, 0);
                break;
            default:
                start = LocalTime.of(16, 10);
                end = LocalTime.of(17, 40);
                break;
        }

        return new ScheduleEvent(
                date,
                pair,
                start,
                end,
                subject,
                "Преподаватель А.А.",
                room,
                groups,
                0
        );
    }
}
