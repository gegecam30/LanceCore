package dev.gege.lancercore.managers;

import dev.gege.lancercore.LancerCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ComboManager {

    private final LancerCore plugin;
    // UUID -> Current Combo Count
    private final Map<UUID, Integer> comboMap = new ConcurrentHashMap<>();
    // UUID -> Timestamp of last hit
    private final Map<UUID, Long> lastHitMap = new ConcurrentHashMap<>();
    
    private BukkitTask cleanupTask;

    public ComboManager(LancerCore plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    // --- Core Logic ---

    public void addHit(Player player) {
        UUID uuid = player.getUniqueId();
        int currentCombo = comboMap.getOrDefault(uuid, 0) + 1;
        
        comboMap.put(uuid, currentCombo);
        lastHitMap.put(uuid, System.currentTimeMillis());

        // Visuals Trigger
        handleVisuals(player, currentCombo);
    }

    public int getCombo(Player player) {
        return comboMap.getOrDefault(player.getUniqueId(), 0);
    }

    public void resetCombo(Player player) {
        comboMap.remove(player.getUniqueId());
        lastHitMap.remove(player.getUniqueId());
    }

    // --- Visuals & Sounds ---

    private void handleVisuals(Player player, int combo) {
        // 1. Action Bar
        if (plugin.getConfig().getBoolean("visuals.action-bar")) {
            String msg = plugin.getConfig().getString("combo.messages." + combo);
            if (msg == null) {
                // Default message if specific number isn't defined
                msg = plugin.getConfig().getString("visuals.combo-message", "&6Combo: &e{combo}")
                        .replace("{combo}", String.valueOf(combo));
            }
            // Simple Action Bar (Spigot API)
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                new net.md_5.bungee.api.chat.TextComponent(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', msg)
                ));
        }

        // 2. Rising Pitch Sound
        if (plugin.getConfig().getBoolean("combo.sounds.enabled")) {
            String soundName = plugin.getConfig().getString("combo.sounds.hit-sound", "BLOCK_NOTE_BLOCK_PLING");
            double basePitch = plugin.getConfig().getDouble("combo.sounds.base-pitch", 1.0);
            double increase = plugin.getConfig().getDouble("combo.sounds.pitch-increase", 0.1);
            
            float finalPitch = (float) (basePitch + (combo * increase));
            if (finalPitch > 2.0f) finalPitch = 2.0f; // Minecraft limit

            try {
                Sound sound = Sound.valueOf(soundName);
                player.playSound(player.getLocation(), sound, 1.0f, finalPitch);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid sound in config: " + soundName);
            }
        }
        
        // 3. Particles (To be implemented in VisualManager/ParticleEngine)
        // triggerParticles(player, combo);
    }

    // --- Optimization: Cleanup Task ---
    
    private void startCleanupTask() {
        int resetTimeSeconds = plugin.getConfig().getInt("combo.reset-time-seconds", 5);
        long timeoutMillis = resetTimeSeconds * 1000L;

        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            
            // Remove players whose last hit was too long ago
            for (UUID uuid : lastHitMap.keySet()) {
                if (now - lastHitMap.get(uuid) > timeoutMillis) {
                    comboMap.remove(uuid);
                    lastHitMap.remove(uuid);
                }
            }
        }, 20L, 20L); // Checks every 1 second
    }

    public void shutdown() {
        if (cleanupTask != null) cleanupTask.cancel();
        comboMap.clear();
        lastHitMap.clear();
    }
}