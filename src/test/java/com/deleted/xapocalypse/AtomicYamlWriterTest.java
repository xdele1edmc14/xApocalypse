package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicYamlWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesInOrderAndFlushesNewestSnapshotBeforeCloseReturns() throws Exception {
        Path target = tempDir.resolve("data.yml");
        List<IOException> errors = new ArrayList<>();

        try (AtomicYamlWriter writer = new AtomicYamlWriter(target, errors::add)) {
            writer.write("value: first\n");
            writer.write("value: second\n");
        }

        assertEquals("value: second\n", Files.readString(target));
        assertTrue(errors.isEmpty());
    }
}
