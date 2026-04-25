package dev.gege.lancercore.data;

import dev.gege.lancercore.LancerCore;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseManager implements Listener {

    private final LancerCore plugin;
    private Connection connection;
    
    // Cache: UUID -> Stats del Jugador
    private final Map<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();

    public DatabaseManager(LancerCore plugin) {
        this.plugin = plugin;
        initConnection();
        initTable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // Clase interna para guardar los datos en memoria
    public static class PlayerStats {
        public int kills = 0;
        public int maxCombo = 0;
        public String selectedParticle = "SOUL_FIRE_FLAME";
        public String selectedStyle = "HELIX"; // Nuevo campo para el estilo
    }

    private void initConnection() {
        try {
            File dataFolder = new File(plugin.getDataFolder(), "database.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().severe("SQL Error: " + e.getMessage());
        }
    }

    private void initTable() {
        // Creamos la tabla con las columnas nuevas
        String sql = "CREATE TABLE IF NOT EXISTS lancer_stats (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "kills INTEGER DEFAULT 0, " +
                "max_combo INTEGER DEFAULT 0, " +
                "selected_particle VARCHAR(32) DEFAULT 'SOUL_FIRE_FLAME', " +
                "selected_style VARCHAR(32) DEFAULT 'HELIX')"; // Columna nueva
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            
            // Migración: Intentamos añadir las columnas por si la tabla es vieja
            try { stmt.execute("ALTER TABLE lancer_stats ADD COLUMN selected_particle VARCHAR(32) DEFAULT 'SOUL_FIRE_FLAME'"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE lancer_stats ADD COLUMN selected_style VARCHAR(32) DEFAULT 'HELIX'"); } catch (SQLException ignored) {}
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerStats stats = new PlayerStats();
            // Cargar defaults desde config
            stats.selectedParticle = plugin.getConfig().getString("visuals.particles.default-type", "SOUL_FIRE_FLAME");
            
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM lancer_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    stats.kills = rs.getInt("kills");
                    stats.maxCombo = rs.getInt("max_combo");
                    if (rs.getString("selected_particle") != null) stats.selectedParticle = rs.getString("selected_particle");
                    if (rs.getString("selected_style") != null) stats.selectedStyle = rs.getString("selected_style");
                }
            } catch (SQLException e) { e.printStackTrace(); }
            statsCache.put(uuid, stats);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        statsCache.remove(event.getPlayer().getUniqueId());
    }

    // --- SETTERS MEJORADOS ---

    public void setSelectedParticle(UUID uuid, String particle) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid, k -> new PlayerStats());
        stats.selectedParticle = particle;
        saveDataAsync(uuid, particle, stats.selectedStyle);
    }

    public void setSelectedStyle(UUID uuid, String style) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid, k -> new PlayerStats());
        stats.selectedStyle = style;
        saveDataAsync(uuid, stats.selectedParticle, style);
    }

    private void saveDataAsync(UUID uuid, String particle, String style) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO lancer_stats (uuid, kills, max_combo, selected_particle, selected_style) VALUES (?, 0, 0, ?, ?) " +
                         "ON CONFLICT(uuid) DO UPDATE SET selected_particle = ?, selected_style = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, particle);
                ps.setString(3, style);
                ps.setString(4, particle);
                ps.setString(5, style);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    // --- GETTERS ---
    public String getSelectedParticle(UUID uuid) { return statsCache.containsKey(uuid) ? statsCache.get(uuid).selectedParticle : "SOUL_FIRE_FLAME"; }
    public String getSelectedStyle(UUID uuid) { return statsCache.containsKey(uuid) ? statsCache.get(uuid).selectedStyle : "HELIX"; }
    
    // Métodos viejos (mantener)
    public void addKill(UUID uuid) { 
        PlayerStats stats = statsCache.computeIfAbsent(uuid, k -> new PlayerStats());
        stats.kills++;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO lancer_stats (uuid, kills, max_combo) VALUES (?, 1, 0) ON CONFLICT(uuid) DO UPDATE SET kills = kills + 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) { ps.setString(1, uuid.toString()); ps.executeUpdate(); } catch (SQLException e) {}
        });
    }
    public void updateMaxCombo(UUID uuid, int newCombo) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid, k -> new PlayerStats());
        if (newCombo > stats.maxCombo) {
            stats.maxCombo = newCombo;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String sql = "INSERT INTO lancer_stats (uuid, kills, max_combo) VALUES (?, 0, ?) ON CONFLICT(uuid) DO UPDATE SET max_combo = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) { ps.setString(1, uuid.toString()); ps.setInt(2, newCombo); ps.setInt(3, newCombo); ps.executeUpdate(); } catch (SQLException e) {}
            });
        }
    }
    public int getKills(UUID uuid) { return statsCache.containsKey(uuid) ? statsCache.get(uuid).kills : 0; }
    public int getMaxCombo(UUID uuid) { return statsCache.containsKey(uuid) ? statsCache.get(uuid).maxCombo : 0; }
    
    public void close() { try { if (connection != null) connection.close(); } catch (SQLException e) {} }
}