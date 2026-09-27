package com.n3k0.schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SeptemberScheduleRegressionTest {
    private static final String GROUP =
            "10-кЮРо25-4";

    @Test
    public void september25KeepsExpectedSecondAndThirdPairs() {
        List<ScheduleEvent> events =
                new ArrayList<>();

        events.add(new ScheduleEvent(
                LocalDate.of(2026, 9, 25),
                2,
                LocalTime.of(10, 40),
                LocalTime.of(12, 10),
                "Русский язык и культура речи (л)",
                "Ефремова Л.И.",
                "201",
                Collections.singletonList(GROUP),
                7
        ));

        events.add(new ScheduleEvent(
                LocalDate.of(2026, 9, 25),
                3,
                LocalTime.of(12, 50),
                LocalTime.of(14, 20),
                "Конституционное право России (л)",
                "Генслер М.С.",
                "201",
                Collections.singletonList(GROUP),
                7
        ));

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(
                        parseResult(events)
                );

        List<ScheduleEvent> day =
                normalized.parseResult.events;

        assertEquals(2, day.size());
        assertEquals(2, day.get(0).pairNumber);
        assertEquals(
                "Русский язык и культура речи",
                day.get(0).cleanSubject()
        );
        assertEquals("201", day.get(0).room);

        assertEquals(3, day.get(1).pairNumber);
        assertEquals(
                "Конституционное право России",
                day.get(1).cleanSubject()
        );
        assertEquals("201", day.get(1).room);
    }

    @Test
    public void september26RemainsSelfStudyDay() {
        ScheduleEvent marker =
                new ScheduleEvent(
                        LocalDate.of(2026, 9, 26),
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(10, 30),
                        "День самостоятельной работы",
                        "",
                        "",
                        Collections.singletonList(GROUP),
                        8
                );

        ScheduleNormalizer.Result normalized =
                new ScheduleNormalizer().normalize(
                        parseResult(
                                Collections.singletonList(marker)
                        )
                );

        assertEquals(
                1,
                normalized.parseResult.events.size()
        );

        assertTrue(
                normalized.parseResult.events
                        .get(0)
                        .cleanSubject()
                        .toLowerCase()
                        .contains("самостоятельной работы")
        );
    }

    private ScheduleParser.ParseResult parseResult(
            List<ScheduleEvent> events
    ) {
        return new ScheduleParser.ParseResult(
                events,
                Collections.singletonList(GROUP),
                Collections.emptyList(),
                Collections.emptyList(),
                9
        );
    }
}
