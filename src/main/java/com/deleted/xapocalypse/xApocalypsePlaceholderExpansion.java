package com.deleted.xapocalypse;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** PlaceholderAPI values exposed by xApocalypse when PlaceholderAPI is installed. */
public final class xApocalypsePlaceholderExpansion extends PlaceholderExpansion {
    private final xApocalypse plugin;

    public xApocalypsePlaceholderExpansion(xApocalypse plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "xapocalypse";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "bloodmoon_days_left" -> formatBloodMoonDays(
                    plugin.getBloodMoon().getCachedDaysUntilNextBloodMoon());
            case "zombie_guts_duration" -> player == null ? "0" : Long.toString(
                    plugin.getImmunity().getRemainingSeconds(player.getUniqueId()));
            case "current_scent" -> player == null ? "0" : formatScent(
                    plugin.getPlayerScent(player.getUniqueId()));
            default -> null;
        };
    }

    private String formatBloodMoonDays(int days) {
        return days == 0 ? "Tonight" : Integer.toString(days);
    }

    private String formatScent(double scent) {
        if (!Double.isFinite(scent) || scent <= 0.0) return "0";
        return Long.toString(Math.round(scent));
    }
}
