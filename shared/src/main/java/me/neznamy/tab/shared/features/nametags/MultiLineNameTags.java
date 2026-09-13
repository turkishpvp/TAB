package me.neznamy.tab.shared.features.nametags;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.data.World;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.platform.MultiLineRenderer;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multi-line nametags (lines above and below player's name) rendered by fake entities
 * riding the player. Client moves them together with the player, so no packets are sent
 * on movement. Texts are computed on nametag thread and published as immutable snapshots
 * for the renderer, which only works on network threads.
 */
public class MultiLineNameTags extends RefreshableFeature implements JoinListener, QuitListener, Loadable, UnLoadable,
        WorldSwitchListener, GroupListener, GameModeListener, CustomThreaded {

    @NotNull private final MultiLineConfiguration configuration;
    @NotNull private final NameTag nameTags;
    @Getter @NotNull private final MultiLineRenderer renderer;
    @NotNull private final DisableChecker disableChecker;
    private final StringToComponentCache cache = new StringToComponentCache("MultiLine nametags", 1000);

    /** Whether vanilla nametag is replaced by the "nametag" line */
    private final boolean replacesVanillaTag;

    /** Spacers below the lowest line to place it at vanilla nametag height (or above vanilla tag if it stays) */
    private final int baseSpacers;

    /**
     * Constructs new instance and registers disable condition checker.
     *
     * @param   configuration
     *          Feature configuration
     * @param   nameTags
     *          Nametag feature used for vanilla nametag visibility
     * @param   renderer
     *          Platform's renderer
     */
    public MultiLineNameTags(@NotNull MultiLineConfiguration configuration, @NotNull NameTag nameTags, @NotNull MultiLineRenderer renderer) {
        this.configuration = configuration;
        this.nameTags = nameTags;
        this.renderer = renderer;
        replacesVanillaTag = configuration.getLines().contains(MultiLineConfiguration.NAMETAG_LINE);
        // Vanilla tag (and belowname score under it) stays when it is not replaced, start lines above them
        baseSpacers = replacesVanillaTag ? 2 : TAB.getInstance().getConfiguration().getConfig().getBelowname() != null ? 4 : 3;
        disableChecker = new DisableChecker(this, TAB.getInstance().getPlaceholderManager().getConditionManager().getByNameOrExpression(configuration.getDisableCondition()),
                this::onDisableConditionChange, p -> p.multiLineData.disabled);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.MULTILINE_NAMETAGS + "-Condition", disableChecker);
    }

    @Override
    public void load() {
        for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
            loadPlayer(player);
        }
        renderer.load();
    }

    @Override
    public void unload() {
        renderer.unload();
        for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
            player.multiLineData.lines = null;
            player.teamData.multiLineActive = false;
            nameTags.getVisibilityManager().updateVisibility(player);
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        loadPlayer(connectedPlayer);
        renderer.onJoin(connectedPlayer);
    }

    private void loadPlayer(@NotNull TabPlayer player) {
        MultiLinePlayerData data = player.multiLineData;
        data.baseSpacers = baseSpacers;
        data.spectator = player.getGamemode() == 3;
        data.prefix = player.loadPropertyFromConfig(this, "tagprefix", "");
        data.name = player.loadPropertyFromConfig(this, "customtagname", player.getName());
        data.suffix = player.loadPropertyFromConfig(this, "tagsuffix", "");
        Property[] properties = new Property[configuration.getLines().size()];
        for (int i = 0; i < properties.length; i++) {
            String line = configuration.getLines().get(i);
            if (!line.equals(MultiLineConfiguration.NAMETAG_LINE)) {
                properties[i] = player.loadPropertyFromConfig(this, line, "");
            }
        }
        data.lineProperties = properties;
        data.disabled.set(disableChecker.isDisableConditionMet(player));
        update(player);
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        renderer.onQuit(disconnectedPlayer);
    }

    @NotNull
    @Override
    public String getRefreshDisplayName() {
        return "Updating multi-line nametags";
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        if (refreshed.multiLineData.lineProperties == null) return; // Player not loaded yet (refresh called before onJoin)
        update(refreshed);
    }

    @Override
    public void onGroupChange(@NotNull TabPlayer player) {
        reloadProperties(player);
    }

    @Override
    public void onWorldChange(@NotNull TabPlayer changed, @NotNull World from, @NotNull World to) {
        reloadProperties(changed);
    }

    @Override
    public void onGameModeChange(@NotNull TabPlayer player) {
        boolean spectator = player.getGamemode() == 3;
        if (player.multiLineData.spectator == spectator) return;
        player.multiLineData.spectator = spectator;
        renderer.refreshOwner(player);
        renderer.refreshViewer(player);
    }

    private void reloadProperties(@NotNull TabPlayer player) {
        MultiLinePlayerData data = player.multiLineData;
        if (data.lineProperties == null) return;
        player.updatePropertyFromConfig(data.prefix, "");
        player.updatePropertyFromConfig(data.name, player.getName());
        player.updatePropertyFromConfig(data.suffix, "");
        for (int i = 0; i < data.lineProperties.length; i++) {
            if (data.lineProperties[i] != null) player.updatePropertyFromConfig(data.lineProperties[i], "");
        }
        update(player);
    }

    private void onDisableConditionChange(@NotNull TabPlayer player, boolean disabled) {
        update(player);
    }

    /**
     * Computes lines of player and publishes them to renderer if they changed.
     *
     * @param   player
     *          Player to update
     */
    private void update(@NonNull TabPlayer player) {
        MultiLinePlayerData data = player.multiLineData;
        if (data.lineProperties == null) return; // Player not loaded yet
        boolean active = !data.disabled.get() && !player.teamData.isDisabled();
        String[] lines = null;
        if (active) {
            List<String> visible = new ArrayList<>(data.lineProperties.length);
            for (Property property : data.lineProperties) {
                String text = property == null ? data.prefix.updateAndGet() + data.name.updateAndGet() + data.suffix.updateAndGet() : property.updateAndGet();
                // ponytail: relational placeholders are not resolved in lines, they would require per-viewer texts on network threads
                String legacy = cache.get(text).toLegacyText();
                if (!isVisiblyEmpty(legacy)) visible.add(legacy);
            }
            lines = visible.toArray(new String[0]);
        }
        if (!Arrays.equals(lines, data.lines)) {
            data.lines = lines;
            renderer.refreshOwner(player);
        }
        boolean hideVanilla = active && replacesVanillaTag;
        if (player.teamData.multiLineActive != hideVanilla) {
            player.teamData.multiLineActive = hideVanilla;
            nameTags.getVisibilityManager().updateVisibility(player);
        }
    }

    private static boolean isVisiblyEmpty(@NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++; // Skip color code
            } else if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    @Override
    @NotNull
    public ThreadExecutor getCustomThread() {
        return nameTags.getCustomThread(); // Same thread as team visibility, which lines follow
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "MultiLine NameTags";
    }
}
