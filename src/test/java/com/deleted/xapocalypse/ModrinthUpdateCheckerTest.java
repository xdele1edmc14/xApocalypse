package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ModrinthUpdateCheckerTest {

    @Test
    void returnsNewestListedReleaseThatIsNewerThanCurrentVersion() {
        String response = """
                [
                  {"version_number":"1.8.0-beta.1","version_type":"beta","status":"listed"},
                  {"version_number":"1.10.0","version_type":"release","status":"listed"},
                  {"version_number":"1.9.0","version_type":"release","status":"listed"},
                  {"version_number":"2.0.0","version_type":"release","status":"archived"}
                ]
                """;

        Optional<String> update = ModrinthUpdateChecker.findAvailableRelease(response, "1.7.2");

        assertEquals(Optional.of("1.10.0"), update);
    }

    @Test
    void returnsEmptyWhenRunningVersionIsCurrentOrNewer() {
        String response = """
                [{"version_number":"1.7.2","version_type":"release","status":"listed"}]
                """;

        assertEquals(Optional.empty(),
                ModrinthUpdateChecker.findAvailableRelease(response, "1.7.2"));
        assertEquals(Optional.empty(),
                ModrinthUpdateChecker.findAvailableRelease(response, "1.8.0"));
    }

    @Test
    void semanticComparisonHandlesMultiDigitAndPrereleaseVersions() {
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.10.0", "1.9.9"));
        assertTrue(ModrinthUpdateChecker.isNewerVersion("1.7.2", "1.7.2-beta.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2-beta.1", "1.7.2"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2", "1.7.2"));
    }

    @Test
    void semanticComparisonRejectsInvalidVersionsAndHandlesUnboundedNumbers() {
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7", "1.6.0"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2-", "1.7.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2-01", "1.7.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2-alpha..1", "1.7.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("1.7.2-α", "1.7.1"));
        assertFalse(ModrinthUpdateChecker.isNewerVersion("١.٨.٠", "1.7.2"));

        assertDoesNotThrow(() -> assertTrue(ModrinthUpdateChecker.isNewerVersion(
                "1.7.2-999999999999999999999999999999999999",
                "1.7.2-2")));
    }

    @Test
    void malformedResponseFailsClosedWithoutInventingAnUpdate() {
        assertEquals(Optional.empty(),
                ModrinthUpdateChecker.findAvailableRelease("not json", "1.7.2"));
        assertEquals(Optional.empty(),
                ModrinthUpdateChecker.findAvailableRelease("[]", "1.7.2"));
    }

    @Test
    void checkUsesInjectedClientAndPropagatesTransportFailure() {
        RuntimeException failure = new RuntimeException("offline");
        ModrinthUpdateChecker checker = new ModrinthUpdateChecker(
                userAgent -> CompletableFuture.failedFuture(failure));

        CompletableFuture<Optional<String>> result = checker.check("1.7.2");

        assertEquals(failure, result.handle((ignored, error) -> error.getCause()).join());
    }
}
