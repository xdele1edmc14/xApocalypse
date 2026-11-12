package com.deleted.xapocalypse;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.Plugin;

/** Owns the lifecycle of xApocalypse's anonymous bStats metrics client. */
final class BStatsManager {

    private static final int PLUGIN_ID = 35_281;

    @FunctionalInterface
    interface Client {
        void shutdown();
    }

    @FunctionalInterface
    interface ClientFactory {
        Client create(Plugin plugin, int pluginId);
    }

    private final ClientFactory clientFactory;
    private Client client;

    BStatsManager() {
        this((plugin, pluginId) -> {
            Metrics metrics = new Metrics(plugin, pluginId);
            return metrics::shutdown;
        });
    }

    BStatsManager(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    void start(Plugin plugin) {
        if (client == null) {
            client = clientFactory.create(plugin, PLUGIN_ID);
        }
    }

    void stop() {
        if (client == null) {
            return;
        }
        Client activeClient = client;
        client = null;
        activeClient.shutdown();
    }
}
