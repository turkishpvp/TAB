package me.neznamy.tab.platforms.bukkit.features;

import me.clip.placeholderapi.PlaceholderAPI;
import me.neznamy.tab.platforms.bukkit.BukkitTabPlayer;
import me.neznamy.tab.platforms.bukkit.hook.PhoenixDisguiseHook;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.disguise.DisguiseIdentity;
import me.neznamy.tab.shared.features.types.Loadable;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.PerformanceUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;
import java.util.function.Function;

/**
 * Hides the real identity of players disguised by Phoenix: TAB shows the disguise name and a fake,
 * stable ping (with a little jitter) everywhere it displays them - tablist latency bars, {@code %ping%},
 * {@code %player%}, {@code %displayname%}, nametag team entries and the PlaceholderAPI player placeholders
 * TAB renders ({@code %player_name%}, {@code %player_ping%}, {@code %player_colored_ping%}).
 * <p>
 * Phoenix has no dependency on TAB and TAB none on Phoenix: the disguise state is polled through
 * {@link PhoenixDisguiseHook}, so disguising on join or from another server is picked up the same way.
 */
public class PhoenixDisguiseTracker extends TabFeature implements Loadable {

    /** How often disguise state is checked */
    private static final int CHECK_INTERVAL_MILLIS = 500;

    /** How often a missing Phoenix is looked for again (it may enable after TAB) */
    private static final long HOOK_RETRY_MILLIS = 10_000L;

    /** PlaceholderAPI player placeholders TAB answers itself for disguised players */
    private static final String PAPI_NAME = "%player_name%";
    private static final String PAPI_PING = "%player_ping%";
    private static final String PAPI_COLORED_PING = "%player_colored_ping%";

    @Nullable private PhoenixDisguiseHook hook;
    private long nextHookAttempt;
    private final boolean placeholderAPI;
    private final PingColors pingColors;

    /**
     * Creates the tracker and takes over the name and ping placeholders it masks.
     *
     * @param   placeholderAPI
     *          Whether PlaceholderAPI is installed
     */
    public PhoenixDisguiseTracker(boolean placeholderAPI) {
        this.placeholderAPI = placeholderAPI;
        pingColors = PingColors.load();
        PlaceholderManagerImpl manager = TAB.getInstance().getPlaceholderManager();
        manager.registerPlayerPlaceholder(TabConstants.Placeholder.PLAYER, p -> ((BukkitTabPlayer) p).getShownName());
        if (placeholderAPI) {
            manager.registerPlayerPlaceholder(PAPI_NAME, p -> shown(p, PAPI_NAME, BukkitTabPlayer::getShownName));
            manager.registerPlayerPlaceholder(PAPI_PING, p -> shown(p, PAPI_PING, t -> PerformanceUtil.toString(t.getPing())));
            manager.registerPlayerPlaceholder(PAPI_COLORED_PING, p -> shown(p, PAPI_COLORED_PING, t -> pingColors.color(t.getPing())));
        }
    }

    @Override
    public void load() {
        CpuManager cpu = TAB.getInstance().getCpu();
        cpu.getProcessingThread().repeatTask(new TimedCaughtTask(cpu, this::check, getFeatureName(),
                TabConstants.CpuUsageCategory.DISGUISE_CHANGE), CHECK_INTERVAL_MILLIS);
    }

    /**
     * Value of a PlaceholderAPI placeholder: computed by TAB while the player is disguised,
     * PlaceholderAPI's own value otherwise.
     */
    @NotNull
    private String shown(@NotNull me.neznamy.tab.api.TabPlayer p, @NotNull String identifier,
                         @NotNull Function<BukkitTabPlayer, String> disguised) {
        BukkitTabPlayer player = (BukkitTabPlayer) p;
        if (player.getDisguise() != null) return disguised.apply(player);
        return PlaceholderAPI.setPlaceholders(player.getPlayer(), identifier);
    }

    private void check() {
        PhoenixDisguiseHook phoenix = hook();
        long now = System.currentTimeMillis();
        for (TabPlayer tabPlayer : TAB.getInstance().getOnlinePlayers()) {
            if (!(tabPlayer instanceof BukkitTabPlayer) || !tabPlayer.isLoaded()) continue;
            BukkitTabPlayer player = (BukkitTabPlayer) tabPlayer;
            DisguiseIdentity current = phoenix == null ? null : phoenix.getDisguise(player.getUniqueId());
            DisguiseIdentity previous = player.getDisguise();
            if (!Objects.equals(previous, current)) {
                int previousPing = player.getPing();
                player.setDisguise(current);
                TAB.getInstance().debug("Disguise of " + player.getName() + " changed, now " + (current == null ? "undisguised" : "disguised"));
                refreshPlaceholders(player);
                sendLatency(player, player.getPing(), previousPing);
            } else if (current != null) {
                int ping = current.ping(now);
                if (ping != current.ping(now - CHECK_INTERVAL_MILLIS)) {
                    refreshPlaceholders(player);
                    sendLatency(player, ping, -1);
                }
            }
            // Team entries must carry the name clients see on the player, never the real one
            String expected = player.getShownName();
            if (!expected.equals(player.getNickname())) player.setExpectedProfileName(expected);
        }
    }

    @Nullable
    private PhoenixDisguiseHook hook() {
        if (hook == null && System.currentTimeMillis() >= nextHookAttempt) {
            nextHookAttempt = System.currentTimeMillis() + HOOK_RETRY_MILLIS;
            hook = PhoenixDisguiseHook.create();
            if (hook != null) TAB.getInstance().debug("Hooked into Phoenix disguises");
        }
        return hook;
    }

    /**
     * Pushes new values of the masked placeholders, so features using them refresh right away.
     */
    private void refreshPlaceholders(@NotNull BukkitTabPlayer player) {
        PlaceholderManagerImpl manager = TAB.getInstance().getPlaceholderManager();
        update(manager, player, TabConstants.Placeholder.PLAYER);
        update(manager, player, TabConstants.Placeholder.PING);
        update(manager, player, TabConstants.Placeholder.DISPLAY_NAME);
        if (placeholderAPI) {
            update(manager, player, PAPI_NAME);
            update(manager, player, PAPI_PING);
            update(manager, player, PAPI_COLORED_PING);
        }
    }

    private void update(@NotNull PlaceholderManagerImpl manager, @NotNull BukkitTabPlayer player, @NotNull String identifier) {
        if (manager.getPlaceholder(identifier) instanceof PlayerPlaceholderImpl) {
            ((PlayerPlaceholderImpl) manager.getPlaceholder(identifier)).update(player);
        }
    }

    /**
     * Sends the player's shown latency to everyone (vanilla latency packets are rewritten on the way out as well).
     */
    private void sendLatency(@NotNull BukkitTabPlayer target, int ping, int previousPing) {
        if (ping == previousPing || TAB.getInstance().getFeatureManager().isFeatureEnabled(TabConstants.Feature.PING_SPOOF)) return;
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            viewer.getTabList().updateLatency(target, ping);
        }
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "Phoenix disguise";
    }

    /**
     * Ping colors of PlaceholderAPI's player expansion ({@code expansions.player.ping_value / ping_color}),
     * so a disguised player's colored ping looks exactly like everyone else's.
     */
    private static final class PingColors {

        private final int medium;
        private final int high;
        private final String low;
        private final String mediumColor;
        private final String highColor;

        private PingColors(int medium, int high, String low, String mediumColor, String highColor) {
            this.medium = medium;
            this.high = high;
            this.low = low;
            this.mediumColor = mediumColor;
            this.highColor = highColor;
        }

        @NotNull
        static PingColors load() {
            File file = new File(new File(Bukkit.getWorldContainer(), "plugins/PlaceholderAPI"), "config.yml");
            YamlConfiguration config = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            String path = "expansions.player.";
            return new PingColors(
                    config.getInt(path + "ping_value.medium", 50),
                    config.getInt(path + "ping_value.high", 100),
                    config.getString(path + "ping_color.low", "&a"),
                    config.getString(path + "ping_color.medium", "&e"),
                    config.getString(path + "ping_color.high", "&c"));
        }

        @NotNull
        String color(int ping) {
            String color = ping > high ? highColor : ping > medium ? mediumColor : low;
            return color + ping;
        }
    }

    /**
     * Shown latency of a tablist entry, used when rewriting outgoing latency packets.
     *
     * @param   entryId
     *          UUID of the tablist entry
     * @param   latency
     *          Latency in the packet
     * @return  Latency to send
     */
    public static int shownLatency(@NotNull java.util.UUID entryId, int latency) {
        TAB tab = TAB.getInstance();
        TabPlayer target = tab.getPlayerByTabListUUID(entryId);
        if (target == null) target = tab.getPlayer(entryId);
        if (!(target instanceof BukkitTabPlayer)) return latency;
        BukkitTabPlayer player = (BukkitTabPlayer) target;
        return player.getDisguise() == null ? latency : player.getPing();
    }
}
