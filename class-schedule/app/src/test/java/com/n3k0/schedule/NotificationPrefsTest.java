package com.n3k0.schedule;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationPrefsTest {
    @Test
    public void repeatMustBeLaterThanMainReminder() {
        NotificationPrefs.Config invalid =
                new NotificationPrefs.Config(
                        true,
                        10,
                        true,
                        30,
                        false,
                        NotificationPrefs.MODE_BEFORE_NEXT,
                        10,
                        true,
                        20,
                        0
                );

        assertFalse(
                NotificationPrefs.isValid(invalid)
        );
    }

    @Test
    public void equalReminderTimesAreRejected() {
        NotificationPrefs.Config invalid =
                new NotificationPrefs.Config(
                        true,
                        10,
                        true,
                        10,
                        false,
                        NotificationPrefs.MODE_BEFORE_NEXT,
                        10,
                        true,
                        20,
                        0
                );

        assertFalse(
                NotificationPrefs.isValid(invalid)
        );
    }

    @Test
    public void mainThirtyRepeatTenIsValid() {
        NotificationPrefs.Config valid =
                new NotificationPrefs.Config(
                        true,
                        30,
                        true,
                        10,
                        true,
                        NotificationPrefs.MODE_BEFORE_NEXT,
                        10,
                        true,
                        20,
                        15
                );

        assertTrue(
                NotificationPrefs.isValid(valid)
        );
    }
}
