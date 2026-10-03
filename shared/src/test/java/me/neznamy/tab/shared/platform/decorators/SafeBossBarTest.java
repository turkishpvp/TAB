package me.neznamy.tab.shared.platform.decorators;

import me.neznamy.tab.api.bossbar.BarColor;
import me.neznamy.tab.api.bossbar.BarStyle;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.platform.impl.DummyBossBar;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class SafeBossBarTest {
    @Test
    void identicalUpdatesProduceNoPacketsButChangedValuesDo() {
        DummyBossBar bar = spy(new DummyBossBar());
        UUID id = UUID.randomUUID();
        TabComponent title = TabComponent.legacyText("title");
        bar.create(id, title, 0.5f, BarColor.RED, BarStyle.PROGRESS);
        clearInvocations(bar);
        for (int i = 0; i < 100; i++) {
            bar.update(id, title);
            bar.update(id, 0.5f);
            bar.update(id, BarColor.RED);
            bar.update(id, BarStyle.PROGRESS);
        }
        verify(bar, never()).updateTitle(any());
        verify(bar, never()).updateProgress(any());
        verify(bar, never()).updateColor(any());
        verify(bar, never()).updateStyle(any());
        bar.update(id, TabComponent.legacyText("new"));
        bar.update(id, 0.7f);
        bar.update(id, BarColor.BLUE);
        bar.update(id, BarStyle.NOTCHED_6);
        verify(bar).updateTitle(any());
        verify(bar).updateProgress(any());
        verify(bar).updateColor(any());
        verify(bar).updateStyle(any());
    }

    @Test
    void repeatedFreezeRetainsBarsThatMustBeRemovedFromClient() {
        DummyBossBar bar = spy(new DummyBossBar());
        UUID id = UUID.randomUUID();
        bar.create(id, TabComponent.empty(), 1, BarColor.RED, BarStyle.PROGRESS);
        bar.freeze();
        bar.remove(id);
        bar.freeze();
        verify(bar, never()).hide(any());
        bar.unfreezeAndSynchronize();
        verify(bar).hide(any());
        verify(bar).show(any()); // Only the initial create; removed bar must not reappear.
    }
}
