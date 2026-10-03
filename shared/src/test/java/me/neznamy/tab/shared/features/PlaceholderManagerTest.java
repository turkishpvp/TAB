package me.neznamy.tab.shared.features;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.features.types.RefreshableFeature;
import me.neznamy.tab.shared.placeholders.PlaceholderReference;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.expansion.ExpansionData;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceholderManagerTest {
    @Test
    void forcedRefreshSupersedesNormalRefreshForSamePlayerAndSkipsOfflinePlayers() throws Exception {
        TAB tab = mock(TAB.class);
        when(tab.getCpu()).thenReturn(mock(CpuManager.class));
        TabPlayer both = mock(TabPlayer.class);
        TabPlayer normalOnly = mock(TabPlayer.class);
        TabPlayer offline = mock(TabPlayer.class);
        when(both.isOnline()).thenReturn(true);
        when(normalOnly.isOnline()).thenReturn(true);
        RefreshableFeature feature = mock(RefreshableFeature.class);
        PlaceholderManagerImpl manager = mock(PlaceholderManagerImpl.class, CALLS_REAL_METHODS);
        var refresh = PlaceholderManagerImpl.class.getDeclaredMethod("refreshFeatures", Map.class, Map.class);
        refresh.setAccessible(true);
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            refresh.invoke(manager, Collections.singletonMap(feature, Arrays.asList(both, offline)),
                    Collections.singletonMap(feature, Arrays.asList(both, normalOnly, offline)));
        }
        verify(feature).refresh(both, true);
        verify(feature, never()).refresh(both, false);
        verify(feature).refresh(normalOnly, false);
        verify(feature, never()).refresh(eq(offline), anyBoolean());
    }

    @Test
    void playerParentIsUpdatedOnlyOnceAndPartialInitializationDoesNotReturnNull() throws Exception {
        TAB tab = mock(TAB.class, RETURNS_DEEP_STUBS);
        TabPlayer player = mock(TabPlayer.class);
        when(player.isOnline()).thenReturn(true);
        setField(player, "lastPlaceholderReturnedValues", new ConcurrentHashMap<>());
        setField(player, "lastPlaceholderEvaluatedValues", new ConcurrentHashMap<>());
        setField(player, "expansionData", mock(ExpansionData.class));
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            PlayerPlaceholderImpl placeholder = spy(new PlayerPlaceholderImpl("%test%", 500, p -> "value"));
            placeholder.setReference(new PlaceholderReference("%test%", placeholder));
            // Represents a second reader arriving between raw and evaluated map publication.
            player.lastPlaceholderReturnedValues.put(placeholder, "value");
            assertEquals("value", placeholder.getLastValue(player));
            clearInvocations(placeholder);
            PlaceholderManagerImpl manager = mock(PlaceholderManagerImpl.class, CALLS_REAL_METHODS);
            var update = PlaceholderManagerImpl.class.getDeclaredMethod("updatePlayerPlaceholders", Map.class, Map.class);
            update.setAccessible(true);
            update.invoke(manager, Collections.singletonMap(placeholder, Collections.singletonMap(player, "new")), new HashMap<>());
            verify(placeholder).updateParents(player);
        }
    }

    private static void setField(TabPlayer player, String name, Object value) throws Exception {
        var field = TabPlayer.class.getField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    @Test
    void nestedParentChainRefreshesEachAncestorOnce() throws Exception {
        TAB tab = mock(TAB.class, RETURNS_DEEP_STUBS);
        TabPlayer player = mock(TabPlayer.class);
        when(player.isLoaded()).thenReturn(true);
        setField(player, "lastPlaceholderReturnedValues", new ConcurrentHashMap<>());
        setField(player, "lastPlaceholderEvaluatedValues", new ConcurrentHashMap<>());
        setField(player, "expansionData", mock(ExpansionData.class));
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            PlayerPlaceholderImpl leaf = new PlayerPlaceholderImpl("%leaf%", 500, p -> "old");
            PlayerPlaceholderImpl parent = new PlayerPlaceholderImpl("%parent%", 500, p -> leaf.getLastValue((TabPlayer) p));
            PlayerPlaceholderImpl ancestor = spy(new PlayerPlaceholderImpl("%ancestor%", 500, p -> parent.getLastValue((TabPlayer) p)));
            leaf.setReference(new PlaceholderReference("%leaf%", leaf));
            parent.setReference(new PlaceholderReference("%parent%", parent));
            ancestor.setReference(new PlaceholderReference("%ancestor%", ancestor));
            leaf.getReference().addParent(parent.getReference());
            parent.getReference().addParent(ancestor.getReference());
            assertEquals("old", ancestor.getLastValue(player));
            clearInvocations(ancestor);
            leaf.hasValueChanged(player, "new", true);
            assertEquals("new", ancestor.getLastValue(player));
            verify(ancestor).request(player);
        }
    }

    @Test
    void cyclicNestedPlaceholdersDoNotOverflowAndLaterValidUpdatesStillWork() throws Exception {
        TAB tab = mock(TAB.class, RETURNS_DEEP_STUBS);
        when(tab.getPlatform().detectAdditionalPlaceholders(anyString())).thenReturn(Collections.emptyList());
        TabPlayer player = mock(TabPlayer.class);
        when(player.isLoaded()).thenReturn(true);
        setField(player, "lastPlaceholderReturnedValues", new ConcurrentHashMap<>());
        setField(player, "lastPlaceholderEvaluatedValues", new ConcurrentHashMap<>());
        setField(player, "expansionData", mock(ExpansionData.class));
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            PlayerPlaceholderImpl first = new PlayerPlaceholderImpl("%first%", 500, p -> "%second%");
            PlayerPlaceholderImpl second = new PlayerPlaceholderImpl("%second%", 500, p -> "%first%");
            first.setReference(new PlaceholderReference("%first%", first));
            second.setReference(new PlaceholderReference("%second%", second));
            when(tab.getPlaceholderManager().getNestedPlaceholderReference("%first%")).thenReturn(first.getReference());
            when(tab.getPlaceholderManager().getNestedPlaceholderReference("%second%")).thenReturn(second.getReference());
            assertNotNull(assertDoesNotThrow(() -> first.getLastValue(player)));
            // Explicitly cover the parent cycle too, independent of nested-cache initialization order.
            first.getReference().addParent(second.getReference());
            second.getReference().addParent(first.getReference());
            assertDoesNotThrow(() -> first.hasValueChanged(player, "resolved", true));
            assertEquals("resolved", first.getLastValue(player));
        }
    }
}
