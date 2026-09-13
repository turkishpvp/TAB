package me.neznamy.tab.shared.cpu;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import me.neznamy.tab.shared.TAB;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thread executor for accepting tasks to execute them in a single threaded executor.
 * All tasks are try/catch-ed and might track CPU usage if needed.
 */
public class ThreadExecutor {

    /** Timeout for finishing tasks when shutting down thread executor */
    private static final int SHUTDOWN_TIMEOUT = 5000;

    private final String threadName;
    private final ScheduledExecutorService executor;

    /**
     * Constructs new instance and starts new thread executor with give name.
     * 
     * @param   threadName
     *          Name of the created thread
     */
    public ThreadExecutor(@NotNull String threadName) {
        this.threadName = threadName;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder().setNameFormat(threadName).build());
        // Do not wait for delayed tasks (announcements, retries) on shutdown, it blocks reload/stop for seconds
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        this.executor = executor;
    }

    /**
     * Shuts down the executor.
     */
    public void shutdown() {
        long time = System.currentTimeMillis();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                List<Runnable> cancelledTasks = executor.shutdownNow();
                TAB.getInstance().getErrorManager().printError("Soft shutdown of thread " + threadName + " exceeded time limit of " + SHUTDOWN_TIMEOUT +
                        "ms, forcing shutdown. This may cause issues. Cancelling " + cancelledTasks.size() + " tasks.", null);
            }
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                TAB.getInstance().getErrorManager().printError("Hard shutdown of thread " + threadName + " exceeded time limit of " + SHUTDOWN_TIMEOUT +
                        "ms. This may cause issues.", null);
            }
        } catch (InterruptedException ignored) {
            // Shutdown successful
            TAB.getInstance().debug("Thread " + threadName + " shutdown in " + (System.currentTimeMillis() - time) + "ms");
        }
    }

    public void execute(@NotNull Runnable task) {
        if (executor.isShutdown()) return;
        executor.execute(new CaughtTask(task));
    }

    public void execute(@NotNull TimedCaughtTask task) {
        if (executor.isShutdown()) return;
        executor.execute(task);
    }

    public void executeLater(@NotNull TimedCaughtTask task, int delayMillis) {
        if (executor.isShutdown()) return;
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts a repeating task.
     *
     * @param   task
     *          Task to run periodically
     * @param   intervalMilliseconds
     *          How often should the task run
     */
    public void repeatTask(@NotNull TimedCaughtTask task, int intervalMilliseconds) {
        if (executor.isShutdown()) return;
        executor.scheduleAtFixedRate(task, intervalMilliseconds, intervalMilliseconds, TimeUnit.MILLISECONDS);
    }
}
