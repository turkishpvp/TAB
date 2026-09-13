package me.neznamy.tab.platforms.bukkit;

import me.neznamy.tab.platforms.bukkit.platform.BukkitPlatform;
import me.neznamy.tab.platforms.bukkit.platform.FoliaPlatform;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Main class for Bukkit.
 */
public class BukkitTAB extends JavaPlugin {

    @Override
    public void onEnable() {
        boolean folia = ReflectionUtils.classExists("io.papermc.paper.threadedregions.RegionizedServer");
        try {
            TAB.create(folia ? new FoliaPlatform(this) : new BukkitPlatform(this));
        } catch (IllegalStateException e) {
            Bukkit.getConsoleSender().sendMessage("§c[TAB] ================================================================================");
            Bukkit.getConsoleSender().sendMessage("§c[TAB] Your server version (" + Bukkit.getBukkitVersion() + ") is not supported.");
            Bukkit.getConsoleSender().sendMessage("§c[TAB] This build only supports 1.8.8, other server versions were removed from it.");
            Bukkit.getConsoleSender().sendMessage("§c[TAB] Thrown error message: " + e.getMessage());
            Bukkit.getConsoleSender().sendMessage("§c[TAB] ================================================================================");
        }
    }

    @Override
    public void onDisable() {
        if (TAB.getInstance() == null) return;
        TAB.getInstance().unload();
    }
}