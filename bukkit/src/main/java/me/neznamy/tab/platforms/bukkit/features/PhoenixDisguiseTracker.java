package me.neznamy.tab.platforms.bukkit.features;

import me.clip.placeholderapi.PlaceholderAPI;
import me.neznamy.tab.platforms.bukkit.BukkitTabPlayer;
import me.neznamy.tab.platforms.bukkit.hook.PhoenixDisguiseHook;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.features.NickCompatibility;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.disguise.DisguiseIdentity;
import me.neznamy.tab.shared.features.playerlist.PlayerList;
import me.neznamy.tab.shared.features.types.Loadable;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.features.types.UnLoadable;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.PerformanceUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
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
 * Phoenix's disguise events and tablist re-adds under a new name only make TAB look sooner.
 * <p>
 * Phoenix swaps the tablist entry itself (remove + add under the disguise identity) and that add carries
 * an old display name, while TAB only sends a display name again when the formatted value changes. After
 * every identity change the tablist name and the nametag team are therefore sent to everyone again, right
 * away and twice more once Phoenix's packets have settled, so a previous or real name never sticks.
 */
public class PhoenixDisguiseTracker extends TabFeature implements Loadable, UnLoadable {

    /** How often disguise state is checked */
    private static final int CHECK_INTERVAL_MILLIS = 500;

    /** How often a missing Phoenix is looked for again (it may enable after TAB) */
    private static final long HOOK_RETRY_MILLIS = 10_000L;

    /** Delays of the repeated tablist name and nametag sends after an identity change */
    private static final int[] REASSERT_DELAYS_MILLIS = {1000, 3000};

    /** Delays of the extra checks after a Phoenix disguise event or a tablist re-add (Phoenix applies after both) */
    private static final int[] SOON_CHECK_DELAYS_MILLIS = {100, 600};

    /** Phoenix events that announce a disguise change */
    private static final String[] PHOENIX_EVENTS = {
            "xyz.refinedev.phoenix.utils.events.disguise.ProfileDisguiseEvent",
            "xyz.refinedev.phoenix.utils.events.disguise.ProfileUndisguiseEvent"
    };

    /** Loaded instance, used by the Phoenix event listener and the packet listener (both outlive reloads) */
    @Nullable private static volatile PhoenixDisguiseTracker loaded;

    /** Whether the Phoenix event listener is registered, it stays for the plugin's lifetime */
    private static volatile boolean eventsRegistered;

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
        loaded = this;
        CpuManager cpu = TAB.getInstance().getCpu();
        cpu.getProcessingThread().repeatTask(new TimedCaughtTask(cpu, this::check, getFeatureName(),
                TabConstants.CpuUsageCategory.DISGUISE_CHANGE), CHECK_INTERVAL_MILLIS);
    }

    @Override
    public void unload() {
        if (loaded == this) loaded = null;
    }

    /**
     * Checks disguises again shortly. Used when something hints at a change (Phoenix event, tablist re-add
     * under a new name); safe to call from any thread.
     */
    public static void checkSoon() {
        PhoenixDisguiseTracker tracker = loaded;
        if (tracker == null) return;
        CpuManager cpu = TAB.getInstance().getCpu();
        for (int delay : SOON_CHECK_DELAYS_MILLIS) {
            cpu.getProcessingThread().executeLater(new TimedCaughtTask(cpu, () -> {
                if (loaded == tracker) tracker.check();
            }, tracker.getFeatureName(), TabConstants.CpuUsageCategory.DISGUISE_CHANGE), delay);
        }
    }

    /**
     * Listens to Phoenix's disguise events. Registered once per plugin lifetime, forwards to the loaded tracker.
     *
     * @param   phoenixLoader
     *          Class loader Phoenix's classes were found in
     */
    private static void registerEvents(@NotNull ClassLoader phoenixLoader) {
        if (eventsRegistered) return;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("TAB");
        if (plugin == null) return;
        Listener listener = new Listener() {};
        for (String className : PHOENIX_EVENTS) {
            try {
                Class<? extends Event> type = Class.forName(className, false, phoenixLoader).asSubclass(Event.class);
                Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR,
                        (l, event) -> checkSoon(), plugin, true);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                TAB.getInstance().debug("Phoenix disguise event " + className + " is not available: " + e);
            }
        }
        eventsRegistered = true;
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
            DisguiseIdentity identity = phoenix == null ? null : phoenix.getDisguise(player.getUniqueId());
            DisguiseIdentity previous = player.getDisguise();
            if (!Objects.equals(previous, identity)) {
                int previousPing = player.getPing();
                player.setDisguise(identity);
                TAB.getInstance().debug("Disguise of " + player.getName() + " changed, now " + (identity == null ? "undisguised" : "disguised"));
                refreshPlaceholders(player);
                sendLatency(player, player.getPing(), previousPing);
                updateExpectedName(player);
                reassert(player, identity);
            } else if (identity != null) {
                int ping = identity.ping(now);
                if (ping != identity.ping(now - CHECK_INTERVAL_MILLIS)) {
                    refreshPlaceholders(player);
                    sendLatency(player, ping, -1);
                }
            }
            updateExpectedName(player);
        }
    }

    /**
     * Team entries must carry the name clients see on the player, never the real one.
     */
    private void updateExpectedName(@NotNull BukkitTabPlayer player) {
        String expected = player.getShownName();
        if (!expected.equals(player.getNickname())) player.setExpectedProfileName(expected);
    }

    /**
     * Sends the player's tablist name and nametag team to everyone again, now and after Phoenix's own
     * remove/add packets have settled (the later sends only while the identity is still the same).
     */
    private void reassert(@NotNull BukkitTabPlayer player, @Nullable DisguiseIdentity identity) {
        resendAppearance(player);
        CpuManager cpu = TAB.getInstance().getCpu();
        for (int delay : REASSERT_DELAYS_MILLIS) {
            cpu.getProcessingThread().executeLater(new TimedCaughtTask(cpu, () -> {
                if (loaded != this || !player.isOnline() || !Objects.equals(player.getDisguise(), identity)) return;
                refreshPlaceholders(player);
                resendAppearance(player);
            }, getFeatureName(), TabConstants.CpuUsageCategory.DISGUISE_CHANGE), delay);
        }
    }

    /**
     * Sends the current tablist format and nametag team of the player to every viewer, whether or not TAB
     * thinks they changed: Phoenix's re-add may have replaced what the client shows.
     */
    private void resendAppearance(@NotNull BukkitTabPlayer player) {
        if (!player.isLoaded() || !player.isOnline()) return;
        PlayerList playerList = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PLAYER_LIST);
        if (playerList != null && player.tablistData.prefix != null) {
            player.tablistData.prefix.update();
            player.tablistData.name.update();
            player.tablistData.suffix.update();
            playerList.formatPlayerForEveryone(player, true);
        }
        NickCompatibility nick = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.NICK_COMPATIBILITY);
        if (nick != null && TAB.getInstance().getNameTagManager() != null) nick.processNameChange(player);
    }

    @Nullable
    private PhoenixDisguiseHook hook() {
        if (hook == null && System.currentTimeMillis() >= nextHookAttempt) {
            nextHookAttempt = System.currentTimeMillis() + HOOK_RETRY_MILLIS;
            hook = PhoenixDisguiseHook.create();
            if (hook != null) {
                TAB.getInstance().debug("Hooked into Phoenix disguises");
                registerEvents(hook.getClassLoader());
            }
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
     * Tells whether the display name TAB remembers for a tablist entry belongs to an earlier identity of
     * the player: Phoenix re-added the entry under a profile name the display name does not show (disguise,
     * reroll or undisguise). Forcing it would show the previous - possibly the real - name until TAB's next
     * update, so the add goes out without it.
     *
     * @param   entryId
     *          UUID of the added entry
     * @param   profileName
     *          Profile name in the add packet
     * @param   remembered
     *          Display name TAB last sent for this entry
     * @return  {@code true} if the remembered display name is stale
     */
    public static boolean isStaleDisplayName(@NotNull java.util.UUID entryId, @Nullable String profileName,
                                             @NotNull TabComponent remembered) {
        if (loaded == null || profileName == null || profileName.isEmpty()) return false;
        TAB tab = TAB.getInstance();
        TabPlayer target = tab.getPlayerByTabListUUID(entryId);
        if (target == null) target = tab.getPlayer(entryId);
        if (!(target instanceof BukkitTabPlayer)) return false;
        return !DisguiseIdentity.showsName(remembered.toLegacyText(), profileName);
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
