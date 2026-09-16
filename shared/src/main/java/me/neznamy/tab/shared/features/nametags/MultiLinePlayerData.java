package me.neznamy.tab.shared.features.nametags;

import lombok.AllArgsConstructor;
import me.neznamy.tab.shared.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    /** Currently displayed lines, replaced as a whole so network threads never see a half-updated state */
    @NotNull
    public volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Whether the player is currently shown their own lines */
    public volatile boolean selfView;

    /** Whether player is in spectator gamemode */
    public volatile boolean spectator;

    /** Entity id of the player */
    public volatile int entityId;

    /** First id of the fake entity id block assigned to this player */
    public volatile int idBase;

    /**
     * Immutable set of layouts of a player. Either all viewers share one layout, or there is one layout
     * per viewer, which is only needed when lines contain relational placeholders or relational conditions.
     */
    public static class Snapshot {

        /** Empty snapshot displaying no lines */
        public static final Snapshot EMPTY = new Snapshot(null, Collections.emptyMap());

        /** Layout shown to all viewers, {@code null} when lines are viewer-specific or not displayed */
        @Nullable
        public final Layout shared;

        /** Layouts for each viewer, empty when all viewers share the same one */
        @NotNull
        public final Map<UUID, Layout> perViewer;

        /**
         * Constructs new instance with given layouts.
         *
         * @param   shared
         *          Layout shown to all viewers, {@code null} if viewer-specific
         * @param   perViewer
         *          Layouts for each viewer, empty if a shared one is used
         */
        public Snapshot(@Nullable Layout shared, @NotNull Map<UUID, Layout> perViewer) {
            this.shared = shared;
            this.perViewer = perViewer;
        }

        /**
         * Returns layout to display to given viewer, {@code null} if there are no lines to display.
         *
         * @param   viewer
         *          Unique ID of the viewer
         * @return  Layout for the viewer or {@code null} if none
         */
        @Nullable
        public Layout forViewer(@NotNull UUID viewer) {
            Layout layout = perViewer.get(viewer);
            return layout != null ? layout : shared;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Snapshot)) return false;
            return Objects.equals(shared, ((Snapshot) o).shared) && perViewer.equals(((Snapshot) o).perViewer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(shared, perViewer);
        }
    }

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
