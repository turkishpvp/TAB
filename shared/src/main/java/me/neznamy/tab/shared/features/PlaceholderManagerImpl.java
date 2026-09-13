package me.neznamy.tab.shared.features;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.api.placeholder.Placeholder;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.TabConstants.CpuUsageCategory;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.placeholders.PlaceholderIdentifier;
import me.neznamy.tab.shared.placeholders.PlaceholderReference;
import me.neznamy.tab.shared.placeholders.PlaceholderRefreshConfiguration;
import me.neznamy.tab.shared.placeholders.PlaceholderRefreshTask;
import me.neznamy.tab.shared.placeholders.conditions.ConditionManager;
import me.neznamy.tab.shared.placeholders.expansion.EmptyTabExpansion;
import me.neznamy.tab.shared.placeholders.expansion.TabExpansion;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.types.RelationalPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.types.ServerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.types.TabPlaceholder;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.DumpUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Messy class for placeholder management
 */
@Getter
public class PlaceholderManagerImpl extends RefreshableFeature implements PlaceholderManager, JoinListener, Loadable, Dumpable {

    private static final Pattern placeholderPattern = Pattern.compile("%([^%]*)%");

    @NotNull
    private final PlaceholderRefreshConfiguration configuration;

    private final Map<String, PlaceholderReference> registeredPlaceholders = new ConcurrentHashMap<>();
    private final Map<Pattern, Function<String, Function<Matcher, Placeholder>>> registeredDynamicPlaceholders = new LinkedHashMap<>(); // guarded by this

    private volatile PlaceholderReference[] usedPlaceholders = new PlaceholderReference[0];

    private long loopTime;

    /** Placeholders due for refresh which could not be submitted yet because previous cycle is still running (processing thread only) */
    private final Set<PlaceholderReference> pendingRefresh = new LinkedHashSet<>();

    /** Whether a refresh cycle is currently running */
    private volatile boolean refreshInFlight;

    /** Maximum amount of unknown placeholders registered because they were found in another placeholder's output */
    private static final int MAX_NESTED_UNKNOWN_PLACEHOLDERS = 500;

    /** Amount of unknown placeholders registered from placeholder outputs */
    private int nestedUnknownPlaceholders;

    @NotNull private final TabExpansion tabExpansion;

    private final CpuManager cpu;

    /** Placeholders which are refreshed on backend server */
    private final Map<String, Integer> bridgePlaceholders = new ConcurrentHashMap<>();

    private final ConditionManager conditionManager = new ConditionManager();

    /**
     * Constructs new instance.
     *
     * @param   cpu
     *          CPU manager for submitting tasks
     * @param   configuration
     *          Placeholder refreshing configuration
     */
    public PlaceholderManagerImpl(@NotNull CpuManager cpu, @NotNull PlaceholderRefreshConfiguration configuration) {
        this.cpu = cpu;
        this.configuration = configuration;
        tabExpansion = TAB.getInstance().getConfiguration().getConfig().getPlaceholders().isRegisterTabExpansion() ?
                TAB.getInstance().getPlatform().createTabExpansion() : new EmptyTabExpansion();
    }

    private void refresh() {
        loopTime += TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL;
        for (PlaceholderReference placeholder : usedPlaceholders) {
            if (placeholder.getRefresh() == -1 || loopTime % placeholder.getRefresh() != 0) continue;
            pendingRefresh.add(placeholder);
        }
        // Previous cycle still running, due placeholders wait in pendingRefresh instead of piling up tasks in unbounded queues
        if (pendingRefresh.isEmpty() || refreshInFlight) return;
        refreshInFlight = true;
        PlaceholderRefreshTask task = new PlaceholderRefreshTask(new ArrayList<>(pendingRefresh));
        pendingRefresh.clear();
        cpu.getPlaceholderThread().execute(new TimedCaughtTask(cpu, () -> {
            boolean handedOff = false;
            try {
                // Run in placeholder refreshing thread
                task.run();

                // Back to main thread
                cpu.getProcessingThread().execute(() -> {
                    try {
                        processRefreshResults(task);
                    } finally {
                        refreshInFlight = false;
                    }
                });
                handedOff = true;
            } finally {
                if (!handedOff) refreshInFlight = false;
            }
        }, getFeatureName(), CpuUsageCategory.PLACEHOLDER_REQUEST));
    }

    private void processRefreshResults(@NotNull PlaceholderRefreshTask task) {
        long time = System.nanoTime();
        Map<RefreshableFeature, Collection<TabPlayer>> update = new HashMap<>();
        for (RefreshableFeature f : updateServerPlaceholders(task.getServerPlaceholderResults())) {
            update.put(f, new HashSet<>(TAB.getInstance().getData().values()));
        }
        updatePlayerPlaceholders(task.getPlayerPlaceholderResults(), update);
        Map<RefreshableFeature, Collection<TabPlayer>> forceUpdate = updateRelationalPlaceholders(task.getRelationalPlaceholderResults());
        cpu.addTime(getFeatureName(), CpuUsageCategory.PLACEHOLDER_SAVE, System.nanoTime() - time);
        cpu.addPlaceholderTimes(task.getUsedTime());

        refreshFeatures(forceUpdate, update);
    }
    
    private void refreshFeatures(@NotNull Map<RefreshableFeature, Collection<TabPlayer>> forceUpdate, @NotNull Map<RefreshableFeature, Collection<TabPlayer>> update) {
        for (Entry<RefreshableFeature, Collection<TabPlayer>> entry : update.entrySet()) {
            TimedCaughtTask task = new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
                for (TabPlayer player : entry.getValue()) {
                    entry.getKey().refresh(player, false);
                }
            }, entry.getKey().getFeatureName(), entry.getKey().getRefreshDisplayName());
            if (entry.getKey() instanceof CustomThreaded) {
                ((CustomThreaded) entry.getKey()).getCustomThread().execute(task);
            } else {
                task.run();
            }
        }
        for (Entry<RefreshableFeature, Collection<TabPlayer>> entry : forceUpdate.entrySet()) {
            TimedCaughtTask task = new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
                for (TabPlayer player : entry.getValue()) {
                    entry.getKey().refresh(player, true);
                }
            }, entry.getKey().getFeatureName(), entry.getKey().getRefreshDisplayName());
            if (entry.getKey() instanceof CustomThreaded) {
                ((CustomThreaded) entry.getKey()).getCustomThread().execute(task);
            } else {
                task.run();
            }
        }
    }

    @NotNull
    private Map<RefreshableFeature, Collection<TabPlayer>> updateRelationalPlaceholders(
            @Nullable Map<RelationalPlaceholderImpl, Map<TabPlayer, Map<TabPlayer, String>>> results) {
        if (results == null) return Collections.emptyMap();
        Map<RefreshableFeature, Collection<TabPlayer>> update = new HashMap<>();
        for (Entry<RelationalPlaceholderImpl, Map<TabPlayer, Map<TabPlayer, String>>> entry : results.entrySet()) {
            RelationalPlaceholderImpl placeholder = entry.getKey();
            for (Entry<TabPlayer, Map<TabPlayer, String>> viewerResult : entry.getValue().entrySet()) {
                TabPlayer viewer = viewerResult.getKey();
                if (!viewer.isOnline()) continue; // Player disconnected in the meantime while refreshing in another thread
                for (Entry<TabPlayer, String> targetResult : viewerResult.getValue().entrySet()) {
                    TabPlayer target = targetResult.getKey();
                    if (!target.isOnline()) continue; // Player disconnected in the meantime while refreshing in another thread
                    if (placeholder.hasValueChanged(viewer, target, targetResult.getValue())) {
                        placeholder.updateParents(target);
                        for (RefreshableFeature f : placeholder.getReference().getUsedByFeatures()) {
                            update.computeIfAbsent(f, c -> new HashSet<>()).add(target);
                        }
                    }
                }
            }
        }
        return update;
    }

    private void updatePlayerPlaceholders(@NotNull Map<PlayerPlaceholderImpl, Map<TabPlayer, String>> results,
                                          @NotNull Map<RefreshableFeature, Collection<TabPlayer>> update) {
        if (results.isEmpty()) return;
        for (Entry<PlayerPlaceholderImpl, Map<TabPlayer, String>> entry : results.entrySet()) {
            PlayerPlaceholderImpl placeholder = entry.getKey();
            for (Entry<TabPlayer, String> playerResult : entry.getValue().entrySet()) {
                TabPlayer player = playerResult.getKey();
                if (!player.isOnline()) continue; // Player disconnected in the meantime while refreshing in another thread
                if (placeholder.hasValueChanged(player, playerResult.getValue(), true)) {
                    placeholder.updateParents(player);
                    for (RefreshableFeature f : placeholder.getReference().getUsedByFeatures()) {
                        update.computeIfAbsent(f, c -> new HashSet<>()).add(player);
                    }
                    if (placeholder.getIdentifier().equals(TabConstants.Placeholder.VANISHED)) {
                        TAB.getInstance().getFeatureManager().onVanishStatusChange(player);
                    }
                    if (placeholder.getIdentifier().equals(TabConstants.Placeholder.GAMEMODE)) {
                        TAB.getInstance().getFeatureManager().onGameModeChange(player);
                    }
                }
            }
        }
    }

    @NotNull
    private Set<RefreshableFeature> updateServerPlaceholders(@NotNull Map<ServerPlaceholderImpl, String> results) {
        Set<RefreshableFeature> set = new HashSet<>();
        for (Entry<ServerPlaceholderImpl, String> entry : results.entrySet()) {
            ServerPlaceholderImpl placeholder = entry.getKey();
            if (placeholder.hasValueChanged(entry.getValue())) {
                set.addAll(placeholder.getReference().getUsedByFeatures());
                for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
                    placeholder.updateParents(all);
                }
            }
        }
        return set;
    }

    /**
     * Returns collection of all currently registered placeholders.
     *
     * @return  collection of all currently registered placeholders
     */
    @NotNull
    public Collection<Placeholder> getAllPlaceholders() {
        return registeredPlaceholders.values().stream().map(PlaceholderReference::getHandle).collect(Collectors.toList());
    }

    /**
     * Registers placeholder into the system.
     *
     * @param   placeholder
     *          Placeholder to register
     * @return  Registered placeholder (input)
     * @param   <T>
     *          Specific placeholder class
     */
    public <T extends Placeholder> PlaceholderReference registerPlaceholder(@NotNull T placeholder) {
        PlaceholderReference existing;
        synchronized (this) {
            existing = registeredPlaceholders.get(placeholder.getIdentifier());
            if (existing == null) {
                PlaceholderReference reference = new PlaceholderReference(placeholder.getIdentifier(), (TabPlaceholder) placeholder);
                ((TabPlaceholder) placeholder).setReference(reference);
                registeredPlaceholders.put(placeholder.getIdentifier(), reference);
                return reference;
            }
            // Reference must be set before publishing the handle, otherwise other threads may see null reference
            ((TabPlaceholder) placeholder).setReference(existing);
            existing.setHandle((TabPlaceholder) placeholder);
        }
        // Refresh outside of the lock, inline refreshes parse placeholders which would invert lock order
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            if (!p.isLoaded()) continue;
            for (RefreshableFeature f : existing.getUsedByFeatures()) {
                TimedCaughtTask task = new TimedCaughtTask(cpu, () -> f.refresh(p, true), f.getFeatureName(), f.getRefreshDisplayName());
                if (f instanceof CustomThreaded) {
                    ((CustomThreaded) f).getCustomThread().execute(task);
                } else {
                    task.run();
                }
            }
        }
        return existing;
    }

    @Override
    public void load() {
        // Start refreshing only once all features are loaded (runTask is queued until CPU manager is enabled),
        // otherwise refreshes reach half-loaded players during reload
        cpu.runTask(() -> cpu.getProcessingThread().repeatTask(new TimedCaughtTask(cpu, this::refresh, getFeatureName(), CpuUsageCategory.PLACEHOLDER_REFRESH_INIT),
                TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL));
        for (PlaceholderReference pl : usedPlaceholders) {
            if (pl.getHandle() instanceof ServerPlaceholderImpl) {
                ((ServerPlaceholderImpl)pl.getHandle()).update();
            }
        }
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            onJoin(p);
        }
    }

    /**
     * Detects placeholders in text using %% pattern and platform-specific syntax,
     * returning list of all detected identifiers
     *
     * @param   text
     *          text to detect placeholders in
     * @return  list of detected identifiers
     */
    @NotNull
    public static List<String> detectPlaceholders(@NonNull String text) {
        List<String> detectedPlaceholders = new ArrayList<>(detectPercentPlaceholders(text));
        detectedPlaceholders.addAll(TAB.getInstance().getPlatform().detectAdditionalPlaceholders(text));

        // The two detection methods return placeholders in their own local order,
        // but merging them by appending breaks the order as they appear in the
        // original text. Reorder by iterating through the string and finding
        // placeholders in the actual order they appear.
        List<String> orderedPlaceholders = new ArrayList<>();
        String remaining = text;

        while (!detectedPlaceholders.isEmpty()) {
            // Find which placeholder appears first in the remaining text
            String nextPlaceholder = null;
            int nextIndex = Integer.MAX_VALUE;

            for (String placeholder : detectedPlaceholders) {
                int idx = remaining.indexOf(placeholder);
                if (idx != -1 && idx < nextIndex) {
                    nextIndex = idx;
                    nextPlaceholder = placeholder;
                }
            }

            // If no more placeholders found, add remaining ones
            if (nextPlaceholder == null) {
                orderedPlaceholders.addAll(detectedPlaceholders);
                break;
            }

            // Add the found placeholder and remove it from further consideration
            orderedPlaceholders.add(nextPlaceholder);
            detectedPlaceholders.remove(nextPlaceholder);

            // Move past this occurrence to find next placeholders in order
            remaining = remaining.substring(nextIndex + nextPlaceholder.length());
        }

        return orderedPlaceholders;
    }

    @NotNull
    private static List<String> detectPercentPlaceholders(@NonNull String text) {
        if (!text.contains("%")) return Collections.emptyList();
        if (text.charAt(0) == '%' && text.charAt(text.length()-1) == '%') {
            int count = 0;
            char[] array = text.toCharArray();
            for (char c : array) {
                if (c == '%') {
                    count++;
                }
            }
            if (count == 2) return Collections.singletonList(text);
        }
        List<String> placeholders = new ArrayList<>();
        Matcher m = placeholderPattern.matcher(text);
        while (m.find()) {
            placeholders.add(m.group());
        }
        return placeholders;
    }

    /**
     * Marks placeholder as used by specified feature.
     *
     * @param   identifier
     *          Placeholder to mark as used
     * @param   feature
     *          Feature using the placeholder
     */
    public synchronized void addUsedPlaceholder(@NonNull String identifier, @NonNull RefreshableFeature feature) {
        if (getPlaceholder(identifier).getReference().addUsedFeature(feature)) {
            recalculateUsedPlaceholders();
            TabPlaceholder p = getPlaceholder(identifier);
            for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
                all.expansionData.setPlaceholderValue(p.getIdentifier(), p.getLastValueSafe(all));
            }
        }
    }

    /**
     * Marks placeholder as used by specified feature.
     *
     * @param   placeholder
     *          Placeholder to mark as used
     * @param   feature
     *          Feature using the placeholder
     */
    public synchronized void addUsedPlaceholder(@NonNull TabPlaceholder placeholder, @NonNull RefreshableFeature feature) {
        if (placeholder.getReference().addUsedFeature(feature)) {
            recalculateUsedPlaceholders();
            for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
                all.expansionData.setPlaceholderValue(placeholder.getIdentifier(), placeholder.getLastValueSafe(all));
            }
        }
    }

    /**
     * Removes feature from all placeholders using it, typically when feature is unregistered.
     *
     * @param   feature
     *          Feature to remove
     */
    public synchronized void removeUsedFeature(@NonNull RefreshableFeature feature) {
        for (PlaceholderReference reference : registeredPlaceholders.values()) {
            reference.removeUsedFeature(feature);
        }
        recalculateUsedPlaceholders();
    }

    /**
     * Updates array of used placeholders.
     */
    private void recalculateUsedPlaceholders() {
        usedPlaceholders = registeredPlaceholders.values().stream().filter(p -> !p.getUsedByFeatures().isEmpty()).toArray(PlaceholderReference[]::new);
    }

    /**
     * Finds replacement for specified placeholder and output.
     *
     * @param   placeholder
     *          Placeholder to find replacement for
     *
     * @param   output
     *          Output the placeholder has returned
     * @return  New output based on configuration, may be identical to {@code output}
     */
    @NotNull
    public String findReplacement(@NonNull String placeholder, @NonNull String output) {
        return getPlaceholderReference(placeholder).getHandle().getReplacements().findReplacement(output);
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        for (PlaceholderReference p : usedPlaceholders) {
            if (p.getHandle() instanceof ServerPlaceholderImpl) { // server placeholders don't update on join
                connectedPlayer.expansionData.setPlaceholderValue(p.getIdentifier(), p.getHandle().getLastValue(null));
            }
        }
        // Initialize to avoid onVanishStatusChange being called in the loop after joining because previous value was null
        ((PlayerPlaceholderImpl)registeredPlaceholders.get(TabConstants.Placeholder.VANISHED).getHandle()).update(connectedPlayer);
    }

    @NotNull
    @Override
    public String getRefreshDisplayName() {
        return "Other";
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        // Condition or placeholder only used in tab expansion, do nothing for now
    }

    /**
     * Returns placeholder with given identifier. If no such placeholder is registered,
     * returns {@code null}.
     *
     * @param   identifier
     *          Placeholder identifier
     * @return  Registered placeholder with given identifier, if present
     */
    @Nullable
    public PlaceholderReference getPlaceholderRaw(@NotNull String identifier) {
        return registeredPlaceholders.get(identifier);
    }

    @NotNull
    public ServerPlaceholderImpl registerServerPlaceholder(@NonNull String identifier, @NonNull Supplier<String> supplier) {
        return registerServerPlaceholder(identifier, configuration.getRefreshInterval(identifier), supplier);
    }

    @NotNull
    public PlayerPlaceholderImpl registerPlayerPlaceholder(@NonNull String identifier,
                                                           @NonNull Function<me.neznamy.tab.api.TabPlayer, String> function) {
        return registerPlayerPlaceholder(identifier, configuration.getRefreshInterval(identifier), function);
    }

    @NotNull
    public RelationalPlaceholderImpl registerRelationalPlaceholder(
            @NonNull String identifier,
            @NonNull BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, String> function
    ) {
        return registerRelationalPlaceholder(identifier, configuration.getRefreshInterval(identifier), function);
    }

    /**
     * Parses all placeholders in the given text for the given player.
     *
     * @param   text
     *          Text to parse
     * @param   player
     *          Player to parse for
     * @return  Text with all placeholders replaced
     */
    @NotNull
    public String parsePlaceholders(@NotNull String text, @NotNull TabPlayer player) {
        String output = text;
        for (String placeholder : detectPlaceholders(text)) {
            TabPlaceholder p = getPlaceholder(placeholder);
            String value = p.getLastValueSafe(player);
            output = output.replace(placeholder, value);
        }
        return output;
    }

    // ------------------
    // API Implementation
    // ------------------

    @Override
    @NotNull
    public ServerPlaceholderImpl registerServerPlaceholder(@NonNull String identifier, int refresh, @NonNull Supplier<String> supplier) {
        ensureActive();
        bridgePlaceholders.remove(identifier);
        return (ServerPlaceholderImpl) registerPlaceholder(new ServerPlaceholderImpl(identifier, refresh, supplier)).getHandle();
    }

    @Override
    @NotNull
    public PlayerPlaceholderImpl registerPlayerPlaceholder(@NonNull String identifier, int refresh,
                                                           @NonNull Function<me.neznamy.tab.api.TabPlayer, String> function) {
        ensureActive();
        bridgePlaceholders.remove(identifier);
        return (PlayerPlaceholderImpl) registerPlaceholder(new PlayerPlaceholderImpl(identifier, refresh, function)).getHandle();
    }

    @Override
    @NotNull
    public RelationalPlaceholderImpl registerRelationalPlaceholder(@NonNull String identifier, int refresh,
                                                                   @NonNull BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, String> function) {
        ensureActive();
        bridgePlaceholders.remove(identifier);
        return (RelationalPlaceholderImpl) registerPlaceholder(new RelationalPlaceholderImpl(identifier, refresh, function)).getHandle();
    }

    @Override
    public synchronized void registerServerPlaceholder(@NonNull Pattern identifier, int refresh, @NonNull Function<Matcher, Supplier<String>> function) {
        ensureActive();
        registeredDynamicPlaceholders.put(identifier, id -> groups -> new ServerPlaceholderImpl(id, refresh, function.apply(groups)));
    }

    @Override
    public synchronized void registerPlayerPlaceholder(@NonNull Pattern identifier, int refresh,
                                          @NonNull Function<Matcher, Function<me.neznamy.tab.api.TabPlayer, String>> function) {
        ensureActive();
        registeredDynamicPlaceholders.put(identifier, id -> groups -> new PlayerPlaceholderImpl(id, refresh, function.apply(groups)));
    }

    @Override
    public synchronized void registerRelationalPlaceholder(@NonNull Pattern identifier, int refresh,
                                              @NonNull Function<Matcher, BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, String>> function) {
        ensureActive();
        registeredDynamicPlaceholders.put(identifier, id -> groups -> new RelationalPlaceholderImpl(id, refresh, function.apply(groups)));
    }

    @NotNull
    public PlayerPlaceholderImpl registerBridgePlaceholder(@NonNull String identifier, int backendRefresh) {
        ensureActive();
        bridgePlaceholders.put(identifier, backendRefresh);
        return (PlayerPlaceholderImpl) registerPlaceholder(new PlayerPlaceholderImpl(identifier, -1, player -> null)).getHandle();
    }

    @NotNull
    public RelationalPlaceholderImpl registerRelationalBridgePlaceholder(@NonNull String identifier, int backendRefresh) {
        ensureActive();
        bridgePlaceholders.put(identifier, backendRefresh);
        return (RelationalPlaceholderImpl) registerPlaceholder(new RelationalPlaceholderImpl(identifier, -1, (viewer, target) -> null)).getHandle();
    }

    @NotNull
    public synchronized PlaceholderReference getPlaceholderReference(@NonNull String identifier) {
        if (!PlaceholderIdentifier.isValid(identifier)) {
            throw new IllegalArgumentException("Placeholder identifier must start and end with % or <> (attempted to use \"" + identifier + "\")");
        }
        // Check if placeholder is already registered
        PlaceholderReference reference = registeredPlaceholders.get(identifier);
        if (reference != null) {
            addUsedPlaceholder(reference.getHandle(), this); // Make sure it refreshes if used via tab expansion or such
            return reference;
        }

        // Placeholder does not exist, create it
        for (Entry<Pattern, Function<String, Function<Matcher, Placeholder>>> entry : registeredDynamicPlaceholders.entrySet()) {
            Matcher m = entry.getKey().matcher(identifier);
            if (m.matches()) {
                return registerPlaceholder(entry.getValue().apply(identifier).apply(m));
            }
        }
        TAB.getInstance().getPlatform().registerUnknownPlaceholder(identifier);
        addUsedPlaceholder(identifier); // Make sure it refreshes if used via tab expansion or such
        return getPlaceholderReference(identifier);
    }

    /**
     * Returns placeholder found inside output of another placeholder. Unlike {@link #getPlaceholderReference(String)},
     * unknown identifiers are only registered if they look like real placeholders and a global limit is not reached yet.
     * Registered placeholders are never unregistered, so outputs such as {@code "45% | 12%"} or player-controlled texts
     * would otherwise register (and refresh) new placeholders forever.
     *
     * @param   identifier
     *          Detected identifier
     * @return  Placeholder reference or {@code null} if identifier should be left as plain text
     */
    @Nullable
    public synchronized PlaceholderReference getNestedPlaceholderReference(@NonNull String identifier) {
        if (registeredPlaceholders.containsKey(identifier)) return getPlaceholderReference(identifier);
        if (identifier.length() > 100) return null;
        for (int i = 1; i < identifier.length() - 1; i++) {
            char c = identifier.charAt(i);
            if (Character.isWhitespace(c) || c == '%' || c == '<' || c == '>') return null;
        }
        if (nestedUnknownPlaceholders >= MAX_NESTED_UNKNOWN_PLACEHOLDERS) return null;
        if (++nestedUnknownPlaceholders == MAX_NESTED_UNKNOWN_PLACEHOLDERS) {
            TAB.getInstance().getPlatform().logWarn(new me.neznamy.tab.shared.chat.component.TabTextComponent(
                    "Registered " + MAX_NESTED_UNKNOWN_PLACEHOLDERS + " placeholders found inside outputs of other placeholders, " +
                    "no more will be registered to prevent a memory leak. Last one: " + identifier));
        }
        return getPlaceholderReference(identifier);
    }

    @Override
    @NotNull
    public synchronized TabPlaceholder getPlaceholder(@NonNull String identifier) {
        return getPlaceholderReference(identifier).getHandle();
    }

    @Override
    public void unregisterPlaceholder(@NonNull Placeholder placeholder) {
        ensureActive();
        unregisterPlaceholder(placeholder.getIdentifier());
    }

    @Override
    public synchronized void unregisterPlaceholder(@NonNull String identifier) {
        ensureActive();
        registeredPlaceholders.remove(identifier);
        recalculateUsedPlaceholders();
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "Refreshing placeholders";
    }

    @Override
    @NotNull
    public Object dump(@NotNull TabPlayer player) {
        Map<String, Object> map = new LinkedHashMap<>();

        List<List<String>> serverPlaceholders = new ArrayList<>();
        List<List<String>> playerPlaceholders = new ArrayList<>();
        Map<TabPlayer, List<List<String>>> relationalPlaceholders = new HashMap<>();
        for (PlaceholderReference reference : usedPlaceholders) {
            TabPlaceholder p = reference.getHandle();
            if (p.getIdentifier().contains("AnonymousCondition")) continue; // These are ugly, don't show them
            if (p instanceof ServerPlaceholderImpl) {
                serverPlaceholders.add(Arrays.asList(p.getIdentifier(), String.valueOf(reference.getRefresh()), p.getLastValue(null)));
            } else if (p instanceof PlayerPlaceholderImpl) {
                playerPlaceholders.add(Arrays.asList(p.getIdentifier(), String.valueOf(reference.getRefresh()), p.getLastValueSafe(player)));
            } else if (p instanceof RelationalPlaceholderImpl) {
                for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
                    relationalPlaceholders.computeIfAbsent(viewer, k -> new ArrayList<>())
                            .add(Arrays.asList(p.getIdentifier(), String.valueOf(reference.getRefresh()), ((RelationalPlaceholderImpl) p).getLastValue(viewer, player)));
                }
            }
        }
        Map<String, Object> placeholderValues = new LinkedHashMap<>();
        placeholderValues.put("server", DumpUtils.tableToLines(Arrays.asList("Identifier", "Refresh", "Current value"), serverPlaceholders));
        placeholderValues.put("player", DumpUtils.tableToLines(Arrays.asList("Identifier", "Refresh", "Current value"), playerPlaceholders));
        Map<String, List<String>> relationalFormatted = new LinkedHashMap<>();
        for (Entry<TabPlayer, List<List<String>>> entry : relationalPlaceholders.entrySet()) {
            relationalFormatted.put("for viewer " + entry.getKey().getName(),
                    DumpUtils.tableToLines(Arrays.asList("Identifier", "Refresh", "Current value"), entry.getValue()));
        }
        placeholderValues.put("relational", relationalFormatted);
        map.put("current-placeholder-values", placeholderValues);

        // Return
        return map;
    }
}