package dev.gege.lancercore.managers;

import dev.gege.lancercore.LancerCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

public class ParticleMenuManager implements Listener {

    private final LancerCore plugin;
    private final String MENU_TITLE = ChatColor.DARK_BLUE + "Particles & Styles";

    public ParticleMenuManager(LancerCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, MENU_TITLE);

        // --- SECCIÓN 1: TIPOS DE PARTÍCULAS ---
        addItem(inv, 10, Material.BLAZE_POWDER, "&6Flame", "TYPE:FLAME");
        addItem(inv, 11, Material.SOUL_TORCH, "&bSoul Fire", "TYPE:SOUL_FIRE_FLAME");
        addItem(inv, 12, Material.REDSTONE, "&cRedstone", "TYPE:DUST"); 
        addItem(inv, 13, Material.TOTEM_OF_UNDYING, "&aTotem", "TYPE:TOTEM_OF_UNDYING");
        addItem(inv, 14, Material.NETHER_STAR, "&fCrit Magic", "TYPE:CRIT_MAGIC");
        
        // CAMBIO: Reemplazamos Dragon Breath por Witch Magic (Que funciona 100%)
        addItem(inv, 15, Material.FERMENTED_SPIDER_EYE, "&5Dark Magic", "TYPE:SPELL_WITCH");
        
        addItem(inv, 16, Material.ECHO_SHARD, "&3Sonic Boom", "TYPE:SONIC_BOOM");

        // --- SECCIÓN 2: ESTILOS ---
        addItem(inv, 29, Material.HOPPER, "&eStyle: &lHELIX (Espiral)", "STYLE:HELIX");
        addItem(inv, 31, Material.ENDER_EYE, "&eStyle: &lAURA (Círculo)", "STYLE:AURA");

        addItem(inv, 35, Material.BARRIER, "&cClose", "CLOSE");

        player.openInventory(inv);
    }

    private void addItem(Inventory inv, int slot, Material mat, String name, String hiddenData) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        meta.setLore(Arrays.asList(ChatColor.BLACK + "ID:" + hiddenData, ChatColor.GRAY + "Click to select"));
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(MENU_TITLE)) return;
        event.setCancelled(true);

        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        
        if (clicked.hasItemMeta() && clicked.getItemMeta().hasLore()) {
            List<String> lore = clicked.getItemMeta().getLore();
            String idLine = lore.get(0); 
            String rawData = idLine.substring(idLine.indexOf(":") + 1);

            if (rawData.equals("CLOSE")) {
                player.closeInventory();
                return;
            }

            // CAMBIAR TIPO
            if (rawData.startsWith("TYPE:")) {
                String particleId = rawData.split(":")[1];
                
                // VERIFICACIÓN DE PERMISOS (Ya implementada)
                // El jugador necesita "lancercore.particles.spell_witch" o "lancercore.particles.all"
                if (!player.hasPermission("lancercore.particles." + particleId.toLowerCase()) 
                    && !player.hasPermission("lancercore.particles.all")) {
                    player.sendMessage(ChatColor.RED + "No permission!");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
                    return;
                }

                plugin.getDatabaseManager().setSelectedParticle(player.getUniqueId(), particleId);
                player.sendMessage(ChatColor.GREEN + "Particle selected: " + particleId);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
            }

            // CAMBIAR ESTILO
            if (rawData.startsWith("STYLE:")) {
                String styleId = rawData.split(":")[1];
                plugin.getDatabaseManager().setSelectedStyle(player.getUniqueId(), styleId);
                player.sendMessage(ChatColor.YELLOW + "Style changed to: " + styleId);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 2.0f);
            }
            
            player.closeInventory();
        }
    }
}