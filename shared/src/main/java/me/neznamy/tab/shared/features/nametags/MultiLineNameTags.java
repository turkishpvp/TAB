package me.neznamy.tab.shared.features.nametags;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.data.World;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.platform.MultiLineRenderer;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Height of the lowest line above player's feet */
    private final double firstLineHeight;

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
    /** Condition for showing a player their own lines, {@code null} if the feature is off or unconditional */
    @Nullable
    private final Condition showToSelfCondition;

    public MultiLineNameTags(@NotNull MultiLineConfiguration configuration, @NotNull NameTag nameTags, @NotNull MultiLineRenderer renderer) {
        this.configuration = configuration;
        this.nameTags = nameTags;
        this.renderer = renderer;
        replacesVanillaTag = configuration.getLines().contains(MultiLineConfiguration.NAMETAG_LINE);
        if (configuration.getFirstLineHeight() != null) {
            firstLineHeight = configuration.getFirstLineHeight();
        } else {
            // Vanilla tag is at 2.3 with belowname score 0.28 under it, start above them if vanilla tag stays
            firstLineHeight = replacesVanillaTag ? 2.34 : TAB.getInstance().getConfiguration().getConfig().getBelowname() != null ? 2.86 : 2.58;
        }
        showToSelfCondition = configuration.isShowToSelf()
                ? TAB.getInstance().getPlaceholderManager().getConditionManager().getByNameOrExpression(configuration.getShowToSelfCondition())
                : null;
        if (showToSelfCondition != null) addUsedPlaceholder(showToSelfCondition.getPlaceholderIdentifier());
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
            player.multiLineData.layouts = Collections.emptyMap();
            player.teamData.multiLineActive = false;
            nameTags.getVisibilityManager().updateVisibility(player);
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        loadPlayer(connectedPlayer);
        for (TabPlayer owner : TAB.getInstance().getOnlinePlayers()) {
            if (owner != connectedPlayer) update(owner);
        }
        renderer.onJoin(connectedPlayer);
    }

    private void loadPlayer(@NotNull TabPlayer player) {
        MultiLinePlayerData data = player.multiLineData;
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
        disconnectedPlayer.multiLineData.layouts = Collections.emptyMap();
        for (TabPlayer owner : TAB.getInstance().getOnlinePlayers()) {
            Map<UUID, MultiLinePlayerData.Layout> layouts = new HashMap<>(owner.multiLineData.layouts);
            layouts.remove(disconnectedPlayer.getUniqueId());
            owner.multiLineData.layouts = Collections.unmodifiableMap(layouts);
        }
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
        Map<UUID, MultiLinePlayerData.Layout> layouts = new HashMap<>();
        if (active) {
            data.prefix.update();
            data.name.update();
            data.suffix.update();
            for (Property property : data.lineProperties) {
                if (property != null) property.update();
            }
            for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
                layouts.put(viewer.getUniqueId(), buildLayout(data, viewer));
            }
        }
        if (!layouts.equals(data.layouts)) {
            data.layouts = Collections.unmodifiableMap(layouts);
            renderer.refreshOwner(player);
        }
        if (configuration.isShowToSelf()) {
            boolean selfView = active && (showToSelfCondition == null || showToSelfCondition.isMet(player));
            if (data.selfView != selfView) {
                data.selfView = selfView;
                renderer.setSelfView(player, selfView);
            }
        }
        boolean hideVanilla = active && replacesVanillaTag;
        if (player.teamData.multiLineActive != hideVanilla) {
            player.teamData.multiLineActive = hideVanilla;
            nameTags.getVisibilityManager().updateVisibility(player);
        }
    }

    /** Resolves viewer-dependent text on the nametag thread, never on a network thread. */
    @NotNull
    private MultiLinePlayerData.Layout buildLayout(@NotNull MultiLinePlayerData data, @NotNull TabPlayer viewer) {
        List<String> texts = new ArrayList<>(data.lineProperties.length);
        List<Double> spacings = new ArrayList<>(data.lineProperties.length);
        for (int i = 0; i < data.lineProperties.length; i++) {
            Property property = data.lineProperties[i];
            String text = property == null ? data.prefix.getFormat(viewer) + data.name.getFormat(viewer) + data.suffix.getFormat(viewer) : property.getFormat(viewer);
            // List values in groups.yml/users.yml are joined with new lines, every entry is a separate line
            String[] parts = text.split("\n", -1);
            int lastVisible = -1;
            for (String part : parts) {
                String legacy = cache.get(part).toLegacyText();
                if (isVisiblyEmpty(legacy)) continue; // Empty lines take no space
                if (texts.size() == MultiLineConfiguration.MAX_LINES) break; // Limited by fake entity id block
                texts.add(legacy);
                spacings.add(configuration.getLineSpacing());
                lastVisible = spacings.size() - 1;
            }
            // Custom spacing of a property applies below its last line
            if (lastVisible != -1) spacings.set(lastVisible, configuration.getSpacingBelow(configuration.getLines().get(i)));
        }
        double[] heights = new double[texts.size()];
        for (int i = 0; i < heights.length - 1; i++) {
            heights[i] = spacings.get(i);
        }
        if (heights.length > 0) heights[heights.length - 1] = firstLineHeight;
        return new MultiLinePlayerData.Layout(texts.toArray(new String[0]), heights, configuration.isLowerWhenSneaking());
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
