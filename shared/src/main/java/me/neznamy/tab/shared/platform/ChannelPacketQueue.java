package me.neznamy.tab.shared.platform;

import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;

/**
 * Outbound packet queue of a single player. Sending packets from outside the event loop
 * through the server normally costs one event loop task and one flush (write syscall) per packet.
 * This queue writes everything queued so far in a single task and flushes once,
 * so bursts caused by placeholder refreshes collapse into one flush without adding any delay.
 */
@RequiredArgsConstructor
public class ChannelPacketQueue {

    /** Player's channel */
    @NonNull
    private final Channel channel;

    /** Function merging two adjacent packets into one, returning {@code null} if they cannot be merged */
    @Nullable
    private final BinaryOperator<Object> merger;

    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    /**
     * Queues packet to be written in the next drain of the event loop.
     *
     * @param   packet
     *          Packet to send
     */
    public void send(@NonNull Object packet) {
        if (!channel.isActive()) {
            ReferenceCountUtil.release(packet);
            return;
        }
        queue.add(packet);
        schedule();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                channel.eventLoop().execute(this::drain);
            } catch (RejectedExecutionException exception) {
                scheduled.set(false);
                discard();
                throw exception;
            }
        }
    }

    private void drain() {
        try {
            drainBatch();
        } finally {
            scheduled.set(false);
            // A writer can enqueue after the last poll but before scheduled is cleared.
            if (!queue.isEmpty()) schedule();
        }
    }

    private void discard() {
        Object packet;
        while ((packet = queue.poll()) != null) ReferenceCountUtil.release(packet);
    }

    private void drainBatch() {
        if (!channel.isActive()) {
            discard();
            return;
        }
        Object pending = queue.poll();
        if (pending == null) return;
        Object next;
        while ((next = queue.poll()) != null) {
            Object merged = merger == null ? null : merger.apply(pending, next);
            if (merged != null) {
                pending = merged;
                continue;
            }
            // Not using voidPromise, Netty 4.0 throws when third-party handlers add listeners to it
            channel.write(pending);
            pending = next;
        }
        channel.write(pending);
        channel.flush();
    }
}
