package dev.gege.lancercore;

import dev.gege.lancercore.commands.LanceCommand;
import dev.gege.lancercore.data.DatabaseManager;
import dev.gege.lancercore.effects.ParticleEngine;
import dev.gege.lancercore.hooks.PAPIExpansion;
import dev.gege.lancercore.listeners.SpearCombatListener;
import dev.gege.lancercore.managers.ComboManager;
import dev.gege.lancercore.managers.ParticleMenuManager; // <--- Importante
import dev.gege.lancercore.listeners.GrappleListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class LancerCore extends JavaPlugin {

    private static LancerCore instance;
    private ComboManager comboManager;
    private DatabaseManager databaseManager;
    private ParticleMenuManager particleMenuManager; // <--- Variable nueva

    @Override
    public void onEnable() {
        instance = this;

        // 1. Cargar Configuración
        saveDefaultConfig();
        getLogger().info("Configuration loaded.");

        // 2. Inicializar Base de Datos
        this.databaseManager = new DatabaseManager(this);

        // 3. Inicializar Managers
        this.comboManager = new ComboManager(this);
        this.particleMenuManager = new ParticleMenuManager(this); // <--- Inicialización nueva

        // 4. Iniciar Partículas (Sync ahora, para seguridad)
        new ParticleEngine(this).runTaskTimer(this, 0L, 2L);

        // 5. Comandos
        if (getCommand("lancercore") != null) {
            getCommand("lancercore").setExecutor(new LanceCommand(this));
        }

        // 6. Eventos
        getServer().getPluginManager().registerEvents(new SpearCombatListener(this), this);
        getServer().getPluginManager().registerEvents(new GrappleListener(this), this);

        // 7. PlaceholderAPI Hook
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            getLogger().info("PlaceholderAPI found! Hooking into it...");
            new PAPIExpansion(this).register();
        }

        getLogger().info("LancerCore ENABLED!");
    }

    @Override
    public void onDisable() {
        if (comboManager != null) comboManager.shutdown();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("LancerCore DISABLED.");
        instance = null;
    }

    // Getters
    public static LancerCore getInstance() { return instance; }
    public ComboManager getComboManager() { return comboManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    
    // El getter que faltaba y causaba el error:
    public ParticleMenuManager getParticleMenuManager() { return particleMenuManager; }
}