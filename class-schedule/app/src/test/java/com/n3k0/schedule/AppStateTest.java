package com.n3k0.schedule;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AppStateTest {
    @Test
    public void emptyState() {
        assertEquals(
                AppState.Mode.EMPTY,
                AppState.resolveMode(
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void pendingState() {
        assertEquals(
                AppState.Mode.IMPORT_REVIEW,
                AppState.resolveMode(
                        false,
                        false,
                        true,
                        false
                )
        );
    }

    @Test
    public void readyState() {
        assertEquals(
                AppState.Mode.READY,
                AppState.resolveMode(
                        false,
                        true,
                        false,
                        false
                )
        );
    }

    @Test
    public void expiredState() {
        assertEquals(
                AppState.Mode.EXPIRED,
                AppState.resolveMode(
                        false,
                        true,
                        false,
                        true
                )
        );
    }

    @Test
    public void activeScheduleSurvivesPendingImport() {
        assertEquals(
                AppState.Mode.IMPORT_REVIEW_WITH_ACTIVE,
                AppState.resolveMode(
                        false,
                        true,
                        true,
                        false
                )
        );
    }

    @Test
    public void importingTakesPrecedence() {
        assertEquals(
                AppState.Mode.IMPORTING,
                AppState.resolveMode(
                        true,
                        true,
                        true,
                        false
                )
        );
    }
}
