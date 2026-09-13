package me.neznamy.tab.shared.platform;

import org.jetbrains.annotations.NotNull;

/**
 * Platform-specific renderer displaying multi-line nametags using fake entities riding the player.
 * Implementation reads line texts from {@link TabPlayer#multiLineData} and never calls back into features,
 * all methods only schedule work on players' network threads.
 */
public interface MultiLineRenderer {

    /**
     * Injects packet handler of the player as early as possible (on join event), before the first
     * entity spawn packet is sent to the player.
     *
     * @param   player
     *          Platform's player object
     */
    void inject(@NotNull Object player);

    /**
     * Registers player as a possible owner of lines and re-evaluates lines of this player
     * for all viewers and lines of all players for this player.
     *
     * @param   player
     *          Player who joined
     */
    void onJoin(@NotNull TabPlayer player);

    /**
     * Unregisters player as owner of lines.
     *
     * @param   player
     *          Player who left
     */
    void onQuit(@NotNull TabPlayer player);

    /**
     * Re-evaluates lines of given owner for all players currently seeing the owner.
     *
     * @param   owner
     *          Player whose lines or visibility changed
     */
    void refreshOwner(@NotNull TabPlayer owner);

    /**
     * Re-evaluates lines of all players seen by given viewer.
     *
     * @param   viewer
     *          Viewer whose view changed (gamemode, view toggle)
     */
    void refreshViewer(@NotNull TabPlayer viewer);

    /**
     * Re-evaluates lines of given owner for given viewer.
     *
     * @param   owner
     *          Player whose lines are displayed
     * @param   viewer
     *          Viewer of the lines
     */
    void refresh(@NotNull TabPlayer owner, @NotNull TabPlayer viewer);

    /**
     * Injects handlers into all online players and spawns lines of players already seen by them.
     */
    void load();

    /**
     * Destroys all lines and removes all handlers.
     */
    void unload();
}
