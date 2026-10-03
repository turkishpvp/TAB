package me.neznamy.tab.shared.platform.decorators;

import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static me.neznamy.tab.shared.platform.Scoreboard.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeScoreboardTest {
    private final CountingScoreboard board = new CountingScoreboard();
    private final TabComponent title = TabComponent.legacyText("Title");
    private final TabComponent changed = TabComponent.legacyText("Changed");

    @Test
    void unchangedRefreshDoesNotEmitUpdatesButEveryObjectiveFieldIsTracked() {
        board.registerObjective("sidebar", title, HealthDisplay.INTEGER, null);
        for (int i = 0; i < 1000; i++) {
            board.updateObjective("sidebar", title, HealthDisplay.INTEGER, null);
        }
        assertEquals(0, board.objectiveUpdates);
        board.updateObjective("sidebar", changed, HealthDisplay.INTEGER, null);
        board.updateObjective("sidebar", changed, HealthDisplay.HEARTS, null);
        board.updateObjective("sidebar", changed, HealthDisplay.HEARTS, title);
        assertEquals(3, board.objectiveUpdates);
    }

    @Test
    void teamOverloadsSuppressDuplicatesAndPreserveChangedFields() {
        registerTeam();
        for (int i = 0; i < 1000; i++) {
            board.updateTeam("team", title, title, NameVisibility.NEVER, CollisionRule.NEVER, 0, EnumChatFormat.RESET);
            board.updateTeam("team", title, title, EnumChatFormat.RESET);
            board.updateTeam("team", NameVisibility.NEVER);
            board.updateTeam("team", CollisionRule.NEVER);
        }
        assertEquals(0, board.teamUpdates);
        board.updateTeam("team", changed, title, EnumChatFormat.RESET);
        board.updateTeam("team", changed, changed, EnumChatFormat.RED);
        board.updateTeam("team", NameVisibility.ALWAYS);
        board.updateTeam("team", CollisionRule.ALWAYS);
        board.updateTeam("team", changed, changed, NameVisibility.ALWAYS, CollisionRule.ALWAYS, 3, EnumChatFormat.RED);
        assertEquals(5, board.teamUpdates);
    }

    @Test
    void frozenChangesAreReplayedAfterClientResetEvenWhenValuesAreUnchanged() {
        board.registerObjective("sidebar", title, HealthDisplay.INTEGER, null);
        board.setDisplaySlot("sidebar", DisplaySlot.SIDEBAR);
        board.setScore("sidebar", "entry", 1, null, null);
        registerTeam();
        board.setFrozen(true);
        board.updateObjective("sidebar", changed, HealthDisplay.HEARTS, null);
        board.updateTeam("team", changed, title, EnumChatFormat.RED);
        board.setScore("sidebar", "entry", 2, null, null);
        assertEquals(0, board.objectiveUpdates);
        assertEquals(0, board.teamUpdates);
        assertEquals(1, board.scores);
        board.setFrozen(false);
        board.resend();
        assertEquals(2, board.objectiveCreates);
        assertEquals(2, board.teamCreates);
        assertEquals(2, board.slots);
        assertEquals(2, board.scores);
        assertSame(changed, board.lastObjective.getTitle());
        assertSame(changed, board.lastTeam.getPrefix());
        assertEquals(2, board.lastScore.getValue());
        board.resend();
        assertEquals(3, board.objectiveCreates);
        assertEquals(3, board.teamCreates);
    }

    private void registerTeam() {
        board.registerTeam("team", title, title, NameVisibility.NEVER, CollisionRule.NEVER,
                Collections.singletonList("entry"), 0, EnumChatFormat.RESET);
    }

    private static class CountingScoreboard extends SafeScoreboard<TabPlayer> {
        int objectiveUpdates, teamUpdates, objectiveCreates, teamCreates, scores, slots;
        Objective lastObjective;
        Team lastTeam;
        Score lastScore;
        CountingScoreboard() { super(null); }
        public void registerObjective(Objective objective) { objectiveCreates++; lastObjective = objective; }
        public void updateObjective(Objective objective) { objectiveUpdates++; }
        public void unregisterObjective(Objective objective) {}
        public void setDisplaySlot(Objective objective) { slots++; }
        public void setScore(Score score) { scores++; lastScore = score; }
        public void removeScore(Score score) {}
        public Object createTeam(String name) { return new Object(); }
        public void registerTeam(Team team) { teamCreates++; lastTeam = team; }
        public void updateTeam(Team team) { teamUpdates++; }
        public void unregisterTeam(Team team) {}
    }
}
