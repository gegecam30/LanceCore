package dev.gege.lancercore.commands;

import dev.gege.lancercore.LancerCore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class LanceCommand implements CommandExecutor, TabCompleter {

    private final LancerCore plugin;

    public LanceCommand(LancerCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        
        if (args.length > 0) {
            // RELOAD
            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("lancercore.admin")) {
                    sender.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                plugin.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "LancerCore configuration reloaded!");
                return true;
            }

            // PARTICLES
            if (args[0].equalsIgnoreCase("particles")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Players only.");
                    return true;
                }
                // Abrimos el menú
                plugin.getParticleMenuManager().openMenu((Player) sender);
                return true;
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /lc <reload|particles>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("lancercore.admin")) completions.add("reload");
            completions.add("particles");
        }
        return completions;
    }
}