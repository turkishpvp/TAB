package me.neznamy.tab.shared.placeholders.conditions;

import me.neznamy.tab.shared.TAB;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class ConditionTest {
    @Test
    void configConversionCanConstructConditionBeforePlaceholderManagerExists() {
        TAB tab = mock(TAB.class);
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            assertDoesNotThrow(() -> new Condition("converted-header", Collections.emptyList(), true,
                    "%player%", "", false));
        }
    }
}
