package dev.gege.lancercore.listeners;

import dev.gege.lancercore.LancerCore;
import dev.gege.lancercore.managers.MinionManager; // <--- ASEGURATE DE TENER ESTE IMPORT
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SpearCombatListener implements Listener {

    private final LancerCore plugin;
    private final MinionManager minionManager; // <--- ESTO FALTABA (Declaración)
    private final Map<UUID, Integer> bleedingTasks = new HashMap<>();
    private final Map<UUID, Long> witherCooldowns = new HashMap<>();

    public SpearCombatListener(LancerCore plugin) {
        this.plugin = plugin;
        this.minionManager = new MinionManager(plugin); // <--- ESTO FALTABA (Inicialización)
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
        String path = "catalysts."; // Shortcut

        // 1. SNOWBALL (Freeze)
        if (mat == Material.SNOWBALL && plugin.getConfig().getBoolean(path + "snowball.enabled")) {
            target.setFreezeTicks(plugin.getConfig().getInt(path + "snowball.freeze-ticks", 140));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, plugin.getConfig().getInt(path + "snowball.slowness-duration", 3) * 20, plugin.getConfig().getInt(path + "snowball.slowness-amplifier", 1)));
            target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getEyeLocation(), 15, 0.4, 0.4, 0.4, 0.05);
            player.playSound(target.getLocation(), Sound.BLOCK_SNOW_BREAK, 1f, 0.8f);
            if (plugin.getConfig().getBoolean(path + "snowball.consume-item")) consumeItem(player, offHand);
        }
        
        // 2. SLIMEBALL (Sticky)
        else if (mat == Material.SLIME_BALL && plugin.getConfig().getBoolean(path + "slimeball.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, plugin.getConfig().getInt(path + "slimeball.slowness-duration", 2) * 20, plugin.getConfig().getInt(path + "slimeball.slowness-amplifier", 3)));
            target.getWorld().spawnParticle(Particle.ITEM_SLIME, target.getEyeLocation(), 15, 0.4, 0.4, 0.4, 0.1);
            player.playSound(target.getLocation(), Sound.ENTITY_SLIME_SQUISH, 1f, 1.2f);
            if (plugin.getConfig().getBoolean(path + "slimeball.consume-item")) consumeItem(player, offHand);
        }

        // 3. GUNPOWDER (Explosion)
        else if (mat == Material.GUNPOWDER && plugin.getConfig().getBoolean(path + "gunpowder.enabled")) {
            event.setDamage(event.getDamage() + plugin.getConfig().getDouble(path + "gunpowder.extra-damage", 2.0));
            Vector direction = player.getLocation().getDirection().normalize().multiply(plugin.getConfig().getDouble(path + "gunpowder.knockback-strength", 1.5)).setY(0.4);
            target.setVelocity(target.getVelocity().add(direction));
            target.getWorld().spawnParticle(Particle.EXPLOSION, target.getEyeLocation(), 1);
            player.playSound(target.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
            if (plugin.getConfig().getBoolean(path + "gunpowder.consume-item")) consumeItem(player, offHand);
        }

        // 4. SPIDER EYE (Poison)
        else if (mat == Material.SPIDER_EYE && plugin.getConfig().getBoolean(path + "spider-eye.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, plugin.getConfig().getInt(path + "spider-eye.duration", 4) * 20, plugin.getConfig().getInt(path + "spider-eye.amplifier", 0)));
            target.getWorld().spawnParticle(Particle.ENTITY_EFFECT, target.getEyeLocation(), 10, 0.3, 0.5, 0.3, 1, Color.GREEN);
            player.playSound(target.getLocation(), Sound.ENTITY_SPIDER_HURT, 1f, 1f);
            if (plugin.getConfig().getBoolean(path + "spider-eye.consume-item")) consumeItem(player, offHand);
        }

        // 5. AMETHYST SHARD (Nausea/Confusion)
        else if (mat == Material.AMETHYST_SHARD && plugin.getConfig().getBoolean(path + "amethyst-shard.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, plugin.getConfig().getInt(path + "amethyst-shard.duration", 6) * 20, plugin.getConfig().getInt(path + "amethyst-shard.amplifier", 0)));
            player.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.5f);
            if (plugin.getConfig().getBoolean(path + "amethyst-shard.consume-item")) consumeItem(player, offHand);
        }

        // 6. ECHO SHARD (Darkness)
        else if (mat == Material.ECHO_SHARD && plugin.getConfig().getBoolean(path + "echo-shard.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, plugin.getConfig().getInt(path + "echo-shard.duration", 5) * 20, 0));
            player.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
            if (plugin.getConfig().getBoolean(path + "echo-shard.consume-item")) consumeItem(player, offHand);
        }

        // 7. FEATHER (Levitation)
        else if (mat == Material.FEATHER && plugin.getConfig().getBoolean(path + "feather.enabled")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, plugin.getConfig().getInt(path + "feather.duration", 1) * 20, plugin.getConfig().getInt(path + "feather.amplifier", 9)));
            player.playSound(target.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1.5f);
            if (plugin.getConfig().getBoolean(path + "feather.consume-item")) consumeItem(player, offHand);
        }

        // 8. GHAST TEAR (Regeneration for Attacker)
        else if (mat == Material.GHAST_TEAR && plugin.getConfig().getBoolean(path + "ghast-tear.enabled")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, plugin.getConfig().getInt(path + "ghast-tear.duration", 3) * 20, plugin.getConfig().getInt(path + "ghast-tear.amplifier", 1)));
            player.getWorld().spawnParticle(Particle.HEART, player.getEyeLocation(), 5, 0.3, 0.3, 0.3);
            player.playSound(player.getLocation(), Sound.ENTITY_GHAST_SCREAM, 0.5f, 2.0f);
            if (plugin.getConfig().getBoolean(path + "ghast-tear.consume-item")) consumeItem(player, offHand);
        }

        // 9. CHORUS FRUIT (Random Teleport)
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

        // 10. PUFFERFISH (Paralysis)
        else if (mat == Material.PUFFERFISH && plugin.getConfig().getBoolean(path + "pufferfish.enabled")) {
            int duration = plugin.getConfig().getInt(path + "pufferfish.duration", 2) * 20;
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 6)); 
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, duration, 200)); 
            player.playSound(target.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1f, 1f);
            if (plugin.getConfig().getBoolean(path + "pufferfish.consume-item")) consumeItem(player, offHand);
        }

        // 11. ROTTEN FLESH (Hunger)
        else if (mat == Material.ROTTEN_FLESH && plugin.getConfig().getBoolean(path + "rotten-flesh.enabled")) {
             if (target instanceof Player) {
                 ((Player) target).addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, plugin.getConfig().getInt(path + "rotten-flesh.duration", 5) * 20, plugin.getConfig().getInt(path + "rotten-flesh.amplifier", 4)));
                 player.playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5f, 0.8f);
                 if (plugin.getConfig().getBoolean(path + "rotten-flesh.consume-item")) consumeItem(player, offHand);
             }
        }

        // 12. COBWEB (Temporary Trap)
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

        // 13. WITHER SKULL (Minion con Cooldown)
        else if (mat == Material.WITHER_SKELETON_SKULL && plugin.getConfig().getBoolean(path + "wither-skeleton-skull.enabled")) {
            
            // --- NUEVO: LÓGICA DE COOLDOWN ---
            long now = System.currentTimeMillis();
            // Si ya usó la habilidad recientemente...
            if (witherCooldowns.containsKey(player.getUniqueId())) {
                long lastUse = witherCooldowns.get(player.getUniqueId());
                // 2000 milisegundos = 2 segundos de espera
                if (now - lastUse < 2000) { 
                    return; // ¡ALTO! No spawnees nada, todavía está cargando.
                }
            }
            // Actualizamos el tiempo de último uso
            witherCooldowns.put(player.getUniqueId(), now);
            // ----------------------------------

            // Llamamos al Manager (Tu código limpio)
            minionManager.spawnWitherMinion(player, target);

            if (plugin.getConfig().getBoolean(path + "wither-skeleton-skull.consume-item")) consumeItem(player, offHand);
        }
    }

    private void consumeItem(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        item.setAmount(item.getAmount() - 1);
    }
    
    // --- (Bleeding Task) ---
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
    // --- NUEVO: SEGURIDAD DEL MINION ---
    
    // 1. Evitar que el Minion ataque a su dueño (IA Fix)
    @EventHandler
    public void onMinionTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        // Si la entidad es un Wither Skeleton y quiere atacar a un Jugador...
        if (event.getEntity() instanceof WitherSkeleton && event.getTarget() instanceof Player) {
            WitherSkeleton minion = (WitherSkeleton) event.getEntity();
            Player target = (Player) event.getTarget();

            // Verificamos si es NUESTRO minion (por el nombre)
            if (minion.getCustomName() != null && minion.getCustomName().contains(target.getName())) {
                event.setCancelled(true); // ¡Quieto! Ese es tu papá/mamá.
            }
        }
    }

    // 2. Evitar que TÚ mates a tu Minion por error (Fuego Amigo / Sweeping Edge)
    @EventHandler
    public void onPlayerDamageMinion(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof WitherSkeleton && event.getDamager() instanceof Player) {
            WitherSkeleton minion = (WitherSkeleton) event.getEntity();
            Player attacker = (Player) event.getDamager();

            if (minion.getCustomName() != null && minion.getCustomName().contains(attacker.getName())) {
                event.setCancelled(true); // No lastimes a tu propio súbdito
            }
        }
    }
}