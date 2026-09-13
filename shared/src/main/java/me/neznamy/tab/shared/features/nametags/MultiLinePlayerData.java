package me.neznamy.tab.shared.features.nametags;

import me.neznamy.tab.shared.Property;
import org.jetbrains.annotations.Nullable;

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

    /** Non-empty lines to display from top to bottom, {@code null} if no lines should be displayed */
    @Nullable
    public volatile String[] lines;

    /** Amount of spacer entities below the lowest line */
    public volatile int baseSpacers;

    /** Whether player is in spectator gamemode */
    public volatile boolean spectator;

    /** Entity id of the player */
    public volatile int entityId;

    /** First id of the fake entity id block assigned to this player */
    public volatile int idBase;
}
