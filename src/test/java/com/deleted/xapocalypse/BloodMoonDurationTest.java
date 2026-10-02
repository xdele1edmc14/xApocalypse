package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BloodMoonDurationTest {

    @Test
    void forcedBloodMoonRemainingTimeIgnoresLateNightWorldClockAfterRestart() {
        long durationTicks = 2L * 60L * 20L;
        long startTimeMillis = 1_000_000L;
        long oneSecondAfterRestart = startTimeMillis + 1_000L;

        long remainingTicks = BloodMoonManager.remainingBloodMoonTicks(
                true,
                durationTicks,
                22_000L,
                startTimeMillis,
                oneSecondAfterRestart
        );

        assertEquals(2_380L, remainingTicks);
    }
}
