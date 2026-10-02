package com.deleted.xapocalypse;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void immunitySaveNeverCallsBlockingFileConfigurationSave() throws Exception {
        xApocalypse plugin = pluginMock();
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));
        immunity.load();
        setField(immunity, "dataConfig", new RejectBlockingSaveYaml());

        try {
            assertDoesNotThrow(immunity::save);
        } finally {
            closeIfPresent(immunity);
        }
    }

    @Test
    void bloodMoonSaveNeverCallsBlockingFileConfigurationSave() throws Exception {
        xApocalypse plugin = pluginMock();
        BloodMoonManager bloodMoon = new BloodMoonManager(plugin);
        bloodMoon.load(tempDir.toFile());
        setField(bloodMoon, "bloodMoonDataConfig", new RejectBlockingSaveYaml());

        try {
            assertDoesNotThrow(bloodMoon::save);
        } finally {
            closeIfPresent(bloodMoon);
        }
    }

    @Test
    void immunitySerializationFailureDoesNotEscapeGameplayThread() throws Exception {
        xApocalypse plugin = pluginMock();
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));
        immunity.load();
        setField(immunity, "dataConfig", new RejectSerializationYaml());

        try {
            assertDoesNotThrow(immunity::save);
        } finally {
            closeIfPresent(immunity);
        }
    }

    private xApocalypse pluginMock() {
        xApocalypse plugin = mock(xApocalypse.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("xApocalypse-test"));
        return plugin;
    }

    private void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private void closeIfPresent(Object owner) throws Exception {
        try {
            Method close = owner.getClass().getDeclaredMethod("close");
            close.invoke(owner);
        } catch (NoSuchMethodException ignored) {
            // The red phase runs against managers that do not own async writers yet.
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }

    private static final class RejectBlockingSaveYaml extends YamlConfiguration {
        @Override
        public void save(File file) throws IOException {
            throw new AssertionError("Blocking FileConfiguration.save(File) reached the caller thread");
        }
    }

    private static final class RejectSerializationYaml extends YamlConfiguration {
        @Override
        public String saveToString() {
            throw new IllegalStateException("broken YAML value");
        }
    }
}
