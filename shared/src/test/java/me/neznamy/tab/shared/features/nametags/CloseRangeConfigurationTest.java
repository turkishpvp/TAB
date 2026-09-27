package me.neznamy.tab.shared.features.nametags;

import java.util.HashMap;
import java.util.Map;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloseRangeConfigurationTest {
    @Test void missingOptionKeepsAllLinesAtCloseRange() {
        assertFalse(parse(new HashMap<>()).isCollapseOnCloseRange());
    }

    @Test void explicitFalseKeepsAllLinesAtCloseRange() {
        Map<Object, Object> map = new HashMap<>();
        map.put("collapse-on-close-range", false);
        assertFalse(parse(map).isCollapseOnCloseRange());
    }

    @Test void otherServersCanExplicitlyOptIn() {
        Map<Object, Object> map = new HashMap<>();
        map.put("collapse-on-close-range", true);
        assertTrue(parse(map).isCollapseOnCloseRange());
    }

    private MultiLineConfiguration parse(Map<Object, Object> map) {
        // The distance option does not depend on group-property registration.
        map.put("lines", java.util.Collections.singletonList("nametag"));
        return MultiLineConfiguration.fromSection(new ConfigurationSection("test.yml", "multiline-nametags", map) {
            @Override public void startupWarn(String message) { /* No running TAB instance in parser tests. */ }
        });
    }
}
