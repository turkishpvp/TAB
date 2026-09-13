package me.neznamy.tab.shared.platform;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelPacketQueueTest {

    @Test
    void burstIsWrittenInOrderWithSingleFlushAndAdjacentPacketsMerged() {
        AtomicInteger flushes = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void flush(ChannelHandlerContext ctx) throws Exception {
                flushes.incrementAndGet();
                super.flush(ctx);
            }
        });
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, (first, second) ->
                first instanceof String && second instanceof String ? first + "+" + second : null);

        queue.send("a");
        queue.send("b");
        queue.send(1);
        queue.send("c");
        for (int i = 0; i < 100; i++) queue.send(i + 10);
        channel.runPendingTasks();

        assertEquals(1, flushes.get());
        assertEquals("a+b", channel.readOutbound());
        assertEquals(1, (int) channel.readOutbound());
        assertEquals("c", channel.readOutbound());
        for (int i = 0; i < 100; i++) assertEquals(i + 10, (int) channel.readOutbound());
        assertNull(channel.readOutbound());

        // Packets sent after the drain are delivered by a new drain
        queue.send("d");
        channel.runPendingTasks();
        assertEquals(2, flushes.get());
        assertEquals("d", channel.readOutbound());
    }

    @Test
    void closedChannelDropsQueuedPackets() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, null);
        channel.close(); // EmbeddedChannel runs pending tasks on close, so close first
        queue.send("a");
        channel.runPendingTasks();
        assertNull(channel.readOutbound());
    }
}
