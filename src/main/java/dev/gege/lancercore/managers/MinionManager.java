package dev.gege.lancercore.managers;

import dev.gege.lancercore.LancerCore;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class MinionManager {

    private final LancerCore plugin;

    public MinionManager(LancerCore plugin) {
        this.plugin = plugin;
    }

    public void spawnWitherMinion(Player owner, LivingEntity target) {
        // 1. CHIVATO: Confirmar que el método se ejecuta
        plugin.getLogger().info("[DEBUG] Intentando spawnear Minion para " + owner.getName());

        Location spawnLoc = target.getLocation();
        
        // Intentamos spawnear
        WitherSkeleton minion = (WitherSkeleton) target.getWorld().spawnEntity(spawnLoc, EntityType.WITHER_SKELETON);

        if (minion == null) {
            // 2. CHIVATO: Error de mundo (Pacífico o WorldGuard)
            plugin.getLogger().warning("[DEBUG] ERROR: El mundo bloqueó el spawn (¿Dificultad Peaceful? ¿WorldGuard?)");
            return;
        }

        // 3. Configurar al Minion
        minion.setCustomName(ChatColor.DARK_RED + "Minion of " + owner.getName());
        minion.setCustomNameVisible(true);
        minion.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
        minion.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));

        // 4. Forzar ataque (IA Fix)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (minion.isValid() && target.isValid()) {
                    minion.setTarget(target);
                }
            }
        }.runTaskLater(plugin, 1L);

        // 5. Auto-Muerte (Tiempo de vida)
        int lifeSeconds = plugin.getConfig().getInt("catalysts.wither-skeleton-skull.lifespan-seconds", 6);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!minion.isDead()) {
                    minion.setHealth(0);
                    minion.getWorld().spawnParticle(Particle.SMOKE, minion.getLocation(), 10, 0.5, 1, 0.5, 0);
                }
            }
        }.runTaskLater(plugin, lifeSeconds * 20L);
        
        plugin.getLogger().info("[DEBUG] Minion spawneado correctamente con ID: " + minion.getEntityId());
    }
}