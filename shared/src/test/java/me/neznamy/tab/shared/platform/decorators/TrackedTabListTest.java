package me.neznamy.tab.shared.platform.decorators;

import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class TrackedTabListTest {
    @Test
    void displayNameSupportDependsOnViewerInsteadOfTargetVersion() {
        TabPlayer viewer = mock(TabPlayer.class);
        TabPlayer target = mock(TabPlayer.class);
        UUID id = UUID.randomUUID();
        when(viewer.getVersion()).thenReturn(ProtocolVersion.V1_8);
        when(target.getTablistId()).thenReturn(id);
        // Deliberately no target protocol: only the receiving client determines packet support.
        TrackedTabList<?> list = mock(TrackedTabList.class,
                withSettings().useConstructor(viewer).defaultAnswer(CALLS_REAL_METHODS));
        TabComponent name = TabComponent.legacyText("Name");
        list.updateDisplayName(target, name);
        verify(list).updateDisplayName0(id, name);
        verify(target, never()).getVersion();
    }
}
