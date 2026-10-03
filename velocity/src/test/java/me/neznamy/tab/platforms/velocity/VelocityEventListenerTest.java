package me.neznamy.tab.platforms.velocity;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.FeatureManager;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

class VelocityEventListenerTest {
    @Test
    void loadedPlayerCanQuitWithoutConnectEventAndOldSessionCannotRemoveReplacement() {
        TAB tab = mock(TAB.class);
        CpuManager cpu = mock(CpuManager.class);
        FeatureManager features = mock(FeatureManager.class);
        when(tab.getCPUManager()).thenReturn(cpu);
        when(tab.getFeatureManager()).thenReturn(features);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(cpu).runTask(any());
        UUID id = UUID.randomUUID();
        Player oldConnection = mock(Player.class);
        Player newConnection = mock(Player.class);
        when(oldConnection.getUniqueId()).thenReturn(id);
        when(newConnection.getUniqueId()).thenReturn(id);
        TabPlayer current = mock(TabPlayer.class);
        when(current.getPlayer()).thenReturn(newConnection);
        when(tab.getPlayer(id)).thenReturn(current);
        DisconnectEvent oldQuit = mock(DisconnectEvent.class);
        when(oldQuit.getPlayer()).thenReturn(oldConnection);
        DisconnectEvent currentQuit = mock(DisconnectEvent.class);
        when(currentQuit.getPlayer()).thenReturn(newConnection);
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            VelocityEventListener listener = new VelocityEventListener();
            listener.onQuit(oldQuit);
            tasks.remove(0).run();
            verify(features, never()).onQuit(any(TabPlayer.class));
            listener.onQuit(currentQuit);
            tasks.remove(0).run();
            verify(features).onQuit(current);
        }
    }

    @Test
    void disconnectBeforeQueuedConnectDoesNotCreateGhostPlayer() {
        TAB tab = mock(TAB.class);
        CpuManager cpu = mock(CpuManager.class);
        when(tab.getCPUManager()).thenReturn(cpu);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(cpu).runTask(any());
        Player connection = mock(Player.class);
        ServerPostConnectEvent connected = mock(ServerPostConnectEvent.class);
        when(connected.getPlayer()).thenReturn(connection);
        try (var singleton = mockStatic(TAB.class)) {
            singleton.when(TAB::getInstance).thenReturn(tab);
            VelocityEventListener listener = spy(new VelocityEventListener());
            listener.onConnect(connected);
            tasks.remove(0).run();
            verify(listener, never()).createPlayer(any());
            verify(tab, never()).getFeatureManager();
        }
    }
}
