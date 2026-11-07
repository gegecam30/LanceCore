package dev.gege.lancercore.hooks;

import dev.gege.lancercore.LancerCore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PAPIExpansion extends PlaceholderExpansion {

    private final LancerCore plugin;

    public PAPIExpansion(LancerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "lancercore"; // Así empiezan tus variables: %lancercore_...%
    }

    @Override
    public @NotNull String getAuthor() {
        return "GeGe";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Mantiene la expansión activa tras un reload de PAPI
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // %lancercore_combo% -> Combo actual (Action Bar)
        if (params.equalsIgnoreCase("combo")) {
            return String.valueOf(plugin.getComboManager().getCombo(player));
        }

        // %lancercore_kills% -> Kills totales (Base de Datos)
        if (params.equalsIgnoreCase("kills")) {
            return String.valueOf(plugin.getDatabaseManager().getKills(player.getUniqueId()));
        }

        // %lancercore_max_combo% -> Récord personal (Base de Datos)
        if (params.equalsIgnoreCase("max_combo")) {
            return String.valueOf(plugin.getDatabaseManager().getMaxCombo(player.getUniqueId()));
        }

        return null; // Placeholder no válido
    }
}