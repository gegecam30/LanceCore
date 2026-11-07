package dev.gege.lancercore.listeners;

import dev.gege.lancercore.LancerCore;
import dev.gege.lancercore.managers.MinionManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class SpearCombatListener implements Listener {

    private final LancerCore plugin;
    private final MinionManager minionManager;
    private final Map<UUID, Integer> bleedingTasks = new HashMap<>();
    private final Map<UUID, Long> witherCooldowns = new HashMap<>();
    
    // TANDA 1
    private final Map<UUID, Long> totemCooldowns = new HashMap<>();
    private final Set<UUID> invulnerablePlayers = new HashSet<>();
    
    // TANDA 2 — Daño Reflejado
    private final Map<UUID, MirrorShieldData> mirrorShields = new HashMap<>();
    private final Map<UUID, RefractionData> refractionShields = new HashMap<>();
    private final Map<UUID, UUID> bloodCurses = new HashMap<>(); // Víctima -> Atacante
    private final Set<UUID> reflectingDamage = new HashSet<>(); // Anti-loop flag

    public SpearCombatListener(LancerCore plugin) {
        this.plugin = plugin;
        this.minionManager = new MinionManager(plugin);
    }

    // 1. GHOST REACH
    @EventHandler
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item)) return;
        if (!plugin.getConfig().getBoolean("combat.ghost-reach.enabled")) return;
        int currentCombo = plugin.getComboManager().getCombo(player);
        int reqCombo = plugin.getConfig().getInt("combat.ghost-reach.min-combo-required", 10);
        if (currentCombo < reqCombo) return;
        double range = plugin.getConfig().getDouble("combat.ghost-reach.range", 7.0);
        RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), range, entity -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId()));
        if (result != null && result.getHitEntity() != null) {
            LivingEntity target = (LivingEntity) result.getHitEntity();
            spawnGhostParticleLine(player.getEyeLocation(), target.getEyeLocation());
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 2.0f);
            double dmg = plugin.getConfig().getDouble("combat.ghost-reach.damage", 6.0);
            target.damage(dmg, player);
        }
    }
    private void spawnGhostParticleLine(Location start, Location end) {
        double distance = start.distance(end);
        Vector vector = end.toVector().subtract(start.toVector()).normalize().multiply(0.5);
        for (double i = 0; i < distance; i += 0.5) {
            start.add(vector);
            start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, start, 1, 0, 0, 0, 0);
        }
    }

    // 2. MAIN COMBAT HANDLER
    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item)) return;
        
        // Damage Logic
        if (player.isInsideVehicle()) {
            double mountBonus = plugin.getConfig().getDouble("combat.mount-bonus-multiplier", 2.0);
            if (mountBonus > 1.0) event.setDamage(event.getDamage() * mountBonus);
        }
        double damageMult = plugin.getConfig().getDouble("combat.damage-multiplier", 1.5);
        event.setDamage(event.getDamage() * damageMult);

        // Combo Logic
        plugin.getComboManager().addHit(player);
        int combo = plugin.getComboManager().getCombo(player);
        plugin.getDatabaseManager().updateMaxCombo(player.getUniqueId(), combo);

        // Lightning
        if (plugin.getConfig().getBoolean("visuals.lightning.enabled")) {
            int interval = plugin.getConfig().getInt("visuals.lightning.trigger-interval", 5);
            if (combo % interval == 0) event.getEntity().getWorld().strikeLightningEffect(event.getEntity().getLocation());
        }

        if (event.getEntity() instanceof LivingEntity) {
            LivingEntity target = (LivingEntity) event.getEntity();
            if (plugin.getConfig().getBoolean("combat.bleeding.enabled")) {
                if (Math.random() < plugin.getConfig().getDouble("combat.bleeding.chance", 0.25)) applyBleeding(target);
            }
            if (plugin.getConfig().getBoolean("catalysts.enabled")) handleCatalysts(player, target, event);
        }
    }

    // --- CATALYST LOGIC ---
    private void handleCatalysts(Player player, LivingEntity target, EntityDamageByEntityEvent event) {
        ItemStack offHand = player.getInventory().getItemInOffHand();
        Material mat = offHand.getType();
        String path = "catalysts.";

        // ==================== CATALYSTS ORIGINALES ====================
        
        if (mat == Material.SNOWBALL && plugin.getConfig().getBoolean(path + "snowball.enabled")) {
            target.setFreezeTicks(plugin.getConfig().getInt(path + "snowball.freeze-ticks", 140));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, plugin.getConfig().getInt(path + "snowball.slowness-duration", 3) * 20, plugin.getConfig().getInt(path + "snowball.slowness-amplifier", 1)));
            target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getEyeLocation(), 15, 0.4, 0.4, 0.4, 0.05);
            player.playSound(target.getLocation(), Sound.BLOCK_SNOW_BREAK, 1f, 0.8f);
            if (plugin.getConfig().getBoolean(path + "snowball.consume-item")) consumeItem(player, offHand);
        }
        
        else if (mat == Material.SLIME_BALL && plugin.getConfig().getBoolean(path + "slimeball.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, plugin.getConfig().getInt(path + "slimeball.slowness-duration", 2) * 20, plugin.getConfig().getInt(path + "slimeball.slowness-amplifier", 3)));
            target.getWorld().spawnParticle(Particle.ITEM_SLIME, target.getEyeLocation(), 15, 0.4, 0.4, 0.4, 0.1);
            player.playSound(target.getLocation(), Sound.ENTITY_SLIME_SQUISH, 1f, 1.2f);
            if (plugin.getConfig().getBoolean(path + "slimeball.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.GUNPOWDER && plugin.getConfig().getBoolean(path + "gunpowder.enabled")) {
            event.setDamage(event.getDamage() + plugin.getConfig().getDouble(path + "gunpowder.extra-damage", 2.0));
            Vector direction = player.getLocation().getDirection().normalize().multiply(plugin.getConfig().getDouble(path + "gunpowder.knockback-strength", 1.5)).setY(0.4);
            target.setVelocity(target.getVelocity().add(direction));
            target.getWorld().spawnParticle(Particle.EXPLOSION, target.getEyeLocation(), 1);
            player.playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
            if (plugin.getConfig().getBoolean(path + "gunpowder.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.SPIDER_EYE && plugin.getConfig().getBoolean(path + "spider-eye.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, plugin.getConfig().getInt(path + "spider-eye.duration", 4) * 20, plugin.getConfig().getInt(path + "spider-eye.amplifier", 0)));
            target.getWorld().spawnParticle(Particle.ENTITY_EFFECT, target.getEyeLocation(), 10, 0.3, 0.5, 0.3, 1, Color.GREEN);
            player.playSound(target.getLocation(), Sound.ENTITY_SPIDER_HURT, 1f, 1f);
            if (plugin.getConfig().getBoolean(path + "spider-eye.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.AMETHYST_SHARD && plugin.getConfig().getBoolean(path + "amethyst-shard.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, plugin.getConfig().getInt(path + "amethyst-shard.duration", 6) * 20, plugin.getConfig().getInt(path + "amethyst-shard.amplifier", 0)));
            player.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.5f);
            if (plugin.getConfig().getBoolean(path + "amethyst-shard.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.ECHO_SHARD && plugin.getConfig().getBoolean(path + "echo-shard.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, plugin.getConfig().getInt(path + "echo-shard.duration", 5) * 20, 0));
            player.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
            if (plugin.getConfig().getBoolean(path + "echo-shard.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.FEATHER && plugin.getConfig().getBoolean(path + "feather.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, plugin.getConfig().getInt(path + "feather.duration", 1) * 20, plugin.getConfig().getInt(path + "feather.amplifier", 9)));
            player.playSound(target.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1.5f);
            if (plugin.getConfig().getBoolean(path + "feather.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.GHAST_TEAR && plugin.getConfig().getBoolean(path + "ghast-tear.enabled")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, plugin.getConfig().getInt(path + "ghast-tear.duration", 3) * 20, plugin.getConfig().getInt(path + "ghast-tear.amplifier", 1)));
            player.getWorld().spawnParticle(Particle.HEART, player.getEyeLocation(), 5, 0.3, 0.3, 0.3);
            player.playSound(player.getLocation(), Sound.ENTITY_GHAST_SCREAM, 0.5f, 2.0f);
            if (plugin.getConfig().getBoolean(path + "ghast-tear.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.CHORUS_FRUIT && plugin.getConfig().getBoolean(path + "chorus-fruit.enabled")) {
            double range = plugin.getConfig().getDouble(path + "chorus-fruit.range", 5.0);
            double randomX = (Math.random() * range * 2) - range;
            double randomZ = (Math.random() * range * 2) - range;
            Location newLoc = target.getLocation().add(randomX, 0, randomZ);
            if (newLoc.getBlock().getType().isAir()) {
                target.teleport(newLoc);
                target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 20, 0.5, 1, 0.5);
                player.playSound(target.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1f, 1f);
            }
            if (plugin.getConfig().getBoolean(path + "chorus-fruit.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.PUFFERFISH && plugin.getConfig().getBoolean(path + "pufferfish.enabled")) {
            int duration = plugin.getConfig().getInt(path + "pufferfish.duration", 2) * 20;
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 6)); 
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, duration, 200)); 
            player.playSound(target.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1f, 1f);
            if (plugin.getConfig().getBoolean(path + "pufferfish.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.ROTTEN_FLESH && plugin.getConfig().getBoolean(path + "rotten-flesh.enabled")) {
             if (target instanceof Player) {
                 ((Player) target).addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, plugin.getConfig().getInt(path + "rotten-flesh.duration", 5) * 20, plugin.getConfig().getInt(path + "rotten-flesh.amplifier", 4)));
                 player.playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5f, 0.8f);
                 if (plugin.getConfig().getBoolean(path + "rotten-flesh.consume-item")) consumeItem(player, offHand);
             }
        }

        else if (mat == Material.COBWEB && plugin.getConfig().getBoolean(path + "cobweb.enabled")) {
            Block block = target.getLocation().getBlock();
            if (block.getType() == Material.AIR || block.getType() == Material.GRASS_BLOCK || block.getType() == Material.TALL_GRASS) {
                block.setType(Material.COBWEB);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (block.getType() == Material.COBWEB) {
                            block.setType(Material.AIR);
                        }
                    }
                }.runTaskLater(plugin, plugin.getConfig().getInt(path + "cobweb.remove-after-seconds", 3) * 20L);
                if (plugin.getConfig().getBoolean(path + "cobweb.consume-item")) consumeItem(player, offHand);
            }
        }

        else if (mat == Material.WITHER_SKELETON_SKULL && plugin.getConfig().getBoolean(path + "wither-skeleton-skull.enabled")) {
            long now = System.currentTimeMillis();
            if (witherCooldowns.containsKey(player.getUniqueId())) {
                long lastUse = witherCooldowns.get(player.getUniqueId());
                if (now - lastUse < 2000) return;
            }
            witherCooldowns.put(player.getUniqueId(), now);
            minionManager.spawnWitherMinion(player, target);
            if (plugin.getConfig().getBoolean(path + "wither-skeleton-skull.consume-item")) consumeItem(player, offHand);
        }

        // ==================== TANDA 1 ====================
        
        else if (mat == Material.NAME_TAG && plugin.getConfig().getBoolean(path + "name-tag.enabled")) {
            String originalName = target.getCustomName();
            target.setCustomName(ChatColor.DARK_RED + "⚠ " + (target instanceof Player ? ((Player) target).getName() : target.getType().name()) + " ⚠");
            target.setCustomNameVisible(true);
            
            int duration = plugin.getConfig().getInt(path + "name-tag.duration-seconds", 15);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (target.isValid()) {
                        target.setCustomName(originalName);
                        target.setCustomNameVisible(false);
                    }
                }
            }.runTaskLater(plugin, duration * 20L);
            
            player.playSound(target.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 2.0f);
            target.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, target.getEyeLocation(), 8, 0.5, 0.5, 0.5);
            if (plugin.getConfig().getBoolean(path + "name-tag.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.MAGMA_CREAM && plugin.getConfig().getBoolean(path + "magma-cream.enabled")) {
            int duration = plugin.getConfig().getInt(path + "magma-cream.duration-seconds", 4);
            new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    if (!target.isValid() || target.isDead() || ticks >= duration) {
                        this.cancel();
                        return;
                    }
                    
                    Block block = target.getLocation().getBlock();
                    if (block.getType() == Material.AIR) {
                        block.setType(Material.FIRE);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (block.getType() == Material.FIRE) block.setType(Material.AIR);
                            }
                        }.runTaskLater(plugin, 20L);
                    }
                    
                    target.getWorld().spawnParticle(Particle.FLAME, target.getLocation(), 3, 0.2, 0, 0.2, 0.02);
                    ticks++;
                }
            }.runTaskTimer(plugin, 0L, 20L);
            
            player.playSound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f);
            if (plugin.getConfig().getBoolean(path + "magma-cream.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.NETHER_STAR && plugin.getConfig().getBoolean(path + "nether-star.enabled")) {
            double radius = plugin.getConfig().getDouble(path + "nether-star.radius", 8.0);
            int duration = plugin.getConfig().getInt(path + "nether-star.duration-seconds", 2);
            
            new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    if (!target.isValid() || target.isDead() || ticks >= duration * 4) {
                        this.cancel();
                        return;
                    }
                    
                    for (Entity nearby : target.getNearbyEntities(radius, radius, radius)) {
                        if (nearby instanceof LivingEntity && nearby != player && nearby != target) {
                            Vector direction = target.getLocation().toVector().subtract(nearby.getLocation().toVector()).normalize();
                            nearby.setVelocity(direction.multiply(0.5));
                        }
                    }
                    
                    target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 20, radius, 1, radius, 0.1);
                    ticks++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
            
            player.playSound(target.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.5f);
            if (plugin.getConfig().getBoolean(path + "nether-star.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.TOTEM_OF_UNDYING && plugin.getConfig().getBoolean(path + "totem.enabled")) {
            long now = System.currentTimeMillis();
            long cooldownMs = plugin.getConfig().getInt(path + "totem.cooldown-seconds", 60) * 1000L;
            
            if (totemCooldowns.containsKey(player.getUniqueId())) {
                long timeLeft = (totemCooldowns.get(player.getUniqueId()) + cooldownMs - now) / 1000;
                if (timeLeft > 0) {
                    player.sendMessage(ChatColor.RED + "Totem on cooldown! " + timeLeft + "s remaining");
                    return;
                }
            }
            
            totemCooldowns.put(player.getUniqueId(), now);
            invulnerablePlayers.add(player.getUniqueId());
            
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 30, 0, false, false));
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation(), 30, 0.5, 1, 0.5, 0.1);
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
            
            int duration = plugin.getConfig().getInt(path + "totem.duration-seconds", 2);
            new BukkitRunnable() {
                @Override
                public void run() {
                    invulnerablePlayers.remove(player.getUniqueId());
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                }
            }.runTaskLater(plugin, duration * 20L);
            
            if (plugin.getConfig().getBoolean(path + "totem.consume-item")) consumeItem(player, offHand);
        }

        else if (mat == Material.HEART_OF_THE_SEA && plugin.getConfig().getBoolean(path + "heart-of-the-sea.enabled")) {
            Vector direction = player.getLocation().toVector().subtract(target.getLocation().toVector()).normalize();
            double strength = plugin.getConfig().getDouble(path + "heart-of-the-sea.pull-strength", 2.0);
            target.setVelocity(direction.multiply(strength).setY(0.5));
            
            target.getWorld().spawnParticle(Particle.DRIPPING_WATER, target.getEyeLocation(), 30, 0.5, 0.5, 0.5, 0.1);
            player.playSound(target.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1f, 0.5f);
            
            if (plugin.getConfig().getBoolean(path + "heart-of-the-sea.consume-item")) consumeItem(player, offHand);
        }

        // ==================== TANDA 2 — DAÑO REFLEJADO ====================

        // 19. PRISMARINE SHARD → Mirror Shield (Refleja próximo ataque recibido)
        else if (mat == Material.PRISMARINE_SHARD && plugin.getConfig().getBoolean(path + "prismarine-shard.enabled")) {
            int duration = plugin.getConfig().getInt(path + "prismarine-shard.duration-seconds", 5);
            mirrorShields.put(player.getUniqueId(), new MirrorShieldData(target.getUniqueId(), System.currentTimeMillis() + (duration * 1000L)));
            
            player.sendMessage(ChatColor.AQUA + "✦ Mirror Shield active!");
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 2.0f);
            player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, player.getEyeLocation(), 15, 0.5, 0.5, 0.5);
            
            if (plugin.getConfig().getBoolean(path + "prismarine-shard.consume-item")) consumeItem(player, offHand);
        }

        // 20. PRISMARINE CRYSTALS → Refraction (Redistribuye % del daño recibido)
        else if (mat == Material.PRISMARINE_CRYSTALS && plugin.getConfig().getBoolean(path + "prismarine-crystals.enabled")) {
            int duration = plugin.getConfig().getInt(path + "prismarine-crystals.duration-seconds", 4);
            double percent = plugin.getConfig().getDouble(path + "prismarine-crystals.reflect-percentage", 0.4);
            
            refractionShields.put(player.getUniqueId(), new RefractionData(target.getUniqueId(), percent, System.currentTimeMillis() + (duration * 1000L)));
            
            player.sendMessage(ChatColor.LIGHT_PURPLE + "◈ Refraction Shield active! (" + (int)(percent * 100) + "% reflected)");
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.5f);
            player.getWorld().spawnParticle(Particle.END_ROD, player.getEyeLocation(), 20, 0.3, 0.5, 0.3);
            
            if (plugin.getConfig().getBoolean(path + "prismarine-crystals.consume-item")) consumeItem(player, offHand);
        }

        // 21. NETHER WART → Blood Curse (El objetivo se daña al golpearte)
        else if (mat == Material.NETHER_WART && plugin.getConfig().getBoolean(path + "nether-wart.enabled")) {
            int duration = plugin.getConfig().getInt(path + "nether-wart.duration-seconds", 4);
            bloodCurses.put(target.getUniqueId(), player.getUniqueId());
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    bloodCurses.remove(target.getUniqueId());
                }
            }.runTaskLater(plugin, duration * 20L);
            
            target.getWorld().spawnParticle(Particle.ENTITY_EFFECT, target.getEyeLocation(), 20, 0.3, 0.5, 0.3, 1, Color.MAROON);
            player.playSound(target.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1f, 0.8f);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
            
            if (plugin.getConfig().getBoolean(path + "nether-wart.consume-item")) consumeItem(player, offHand);
        }
    }

    // ==================== TANDA 2 — DAMAGE REFLECTION HANDLERS ====================

    @EventHandler
    public void onReflectiveDamage(EntityDamageByEntityEvent event) {
        // Prevenir loops infinitos
        if (reflectingDamage.contains(event.getEntity().getUniqueId())) return;

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            UUID victimId = victim.getUniqueId();

            // MIRROR SHIELD — Refleja COMPLETAMENTE el próximo golpe
            if (mirrorShields.containsKey(victimId)) {
                MirrorShieldData shield = mirrorShields.get(victimId);
                
                if (System.currentTimeMillis() > shield.expiryTime) {
                    mirrorShields.remove(victimId);
                    return;
                }

                Entity damagerEntity = event.getDamager();
                if (damagerEntity.getUniqueId().equals(shield.targetId)) {
                    event.setCancelled(true);
                    
                    reflectingDamage.add(damagerEntity.getUniqueId());
                    if (damagerEntity instanceof LivingEntity) {
                        ((LivingEntity) damagerEntity).damage(event.getDamage(), victim);
                    }
                    reflectingDamage.remove(damagerEntity.getUniqueId());
                    
                    victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, victim.getEyeLocation(), 15, 0.5, 0.5, 0.5);
                    victim.playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 2.0f);
                    
                    mirrorShields.remove(victimId);
                }
            }

            // REFRACTION SHIELD — Redistribuye un %
            if (refractionShields.containsKey(victimId)) {
                RefractionData shield = refractionShields.get(victimId);
                
                if (System.currentTimeMillis() > shield.expiryTime) {
                    refractionShields.remove(victimId);
                    return;
                }

                Entity damagerEntity = event.getDamager();
                if (damagerEntity.getUniqueId().equals(shield.targetId)) {
                    double reflectedDamage = event.getDamage() * shield.reflectPercent;
                    event.setDamage(event.getDamage() * (1 - shield.reflectPercent));
                    
                    reflectingDamage.add(damagerEntity.getUniqueId());
                    if (damagerEntity instanceof LivingEntity) {
                        ((LivingEntity) damagerEntity).damage(reflectedDamage, victim);
                    }
                    reflectingDamage.remove(damagerEntity.getUniqueId());
                    
                    victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, victim.getEyeLocation(), 8, 0.3, 0.3, 0.3);
                }
            }
        }

        // BLOOD CURSE — El atacante se daña cuando golpea a la víctima cursada
        if (event.getEntity() instanceof LivingEntity && event.getDamager() instanceof Player) {
            UUID victimId = event.getEntity().getUniqueId();
            
            if (bloodCurses.containsKey(victimId)) {
                Player cursedVictimOwner = Bukkit.getPlayer(bloodCurses.get(victimId));
                Player attacker = (Player) event.getDamager();
                
                if (cursedVictimOwner != null && attacker.getUniqueId().equals(cursedVictimOwner.getUniqueId())) {
                    return; // El dueño de la maldición no se daña a sí mismo
                }
                
                double reflectedDamage = event.getDamage();
                
                reflectingDamage.add(attacker.getUniqueId());
                attacker.damage(reflectedDamage);
                reflectingDamage.remove(attacker.getUniqueId());
                
                attacker.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, attacker.getEyeLocation(), 5, 0.2, 0.2, 0.2);
                attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
            }
        }
    }

    // ==================== TOTEM INVULNERABILITY ====================
    @EventHandler
    public void onInvulnerableDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if (invulnerablePlayers.contains(victim.getUniqueId())) {
                event.setCancelled(true);
                victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, victim.getEyeLocation(), 5, 0.3, 0.3, 0.3);
            }
        }
    }

    private void consumeItem(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        item.setAmount(item.getAmount() - 1);
    }
    
    private void applyBleeding(LivingEntity target) {
        UUID uuid = target.getUniqueId();
        if (bleedingTasks.containsKey(uuid)) Bukkit.getScheduler().cancelTask(bleedingTasks.get(uuid));
        int duration = plugin.getConfig().getInt("combat.bleeding.duration-seconds", 3);
        double dmgPerSec = plugin.getConfig().getDouble("combat.bleeding.damage-per-second", 1.0);
        int taskId = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (target.isDead() || !target.isValid() || ticks >= duration) {
                    bleedingTasks.remove(uuid);
                    this.cancel();
                    return;
                }
                target.damage(dmgPerSec);
                target.getWorld().spawnParticle(Particle.BLOCK, target.getEyeLocation(), 10, 0.3, 0.3, 0.3, Bukkit.createBlockData(Material.REDSTONE_BLOCK));
                ticks++;
            }
        }.runTaskTimer(plugin, 20L, 20L).getTaskId();
        bleedingTasks.put(uuid, taskId);
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Entity killerEntity = event.getEntity().getKiller();
        if (!(killerEntity instanceof Player)) return;
        Player player = (Player) killerEntity;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (isSpear(item)) {
            plugin.getDatabaseManager().addKill(player.getUniqueId());
            if (plugin.getConfig().getBoolean("combo.finisher.enabled")) {
                int currentCombo = plugin.getComboManager().getCombo(player);
                int minCombo = plugin.getConfig().getInt("combo.finisher.min-combo-trigger", 5);
                if (currentCombo >= minCombo) {
                    playFinisherSound(player);
                    if (plugin.getConfig().getBoolean("combo.finisher.rewards.enabled")) {
                        List<String> commands = plugin.getConfig().getStringList("combo.finisher.rewards.commands");
                        for (String cmd : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item)) return;
        if (event.getFoodLevel() < player.getFoodLevel()) {
            int cost = plugin.getConfig().getInt("combat.lunge-hunger-cost", 2);
            int newFoodLevel = Math.max(0, player.getFoodLevel() - cost);
            event.setFoodLevel(newFoodLevel);
        }
    }

    private void playFinisherSound(Player player) {
        try {
            String soundName = plugin.getConfig().getString("combo.finisher.sound", "ENTITY_WITHER_SPAWN");
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 0.5f, 0.8f);
        } catch (Exception ignored) {}
    }

    private boolean isSpear(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getType().name().contains("SPEAR");
    }
    
    @EventHandler
    public void onMinionTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof WitherSkeleton && event.getTarget() instanceof Player) {
            WitherSkeleton minion = (WitherSkeleton) event.getEntity();
            Player target = (Player) event.getTarget();
            if (minion.getCustomName() != null && minion.getCustomName().contains(target.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerDamageMinion(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof WitherSkeleton && event.getDamager() instanceof Player) {
            WitherSkeleton minion = (WitherSkeleton) event.getEntity();
            Player attacker = (Player) event.getDamager();
            if (minion.getCustomName() != null && minion.getCustomName().contains(attacker.getName())) {
                event.setCancelled(true);
            }
        }
    }

    // === DATA CLASSES ===
    private static class MirrorShieldData {
        UUID targetId;
        long expiryTime;
        MirrorShieldData(UUID targetId, long expiryTime) {
            this.targetId = targetId;
            this.expiryTime = expiryTime;
        }
    }

    private static class RefractionData {
        UUID targetId;
        double reflectPercent;
        long expiryTime;
        RefractionData(UUID targetId, double reflectPercent, long expiryTime) {
            this.targetId = targetId;
            this.reflectPercent = reflectPercent;
            this.expiryTime = expiryTime;
        }
    }
}