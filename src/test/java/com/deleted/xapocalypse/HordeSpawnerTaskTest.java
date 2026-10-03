package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HordeSpawnerTaskTest {

    @Test
    void fatalJvmErrorsEscapeTheRepeatingTask() {
        xApocalypse plugin = mock(xApocalypse.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HordeSpawnerTaskTest"));
        AssertionError fatal = new AssertionError("fatal VM-level failure");
        doThrow(fatal).when(plugin).debugLog(anyString());

        assertThrows(AssertionError.class, () -> new HordeSpawnerTask(plugin).run());
    }
}
