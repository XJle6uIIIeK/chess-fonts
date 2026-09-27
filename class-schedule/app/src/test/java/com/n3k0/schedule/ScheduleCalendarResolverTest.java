package com.n3k0.schedule;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScheduleCalendarResolverTest {
    @Test
    public void reconstructsMissingSplitPageDatesInSeptember() {
        List<LocalDate> explicit = Arrays.asList(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 18),
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 23),
                LocalDate.of(2026, 9, 24),
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 26),
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30)
        );

        List<LocalDate> resolved =
                new ScheduleCalendarResolver()
                        .resolve(explicit, 26);

        assertEquals(26, resolved.size());
        assertTrue(
                resolved.contains(
                        LocalDate.of(2026, 9, 3)
                )
        );
        assertTrue(
                resolved.contains(
                        LocalDate.of(2026, 9, 11)
                )
        );
        assertTrue(
                resolved.contains(
                        LocalDate.of(2026, 9, 22)
                )
        );

        // Sundays are not represented by physical day blocks.
        assertFalse(
                resolved.contains(
                        LocalDate.of(2026, 9, 6)
                )
        );
        assertFalse(
                resolved.contains(
                        LocalDate.of(2026, 9, 13)
                )
        );
        assertFalse(
                resolved.contains(
                        LocalDate.of(2026, 9, 20)
                )
        );
        assertFalse(
                resolved.contains(
                        LocalDate.of(2026, 9, 27)
                )
        );

        assertEquals(
                LocalDate.of(2026, 9, 28),
                resolved.get(23)
        );
    }

    @Test
    public void keepsExplicitCalendarWhenNothingIsMissing() {
        List<LocalDate> explicit = Arrays.asList(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 3)
        );

        List<LocalDate> resolved =
                new ScheduleCalendarResolver()
                        .resolve(explicit, 3);

        assertEquals(explicit, resolved);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAmbiguousCalendarInsteadOfInventingDates() {
        List<LocalDate> explicit =
                new ArrayList<>();

        explicit.add(LocalDate.of(2026, 9, 1));
        explicit.add(LocalDate.of(2026, 9, 30));

        new ScheduleCalendarResolver()
                .resolve(explicit, 29);
    }
}
