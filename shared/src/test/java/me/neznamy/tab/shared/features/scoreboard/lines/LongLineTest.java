package me.neznamy.tab.shared.features.scoreboard.lines;

import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.features.scoreboard.ScoreboardImpl;
import me.neznamy.tab.shared.features.scoreboard.ScoreboardManagerImpl;
import me.neznamy.tab.shared.features.scoreboard.ScoreboardPlayerData;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class LongLineTest {
    private final TabPlayer player = mock(TabPlayer.class);
    private final Scoreboard board = mock(Scoreboard.class);
    private final ScoreboardLineHolder holder = mock(ScoreboardLineHolder.class);
    private final Property text = mock(Property.class);
    private final Property format = mock(Property.class);
    private final Property score = mock(Property.class);
    private final LongLine line = new LongLine(holder);
    private ScoreboardPlayerData data;

    @BeforeEach
    void setup() throws Exception {
        data = new ScoreboardPlayerData();
        var scoreboardData = TabPlayer.class.getField("scoreboardData");
        scoreboardData.setAccessible(true);
        scoreboardData.set(player, data);
        ScoreboardImpl parent = mock(ScoreboardImpl.class);
        ScoreboardManagerImpl manager = mock(ScoreboardManagerImpl.class);
        StringToComponentCache cache = mock(StringToComponentCache.class);
        data.activeScoreboard = parent;
        when(holder.getParent()).thenReturn(parent);
        when(parent.getManager()).thenReturn(manager);
        when(manager.getCache()).thenReturn(cache);
        when(cache.get(anyString())).thenAnswer(call -> TabComponent.legacyText(call.getArgument(0)));
        when(holder.getForcedPlayerNameStart()).thenReturn("§1");
        when(holder.getTeamName()).thenReturn("line");
        when(player.getVersion()).thenReturn(ProtocolVersion.V1_8);
        when(player.getScoreboard()).thenReturn(board);
        when(text.get()).thenReturn("Text");
        when(format.get()).thenReturn("");
        data.lineProperties.put(holder, new ScoreboardPlayerData.LineProperties(text, "§1Text", format, score));
    }

    @Test
    void scoreChangeKeepsExistingEntryAndTeam() {
        when(score.update()).thenReturn(true);
        line.refresh(player);
        verify(holder).setScore(player, "§1Text");
        verify(board, never()).removeScore(anyString(), anyString());
        verify(board, never()).unregisterTeam(anyString());
        verify(holder).updateTeam(player, "", "");
    }

    @Test
    void changedEntryIsRemovedBeforeReplacement() {
        when(text.update()).thenReturn(true);
        when(text.get()).thenReturn("New");
        line.refresh(player);
        var order = inOrder(board, holder);
        order.verify(board).removeScore(ScoreboardManagerImpl.OBJECTIVE_NAME, "§1Text");
        order.verify(board).unregisterTeam("line");
        order.verify(board).registerTeam(eq("line"), any(), any(), any(), any(), eq(java.util.Collections.singletonList("§1New")), eq(0), any());
        order.verify(holder).setScore(player, "§1New");
        org.junit.jupiter.api.Assertions.assertEquals("§1New", data.lineProperties.get(holder).scoreName);
    }

    @Test
    void emptyThenRestoredLineKeepsTeamAndRestoresScore() {
        when(text.update()).thenReturn(true);
        when(text.get()).thenReturn("");
        line.refresh(player);
        verify(board).removeScore(ScoreboardManagerImpl.OBJECTIVE_NAME, "§1Text");
        clearInvocations(board, holder);
        when(text.get()).thenReturn("Text");
        line.refresh(player);
        verify(holder).setScore(player, "§1Text");
        verify(board, never()).unregisterTeam(anyString());
    }

    @Test
    void suffixChangeDoesNotRecreateTheScoreEntry() {
        String prefix = "AAAAAAAAAAAAAAAA";
        String middle = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        when(text.get()).thenReturn(prefix + middle + "Old");
        line.register(player);
        clearInvocations(board, holder);
        when(text.update()).thenReturn(true);
        when(text.get()).thenReturn(prefix + middle + "New");
        line.refresh(player);
        verify(holder).updateTeam(eq(player), eq(prefix), contains("New"));
        verify(board, never()).removeScore(anyString(), anyString());
        verify(board, never()).unregisterTeam(anyString());
    }
}
