package me.neznamy.tab.shared.cpu;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThreadExecutorTest {
    @Test
    void workerCanShutItselfDownWithoutWaitingForItself() throws Exception {
        ThreadExecutor executor = new ThreadExecutor("TAB self-shutdown test");
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowShutdown = new CountDownLatch(1);
        Runnable queued = mock(Runnable.class);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    if (!allowShutdown.await(2, TimeUnit.SECONDS)) return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                executor.shutdown();
                finished.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.execute(queued);
            allowShutdown.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        } finally {
            allowShutdown.countDown();
            executor.shutdown();
        }
        verifyNoInteractions(queued);
    }

    @Test
    void timedTaskOnlyReportsUsageWhenTrackingIsEnabled() {
        CpuManager cpu = mock(CpuManager.class);
        Runnable work = mock(Runnable.class);
        TimedCaughtTask task = new TimedCaughtTask(cpu, work, "feature", "task");
        task.run();
        verify(work).run();
        verify(cpu, never()).addTime(anyString(), anyString(), anyLong());
        when(cpu.isTrackUsage()).thenReturn(true);
        task.run();
        verify(work, times(2)).run();
        verify(cpu).addTime(eq("feature"), eq("task"), anyLong());
    }
}
