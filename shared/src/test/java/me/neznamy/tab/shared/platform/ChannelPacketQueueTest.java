package me.neznamy.tab.shared.platform;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

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

    @Test
    void closedChannelReleasesReferenceCountedPackets() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, null);
        channel.close();
        ByteBuf packet = Unpooled.buffer(1);
        queue.send(packet);
        assertEquals(0, packet.refCnt());
    }

    @Test
    void reentrantSendDuringDrainDoesNotScheduleAnEmptyTask() {
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(loop);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(loop).execute(any());
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, null);
        doAnswer(call -> { queue.send("c"); return null; }).when(channel).write("a");
        queue.send("a");
        queue.send("b");
        tasks.get(0).run();
        assertEquals(1, tasks.size());
        var order = inOrder(channel);
        order.verify(channel).write("a");
        order.verify(channel).write("b");
        order.verify(channel).write("c");
        verify(channel).flush();
    }

    @Test
    void sendAfterLastPollIsNotStranded() {
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(loop);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(loop).execute(any());
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, null);
        doAnswer(call -> { queue.send("late"); return channel; }).doReturn(channel).when(channel).flush();
        queue.send("first");
        tasks.get(0).run();
        assertEquals(2, tasks.size());
        tasks.get(1).run();
        verify(channel).write("late");
        assertEquals(2, tasks.size());
    }

    @Test
    void eventLoopRejectionReleasesPacket() {
        Channel channel = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.eventLoop()).thenReturn(loop);
        doThrow(new RejectedExecutionException()).when(loop).execute(any());
        ByteBuf packet = Unpooled.buffer(1);
        ChannelPacketQueue queue = new ChannelPacketQueue(channel, null);
        assertThrows(RejectedExecutionException.class, () -> queue.send(packet));
        assertEquals(0, packet.refCnt());
    }
}
