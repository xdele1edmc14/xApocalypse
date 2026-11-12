package com.deleted.xapocalypse;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class BStatsManagerTest {

    @Test
    void startsMetricsOnceWithRegisteredPluginId() {
        Plugin plugin = mock(Plugin.class);
        RecordingFactory factory = new RecordingFactory();
        BStatsManager manager = new BStatsManager(factory);

        manager.start(plugin);
        manager.start(plugin);

        assertEquals(1, factory.createCalls);
        assertSame(plugin, factory.plugin);
        assertEquals(35_281, factory.pluginId);
    }

    @Test
    void stopShutsDownActiveMetricsOnce() {
        RecordingFactory factory = new RecordingFactory();
        BStatsManager manager = new BStatsManager(factory);
        manager.start(mock(Plugin.class));

        manager.stop();
        manager.stop();

        assertEquals(1, factory.client.shutdownCalls);
    }

    private static final class RecordingFactory implements BStatsManager.ClientFactory {
        private final RecordingClient client = new RecordingClient();
        private Plugin plugin;
        private int pluginId;
        private int createCalls;

        @Override
        public BStatsManager.Client create(Plugin plugin, int pluginId) {
            this.plugin = plugin;
            this.pluginId = pluginId;
            createCalls++;
            return client;
        }
    }

    private static final class RecordingClient implements BStatsManager.Client {
        private int shutdownCalls;

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }
}
