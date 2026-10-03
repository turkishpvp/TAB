package me.neznamy.tab.shared.placeholders;

import me.neznamy.tab.shared.features.types.RefreshableFeature;
import me.neznamy.tab.shared.placeholders.types.TabPlaceholder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceholderReferenceTest {
    @Test
    void featureRegistrationCanOverlapRefreshIteration() {
        PlaceholderReference reference = new PlaceholderReference("%test%", mock(TabPlaceholder.class));
        reference.addUsedFeature(mock(RefreshableFeature.class));
        var iterator = reference.getUsedByFeatures().iterator();
        reference.addUsedFeature(mock(RefreshableFeature.class));
        assertDoesNotThrow(() -> { while (iterator.hasNext()) iterator.next(); });
    }
}
