package me.neznamy.tab.shared.placeholders;

import me.neznamy.tab.shared.config.file.ConfigurationSection;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceholderRefreshConfigurationTest {
    @ParameterizedTest
    @ValueSource(ints = {0, -50, 75})
    void invalidDefaultsAndPermissionsFallBackToValidIntervals(int interval) {
        ConfigurationSection section = mock(ConfigurationSection.class);
        when(section.getInt("default-refresh-interval", 500)).thenReturn(interval);
        when(section.getKeys()).thenReturn(Collections.singletonList("%custom%"));
        when(section.getObject("%custom%")).thenReturn(interval);
        var config = PlaceholderRefreshConfiguration.fromSection(section, interval);
        assertEquals(500, config.getRefreshInterval("%missing%"));
        assertEquals(500, config.getRefreshInterval("%custom%"));
        assertEquals(1000, config.getRefreshInterval("%permission:example%"));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerPlaceholderImpl("%api%", interval, player -> "test"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 50, 500})
    void validDefaultsIncludingDisabledRefreshArePreserved(int interval) {
        ConfigurationSection section = mock(ConfigurationSection.class);
        when(section.getInt("default-refresh-interval", 500)).thenReturn(interval);
        when(section.getKeys()).thenReturn(Collections.emptyList());
        var config = PlaceholderRefreshConfiguration.fromSection(section, interval);
        assertEquals(interval, config.getRefreshInterval("%missing%"));
        assertEquals(interval, config.getRefreshInterval("%permission:example%"));
    }
}
