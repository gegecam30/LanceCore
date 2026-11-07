package dev.gege.lancercore.listeners;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GrappleListener implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey grappleKey;
    
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, AnchorData> activeAnchors = new HashMap<>();
    private final Map<UUID, Long> fallImmunity = new HashMap<>();

    public GrappleListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.grappleKey = new NamespacedKey(plugin, "is_grapple");
    }

    // 1. LANZAMIENTO
    @EventHandler
    public void onTridentThrow(ProjectileLaunchEvent event) {
        if (!plugin.getConfig().getBoolean("grappling-trident.enabled", true)) return;
        
        if (!(event.getEntity() instanceof Trident)) return;
        Trident trident = (Trident) event.getEntity();
        if (!(trident.getShooter() instanceof Player)) return;
        
        Player player = (Player) trident.getShooter();
        
        // Revisar si la config exige la rienda en la mano secundaria
        if (plugin.getConfig().getBoolean("grappling-trident.require-lead-in-offhand", true)) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand.getType() != Material.LEAD) return;
        }

        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(player.getUniqueId())) {
            long timeLeft = (cooldowns.get(player.getUniqueId()) - now) / 1000;
            if (timeLeft > 0) {
                String msg = plugin.getConfig().getString("grappling-trident.messages.on-cooldown", "&e&lGrapple on cooldown! &8({time}s)");
                msg = msg.replace("{time}", String.valueOf(timeLeft));
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', msg)));
                event.setCancelled(true);
                return;
            }
        }

        trident.getPersistentDataContainer().set(grappleKey, PersistentDataType.BYTE, (byte) 1);
    }

    // 2. IMPACTO (Distancia y Cuerda Visual)
    @EventHandler
    public void onTridentHit(ProjectileHitEvent event) {
        if (!plugin.getConfig().getBoolean("grappling-trident.enabled", true)) return;
        
        if (!(event.getEntity() instanceof Trident)) return;
        Trident trident = (Trident) event.getEntity();
        if (!trident.getPersistentDataContainer().has(grappleKey, PersistentDataType.BYTE)) return;
        if (!(trident.getShooter() instanceof Player)) return;

        Player player = (Player) trident.getShooter();
        ItemStack tridentItem = trident.getItem();
        int unbreakingLevel = tridentItem.getEnchantmentLevel(Enchantment.UNBREAKING);
        
        // Leer rangos desde la config
        int maxDist = plugin.getConfig().getInt("grappling-trident.ranges.base", 15); 
        if (unbreakingLevel == 1) maxDist = plugin.getConfig().getInt("grappling-trident.ranges.unbreaking-1", 25);
        else if (unbreakingLevel == 2) maxDist = plugin.getConfig().getInt("grappling-trident.ranges.unbreaking-2", 35);
        else if (unbreakingLevel >= 3) maxDist = plugin.getConfig().getInt("grappling-trident.ranges.unbreaking-3", 50);

        double distance = player.getLocation().distance(trident.getLocation());

        if (distance > maxDist) {
            String msg = plugin.getConfig().getString("grappling-trident.messages.out-of-range", "&c&lTarget too far! &8(Max: {max}m)");
            msg = msg.replace("{max}", String.valueOf(maxDist));
            
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', msg)));
            return;
        }

        if (plugin.getConfig().getBoolean("grappling-trident.require-lead-in-offhand", true)) {
            consumeLead(player);
        }

        // Crear la cuerda visual
        BukkitRunnable particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || trident.isDead() || !trident.isValid()) {
                    this.cancel();
                    activeAnchors.remove(player.getUniqueId());
                    return;
                }
                Location start = player.getLocation().add(0, 1, 0);
                Location end = trident.getLocation();
                Vector dir = end.toVector().subtract(start.toVector());
                double distLine = dir.length();
                dir.normalize();
                
                for (double i = 0; i < distLine; i += 0.5) {
                    Location point = start.clone().add(dir.clone().multiply(i));
                    player.getWorld().spawnParticle(Particle.CRIT, point, 1, 0, 0, 0, 0);
                }
            }
        };
        particleTask.runTaskTimer(plugin, 0L, 2L);

        activeAnchors.put(player.getUniqueId(), new AnchorData(trident, particleTask.getTaskId()));
        
        String anchoredMsg = plugin.getConfig().getString("grappling-trident.messages.anchored", "&a&lAnchored! &7Right-click to pull.");
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', anchoredMsg)));
        player.playSound(player.getLocation(), Sound.ENTITY_LEASH_KNOT_PLACE, 1f, 1.5f);
    }

    // 3. EL IMPULSO (Arrastre Físico Constante)
    @EventHandler
    public void onPlayerPull(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) && activeAnchors.containsKey(player.getUniqueId())) {
            
            AnchorData data = activeAnchors.remove(player.getUniqueId());
            plugin.getServer().getScheduler().cancelTask(data.particleTaskId); // Detener el rayo láser visual
            
            Location targetLoc = data.trident.getLocation();
            
            // Leer valores físicos desde la config
            double pullSpeed = plugin.getConfig().getDouble("grappling-trident.pull-speed-multiplier", 1.8);
            int immunitySeconds = plugin.getConfig().getInt("grappling-trident.fall-immunity-seconds", 8);
            int cooldownSeconds = plugin.getConfig().getInt("grappling-trident.cooldown-seconds", 5);
            
            player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_2, 1f, 1f);
            
            // Motor de tracción continua (BukkitRunnable)
            new BukkitRunnable() {
                int ticks = 0;
                
                @Override
                public void run() {
                    ticks++;
                    
                    // Condición de parada
                    if (!player.isOnline() || player.isDead() || ticks > 80 || player.getLocation().distance(targetLoc) <= 2.0) {
                        player.setGliding(false); // Quitar postura de arrastre
                        fallImmunity.put(player.getUniqueId(), System.currentTimeMillis() + (immunitySeconds * 1000L));
                        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (cooldownSeconds * 1000L));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
                        this.cancel();
                        return;
                    }

                    // Calcular la dirección en tiempo real mientras se mueve
                    Vector direction = targetLoc.toVector().subtract(player.getLocation().toVector()).normalize();
                    
                    // Velocidad constante de arrastre configurada
                    player.setVelocity(direction.multiply(pullSpeed));
                    
                    // Forzar postura horizontal
                    player.setGliding(true);
                    player.setFallDistance(0); 
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }
    }

    // 4. EVITAR DAÑO DE CAÍDA
    @EventHandler
    public void onPlayerFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        Player player = (Player) event.getEntity();
        if (fallImmunity.containsKey(player.getUniqueId())) {
            if (System.currentTimeMillis() < fallImmunity.get(player.getUniqueId())) {
                event.setCancelled(true);
                fallImmunity.remove(player.getUniqueId());
            } else {
                fallImmunity.remove(player.getUniqueId());
            }
        }
    }

    private void consumeLead(Player player) {
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() == Material.LEAD) {
            offHand.setAmount(offHand.getAmount() - 1);
        }
    }

    private static class AnchorData {
        public final Trident trident;
        public final int particleTaskId;

        public AnchorData(Trident trident, int particleTaskId) {
            this.trident = trident;
            this.particleTaskId = particleTaskId;
        }
    }
}