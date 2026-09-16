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

import java.util.*;

/**
 * Multi-line nametags (lines above and below player's name) rendered by fake entities
 * riding the player. Client moves them together with the player, so no packets are sent
 * on movement. Texts are computed on nametag thread and published as immutable snapshots
 * for the renderer, which only works on network threads.
 * <p>
 * Lines are only computed for each viewer separately if they contain something viewer-specific
 * (relational placeholders or relational conditions), otherwise all viewers share one snapshot.
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

    /** Condition a line requires to be displayed, indexed like configured lines, {@code null} if the line has none */
    private final Condition[] lineConditions;

    /** Conditions of line cases, indexed by line and case, {@code null} entries for lines without cases */
    private final Condition[][] caseConditions;

    /** Texts of line cases, indexed by line and case, {@code null} entries for lines without cases */
    private final String[][] caseTexts;

    /** Whether any line condition is relational, which makes lines viewer-specific */
    private final boolean relationalConditions;

    /** Condition for showing a player their own lines, {@code null} if the option is off or unconditional */
    @Nullable
    private final Condition showToSelfCondition;

    /**
     * Constructs new instance, loads line conditions and registers disable condition checker.
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
        if (configuration.getFirstLineHeight() != null) {
            firstLineHeight = configuration.getFirstLineHeight();
        } else {
            // Vanilla tag is at 2.3 with belowname score 0.28 under it, start above them if vanilla tag stays
            firstLineHeight = replacesVanillaTag ? 2.34 : TAB.getInstance().getConfiguration().getConfig().getBelowname() != null ? 2.86 : 2.58;
        }

        lineConditions = new Condition[configuration.getLines().size()];
        boolean relational = false;
        for (int i = 0; i < lineConditions.length; i++) {
            String expression = configuration.getLineConditions().get(configuration.getLines().get(i));
            if (expression == null) continue;
            Condition condition = TAB.getInstance().getPlaceholderManager().getConditionManager().getByNameOrExpression(expression);
            if (condition == null) continue;
            lineConditions[i] = condition;
            // Refresh lines when the condition changes value
            addUsedPlaceholder(condition.hasRelationalContent() ? condition.getRelationalPlaceholderIdentifier() : condition.getPlaceholderIdentifier());
            if (condition.hasRelationalContent()) relational = true;
        }
        caseConditions = new Condition[lineConditions.length][];
        caseTexts = new String[lineConditions.length][];
        for (int i = 0; i < lineConditions.length; i++) {
            List<MultiLineConfiguration.LineCase> cases = configuration.getLineCases().get(configuration.getLines().get(i));
            if (cases == null) continue;
            caseConditions[i] = new Condition[cases.size()];
            caseTexts[i] = new String[cases.size()];
            for (int j = 0; j < cases.size(); j++) {
                MultiLineConfiguration.LineCase lineCase = cases.get(j);
                caseTexts[i][j] = lineCase.getText();
                if (lineCase.getCondition() == null) continue;
                Condition condition = TAB.getInstance().getPlaceholderManager().getConditionManager().getByNameOrExpression(lineCase.getCondition());
                if (condition == null) continue;
                caseConditions[i][j] = condition;
                addUsedPlaceholder(condition.hasRelationalContent() ? condition.getRelationalPlaceholderIdentifier() : condition.getPlaceholderIdentifier());
                if (condition.hasRelationalContent()) relational = true;
            }
        }
        relationalConditions = relational;

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
            player.multiLineData.snapshot = MultiLinePlayerData.Snapshot.EMPTY;
            player.teamData.multiLineActive = false;
            nameTags.getVisibilityManager().updateVisibility(player);
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        loadPlayer(connectedPlayer);
        for (TabPlayer owner : TAB.getInstance().getOnlinePlayers()) {
            // Only viewer-specific players need a snapshot for the new viewer, everyone else shares one
            if (owner != connectedPlayer && !owner.multiLineData.snapshot.perViewer.isEmpty()) update(owner);
        }
        renderer.onJoin(connectedPlayer);
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        renderer.onQuit(disconnectedPlayer);
        disconnectedPlayer.multiLineData.snapshot = MultiLinePlayerData.Snapshot.EMPTY;
        for (TabPlayer owner : TAB.getInstance().getOnlinePlayers()) {
            MultiLinePlayerData.Snapshot snapshot = owner.multiLineData.snapshot;
            if (!snapshot.perViewer.containsKey(disconnectedPlayer.getUniqueId())) continue;
            Map<UUID, MultiLinePlayerData.Layout> copy = new HashMap<>(snapshot.perViewer);
            copy.remove(disconnectedPlayer.getUniqueId());
            owner.multiLineData.snapshot = new MultiLinePlayerData.Snapshot(null, Collections.unmodifiableMap(copy));
        }
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
        Property[][] caseProperties = new Property[caseTexts.length][];
        for (int i = 0; i < caseTexts.length; i++) {
            if (caseTexts[i] == null) continue;
            caseProperties[i] = new Property[caseTexts[i].length];
            for (int j = 0; j < caseTexts[i].length; j++) {
                caseProperties[i][j] = new Property(this, player, caseTexts[i][j]);
            }
        }
        data.caseProperties = caseProperties;
        data.lineProperties = properties;
        data.disabled.set(disableChecker.isDisableConditionMet(player));
        update(player);
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
        for (Property property : data.lineProperties) {
            if (property != null) player.updatePropertyFromConfig(property, "");
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
        if (!active) {
            publish(player, MultiLinePlayerData.Snapshot.EMPTY);
        } else {
            data.prefix.update();
            data.name.update();
            data.suffix.update();
            for (Property property : data.lineProperties) {
                if (property != null) property.update();
            }
            for (Property[] cases : data.caseProperties) {
                if (cases == null) continue;
                for (Property property : cases) {
                    property.update();
                }
            }
            if (isViewerSpecific(data)) {
                Map<UUID, MultiLinePlayerData.Layout> layouts = new HashMap<>();
                for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
                    layouts.put(viewer.getUniqueId(), buildLayout(player, viewer));
                }
                publish(player, new MultiLinePlayerData.Snapshot(null, Collections.unmodifiableMap(layouts)));
            } else {
                publish(player, new MultiLinePlayerData.Snapshot(buildLayout(player, player), Collections.emptyMap()));
            }
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

    /**
     * Saves computed snapshot and notifies renderer if it changed.
     *
     * @param   player
     *          Player the snapshot belongs to
     * @param   snapshot
     *          Newly computed snapshot
     */
    private void publish(@NotNull TabPlayer player, @NotNull MultiLinePlayerData.Snapshot snapshot) {
        if (snapshot.equals(player.multiLineData.snapshot)) return;
        player.multiLineData.snapshot = snapshot;
        renderer.refreshOwner(player);
    }

    /**
     * Returns {@code true} if lines can be different for each viewer, which requires a snapshot per viewer.
     *
     * @param   data
     *          Player's data with updated properties
     * @return  {@code true} if lines are viewer-specific, {@code false} if all viewers see the same lines
     */
    private boolean isViewerSpecific(@NotNull MultiLinePlayerData data) {
        if (relationalConditions) return true;
        if (data.prefix.isViewerSpecific() || data.name.isViewerSpecific() || data.suffix.isViewerSpecific()) return true;
        for (Property property : data.lineProperties) {
            if (property != null && property.isViewerSpecific()) return true;
        }
        for (Property[] cases : data.caseProperties) {
            if (cases == null) continue;
            for (Property property : cases) {
                if (property.isViewerSpecific()) return true;
            }
        }
        return false;
    }

    /** Resolves viewer-dependent text on the nametag thread, never on a network thread. */
    @NotNull
    private MultiLinePlayerData.Layout buildLayout(@NotNull TabPlayer player, @NotNull TabPlayer viewer) {
        MultiLinePlayerData data = player.multiLineData;
        List<String> texts = new ArrayList<>(data.lineProperties.length);
        List<Double> spacings = new ArrayList<>(data.lineProperties.length);
        for (int i = 0; i < data.lineProperties.length; i++) {
            if (!isLineVisible(i, player, viewer)) continue;
            String text;
            if (data.caseProperties[i] != null) {
                Property selected = selectCase(i, data.caseProperties[i], player, viewer);
                if (selected == null) continue; // No case matched, line is not displayed
                text = selected.getFormat(viewer);
            } else {
                Property property = data.lineProperties[i];
                text = property == null ? data.prefix.getFormat(viewer) + data.name.getFormat(viewer) + data.suffix.getFormat(viewer) : property.getFormat(viewer);
            }
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

    /**
     * Returns {@code true} if the line at given index passes its configured condition.
     *
     * @param   line
     *          Index of the line in configuration
     * @param   player
     *          Player the lines belong to
     * @param   viewer
     *          Player viewing the lines
     * @return  {@code true} if the line should be displayed, {@code false} if not
     */
    private boolean isLineVisible(int line, @NotNull TabPlayer player, @NotNull TabPlayer viewer) {
        Condition condition = lineConditions[line];
        if (condition == null) return true;
        return condition.hasRelationalContent() ? condition.isMet(viewer, player) : condition.isMet(player);
    }

    /**
     * Returns first text of a line with a met condition, {@code null} if no case matched.
     *
     * @param   line
     *          Index of the line in configuration
     * @param   cases
     *          Texts of the line in configured order
     * @param   player
     *          Player the lines belong to
     * @param   viewer
     *          Player viewing the lines
     * @return  Text to display or {@code null} if the line should be hidden
     */
    @Nullable
    private Property selectCase(int line, @NotNull Property[] cases, @NotNull TabPlayer player, @NotNull TabPlayer viewer) {
        for (int i = 0; i < cases.length; i++) {
            Condition condition = caseConditions[line][i];
            if (condition == null) return cases[i]; // Case without a condition always matches
            if (condition.hasRelationalContent() ? condition.isMet(viewer, player) : condition.isMet(player)) return cases[i];
        }
        return null;
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
