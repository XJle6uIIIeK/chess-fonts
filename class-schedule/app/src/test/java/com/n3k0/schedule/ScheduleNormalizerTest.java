package com.n3k0.schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ScheduleNormalizerTest {
    @Test
    public void exactDuplicatesAreRemoved() {
        ScheduleEvent a = event(
                "10-кЮРо25-4",
                "Конституционное право России (л)",
                "Генслер М.С.",
                "201"
        );

        ScheduleEvent b = event(
                "10-кЮРо25-4",
                "Конституционное   право России (л)",
                "Генслер М.С.",
                "201"
        );

        ScheduleParser.ParseResult source =
                parseResult(Arrays.asList(a, b));

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(source);

        assertEquals(
                1,
                normalized.parseResult.events.size()
        );
        assertEquals(
                1,
                normalized.removedExactDuplicates
        );
    }

    @Test
    public void compatibleFragmentsAreMerged() {
        ScheduleEvent withoutRoom =
                event(
                        "10-кЮРо25-4",
                        "Русский язык и культура речи (л)",
                        "Ефремова Л.И.",
                        ""
                );

        ScheduleEvent withRoom =
                event(
                        "10-кЮРо25-4",
                        "Русский язык и культура речи",
                        "Ефремова Л.И.",
                        "201"
                );

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(
                        parseResult(
                                Arrays.asList(
                                        withoutRoom,
                                        withRoom
                                )
                        )
                );

        assertEquals(
                1,
                normalized.parseResult.events.size()
        );
        assertEquals(
                "201",
                normalized.parseResult.events.get(0).room
        );
        assertTrue(
                normalized.mergedFragments >= 1
        );
    }

    @Test
    public void genuinelyDifferentSubjectsStaySeparate() {
        ScheduleEvent a =
                event(
                        "10-кЮРо25-4",
                        "Русский язык и культура речи",
                        "Ефремова Л.И.",
                        "201"
                );

        ScheduleEvent b =
                event(
                        "10-кЮРо25-4",
                        "Римское право",
                        "Борисова А.А.",
                        "207"
                );

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(
                        parseResult(
                                Arrays.asList(a, b)
                        )
                );

        assertEquals(
                2,
                normalized.parseResult.events.size()
        );
        assertTrue(
                normalized.unresolvedConflicts >= 1
        );
    }

    private ScheduleParser.ParseResult parseResult(
            List<ScheduleEvent> events
    ) {
        return new ScheduleParser.ParseResult(
                events,
                Collections.singletonList("10-кЮРо25-4"),
                Collections.emptyList(),
                Collections.emptyList(),
                9
        );
    }

    private ScheduleEvent event(
            String group,
            String subject,
            String teacher,
            String room
    ) {
        return new ScheduleEvent(
                LocalDate.of(2026, 9, 25),
                2,
                LocalTime.of(10, 40),
                LocalTime.of(12, 10),
                subject,
                teacher,
                room,
                Collections.singletonList(group),
                7
        );
    }
}
