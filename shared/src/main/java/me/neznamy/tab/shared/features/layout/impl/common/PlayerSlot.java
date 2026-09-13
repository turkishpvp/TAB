package me.neznamy.tab.shared.features.layout.impl.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.features.layout.LayoutManagerImpl;
import me.neznamy.tab.shared.features.layout.impl.LayoutBase;
import me.neznamy.tab.shared.features.playerlist.PlayerList;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

@RequiredArgsConstructor
public class PlayerSlot {

    private static final StringToComponentCache cache = new StringToComponentCache("LayoutPlayerSlot", 100);

    private final int slot;
    private final LayoutBase layout;
    @Getter private final UUID uniqueId;
    @Getter private TabPlayer player;
    private String text = "";

    public void setPlayer(@Nullable TabPlayer newPlayer) {
        if (player == newPlayer) return;
        player = newPlayer;
        if (player != null) text = "";
        layout.getViewer().getTabList().removeEntry(uniqueId);
        if (shouldShow()) layout.getViewer().getTabList().addEntry(getSlot(layout.getViewer()));
    }

    /**
     * Returns whether this slot should be present in the tablist at all. With hide-empty-slots
     * enabled, a slot without a player and without text is left out entirely, so the tablist
     * grows and shrinks with the amount of players instead of always being a full grid.
     *
     * @return  {@code true} if the slot should be sent to the viewer
     */
    private boolean shouldShow() {
        return player != null || !text.isEmpty() || !layout.getManager().getConfiguration().isHideEmptySlots();
    }

    public @NotNull TabList.Entry getSlot(@NotNull TabPlayer viewer) {
        TabList.Entry data;
        TabPlayer player = this.player; //avoiding NPE from concurrent access
        if (player != null) {
            PlayerList playerList = layout.getManager().getPlayerList();
            data = new TabList.Entry(
                    uniqueId,
                    layout.getManager().getConfiguration().getDirection().getEntryName(viewer, slot, LayoutManagerImpl.isTeamsEnabled()),
                    player.getTabList().getSkin(),
                    true,
                    layout.getManager().getPingSpoof() != null ? layout.getManager().getPingSpoof().getConfiguration().getValue() : player.getPing(),
                    0,
                    playerList == null || player.tablistData.disabled.get() ? TabComponent.legacyText(player.getName()) : playerList.getTabFormat(player, viewer),
                    Integer.MAX_VALUE - layout.getManager().getConfiguration().getDirection().translateSlot(slot),
                    true
            );
        } else {
            data = new TabList.Entry(
                    uniqueId,
                    layout.getManager().getConfiguration().getDirection().getEntryName(viewer, slot, LayoutManagerImpl.isTeamsEnabled()),
                    layout.getPattern().getDefaultSkin(slot),
                    true,
                    layout.getManager().getConfiguration().getEmptySlotPing(),
                    0,
                    TabComponent.legacyText(text),
                    Integer.MAX_VALUE - layout.getManager().getConfiguration().getDirection().translateSlot(slot),
                    true
            );
        }
        return data;
    }

    public void setText(@NotNull String text) {
        if (this.text.equals(text) && player == null) return;
        if (player != null) {
            this.text = text;
            setPlayer(null);
            return;
        }
        boolean wasShown = shouldShow();
        this.text = text;
        boolean show = shouldShow();
        if (show && !wasShown) {
            layout.getViewer().getTabList().addEntry(getSlot(layout.getViewer()));
        } else if (!show && wasShown) {
            layout.getViewer().getTabList().removeEntry(uniqueId);
        } else if (show) {
            layout.getViewer().getTabList().updateDisplayName(uniqueId, cache.get(text));
        }
    }
}
