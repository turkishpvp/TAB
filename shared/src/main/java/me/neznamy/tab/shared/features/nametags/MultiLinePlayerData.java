package me.neznamy.tab.shared.features.nametags;

import lombok.AllArgsConstructor;
import me.neznamy.tab.shared.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class holding multi-line nametag data of a player. Fields read by network threads are volatile
 * and replaced as a whole, never modified in place.
 */
public class MultiLinePlayerData {

    /** Line properties in configured order (top to bottom), {@code null} entry for the "nametag" line */
    @Nullable
    public Property[] lineProperties;

    /** Properties forming the "nametag" line */
    @Nullable
    public Property prefix;

    @Nullable
    public Property name;

    @Nullable
    public Property suffix;

    /** Flag tracking whether the feature is disabled for this player with a condition */
    public final AtomicBoolean disabled = new AtomicBoolean();

    /** Currently displayed lines, {@code null} if no lines should be displayed */
    @Nullable
    public volatile Layout layout;

    /** Whether the player is currently shown their own lines */
    public volatile boolean selfView;

    /** Whether player is in spectator gamemode */
    public volatile boolean spectator;

    /** Entity id of the player */
    public volatile int entityId;

    /** First id of the fake entity id block assigned to this player */
    public volatile int idBase;

    /**
     * Immutable snapshot of displayed lines.
     */
    @AllArgsConstructor
    public static class Layout {

        /** Non-empty lines from top to bottom */
        @NotNull
        public final String[] lines;

        /**
         * Heights in blocks, same length as lines. Value at index i is the space between line i and the line below it,
         * last value is height of the lowest line above player's feet.
         */
        @NotNull
        public final double[] heights;

        /** Whether lines should move down when player sneaks */
        public final boolean lowerWhenSneaking;

        /**
         * Returns {@code true} if entities of both layouts are positioned the same, so only texts may differ.
         *
         * @param   other
         *          Layout to compare with
         * @return  {@code true} if structure is the same, {@code false} if not
         */
        public boolean hasSameStructure(@NotNull Layout other) {
            return lines.length == other.lines.length && lowerWhenSneaking == other.lowerWhenSneaking && Arrays.equals(heights, other.heights);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Layout)) return false;
            return hasSameStructure((Layout) o) && Arrays.equals(lines, ((Layout) o).lines);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(lines);
        }
    }
}
