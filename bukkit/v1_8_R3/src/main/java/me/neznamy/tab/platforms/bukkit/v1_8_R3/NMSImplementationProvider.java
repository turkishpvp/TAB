package me.neznamy.tab.platforms.bukkit.v1_8_R3;

import io.netty.channel.Channel;
import lombok.Getter;
import me.neznamy.tab.platforms.bukkit.BukkitTabPlayer;
import me.neznamy.tab.platforms.bukkit.provider.ComponentConverter;
import me.neznamy.tab.platforms.bukkit.provider.ImplementationProvider;
import me.neznamy.tab.shared.platform.ChannelPacketQueue;
import me.neznamy.tab.shared.platform.MultiLineRenderer;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.platform.TabListEntryTracker;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation provider using direct NMS code for 1.8.8.
 */
@Getter
public class NMSImplementationProvider implements ImplementationProvider {

    @NotNull
    private final ComponentConverter<?> componentConverter = new NMSComponentConverter();
    
    @Override
    @NotNull
    public Scoreboard newScoreboard(@NotNull BukkitTabPlayer player) {
        return new NMSPacketScoreboard(player);
    }

    @Override
    @NotNull
    public TabList newTabList(@NotNull BukkitTabPlayer player) {
        return new NMSPacketTabList(player);
    }

    @Override
    @NotNull
    public Channel getChannel(@NotNull Player player) {
        return ((CraftPlayer)player).getHandle().playerConnection.networkManager.channel;
    }

    @Override
    @NotNull
    public TabListEntryTracker newTabListEntryTracker(@NotNull Player player) {
        return new NMSTabListEntryTracker(getChannel(player));
    }

    @Override
    @NotNull
    public MultiLineRenderer newMultiLineRenderer() {
        return new NMSMultiLineRenderer();
    }

    @Override
    public int getPing(@NotNull BukkitTabPlayer player) {
        return ((CraftPlayer)player.getPlayer()).getHandle().ping;
    }

    /**
     * Returns player's packet queue, creating it on first use. Scoreboard and tablist are both
     * created in TabPlayer constructor on the same thread, so no synchronization is needed.
     *
     * @param   player
     *          Player to get queue of
     * @return  Player's packet queue
     */
    @NotNull
    static ChannelPacketQueue queue(@NotNull BukkitTabPlayer player) {
        ChannelPacketQueue queue = player.getPacketQueue();
        if (queue == null) {
            queue = new ChannelPacketQueue(((CraftPlayer)player.getPlayer()).getHandle().playerConnection.networkManager.channel,
                    NMSPacketTabList::merge);
            player.setPacketQueue(queue);
        }
        return queue;
    }
}