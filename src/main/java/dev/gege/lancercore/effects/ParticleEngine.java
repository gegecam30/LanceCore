package dev.gege.lancercore.effects;

import dev.gege.lancercore.LancerCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class ParticleEngine extends BukkitRunnable {

    private final LancerCore plugin;
    private double time = 0;

    public ParticleEngine(LancerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("visuals.particles.enabled")) return;

        int minCombo = plugin.getConfig().getInt("visuals.particles.min-combo-trigger", 3);
        double radius = plugin.getConfig().getDouble("visuals.particles.radius", 1.2);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isValid()) continue;

            int combo = plugin.getComboManager().getCombo(player);
            if (combo >= minCombo) {
                
                String particleName = plugin.getDatabaseManager().getSelectedParticle(player.getUniqueId());
                String styleName = plugin.getDatabaseManager().getSelectedStyle(player.getUniqueId());
                
                Particle particle;
                try {
                    // Mapeos de compatibilidad
                    if (particleName.equals("TOTEM")) particleName = "TOTEM_OF_UNDYING";
                    // Compatibilidad con usuarios que ya tengan guardado REDSTONE viejo
                    if (particleName.equals("REDSTONE")) particleName = "DUST"; 
                    
                    particle = Particle.valueOf(particleName);
                } catch (IllegalArgumentException e) {
                    particle = Particle.SOUL_FIRE_FLAME;
                }

                if (styleName.equalsIgnoreCase("HELIX")) {
                    spawnHelix(player, particle, radius);
                } else {
                    spawnAura(player, particle, radius);
                }
            }
        }
        time += 0.2;
    }

    private void spawnHelix(Player player, Particle particle, double radius) {
        Location loc = player.getLocation();
        double x = radius * Math.cos(time);
        double z = radius * Math.sin(time);
        double y = (time % 10) / 5.0; 

        loc.add(x, y, z);
        spawnSmartParticle(loc, particle); 
    }

    private void spawnAura(Player player, Particle particle, double radius) {
        Location loc = player.getLocation();
        for (int i = 0; i < 360; i += 30) {
            double angle = Math.toRadians(i + (time * 20));
            double x = radius * Math.cos(angle);
            double z = radius * Math.sin(angle);
            
            loc.add(x, 0.1, z);
            spawnSmartParticle(loc, particle);
            loc.subtract(x, 0.1, z);
        }
    }

    private void spawnSmartParticle(Location loc, Particle particle) {
        // --- CASO ESPECIAL: DUST (Antes Redstone) ---
        if (particle == Particle.DUST) { 
            try {
                // Color Rojo por defecto
                Particle.DustOptions dustOptions = new Particle.DustOptions(Color.RED, 1.0f);
                loc.getWorld().spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, dustOptions);
            } catch (Exception e) {
                // Fallback silencioso
            }
            return;
        }

        // El resto de partículas (incluida SPELL_WITCH) funcionan normal aquí
        loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0);
    }
}