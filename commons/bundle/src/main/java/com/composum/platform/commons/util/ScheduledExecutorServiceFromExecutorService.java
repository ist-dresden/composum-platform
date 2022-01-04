package com.composum.platform.commons.util;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter that turns an {@link java.util.concurrent.ExecutorService} (such as {@link SlingThreadPoolExecutorService})
 * into an {@link java.util.concurrent.ScheduledExecutorService}. Please remember to shut it down when discarded! It'll shut down the underlying ExecutorService, too.
 * This mostly ties up one thread of the pool for the scheduling mechanism, so take care not to run too many
 * executors on the same threadpool. (We do, however, free the thread again and again).
 */
public class ScheduledExecutorServiceFromExecutorService implements ScheduledExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledExecutorServiceFromExecutorService.class);

    @NotNull
    protected final ExecutorService executorService;

    /**
     * Queue of timed tasks; synchronize with {@link #queueLockObject}.
     */
    protected final PriorityQueue<DelayedTask<?>> queue = new PriorityQueue<>();

    protected final Object queueLockObject = new Object();

    /**
     * Creates the scheduled executor service and sets the used executorService.
     *
     * @param executorService the underlying {@link ExecutorService}; will be shutdown if this is shutdown, too.
     */
    public ScheduledExecutorServiceFromExecutorService(@NotNull ExecutorService executorService) {
        this.executorService = executorService;
        executorService.submit(this::processQueue);
    }

    /**
     * The task that checks whether there are some scheduled tasks to be done now. It waits until there is
     * something to do, submits all that needs to be done now and then puts itself into the executors queue
     * to avoid blocking it.
     */
    protected void processQueue() {
        try {
            DelayedTask<?> nextTask;
            synchronized (queueLockObject) {
                // wait until the first task needs to be run
                long waitTime = 1000;
                nextTask = queue.peek();
                if (nextTask != null) {
                    waitTime = nextTask.nextExecutionTime.get() - System.currentTimeMillis();
                }
                if (waitTime > 0) {
                    try { // waits for notifications from #queue
                        LOG.debug("Wait for {} ms: @{}", waitTime, System.identityHashCode(this));
                        queueLockObject.wait(waitTime, 0);
                    } catch (InterruptedException e) {
                        LOG.debug("Interrupted. @{}", System.identityHashCode(this));
                    }
                }
            }
            do {
                synchronized (queueLockObject) {
                    nextTask = queue.peek();
                    if (nextTask != null && System.currentTimeMillis() < nextTask.nextExecutionTime.get()) {
                        nextTask = null; // next one is not ready - repeat loop.
                    } else { // remove it from the queue and work on it now
                        nextTask = queue.poll();
                    }
                }
                if (nextTask != null) {
                    LOG.debug("Task submitted for execution: {} at @{}", nextTask, System.identityHashCode(this));
                    nextTask.submittedFuture = executorService.submit(nextTask);
                }
            } while (nextTask != null && !executorService.isShutdown());
            if (!executorService.isShutdown()) { // repeat this, but let other pending tasks have their share of CPU time
                executorService.submit(this::processQueue);
            }
        } catch (Exception e) {
            LOG.error("Bug: ProcessQueue aborted, executor is now dysfunctional! @{}", System.identityHashCode(this), e);
        }
    }

    /**
     * Stores the necessary data for one (scheduled) task to be executed.
     */
    protected static class DelayedTask<V> implements Runnable, Comparable<DelayedTask<V>> {
        @NotNull
        protected final Callable<V> callable;
        /**
         * The future that saves the data from the callable, if appropriate.
         */
        protected final CompletableFuture<V> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                Future<?> submitted = submittedFuture;
                if (submitted != null) {
                    submitted.cancel(mayInterruptIfRunning);
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        protected final boolean repeated;
        /**
         * Future for the task that's currently running / was last run, if appropriate.
         */
        protected volatile Future<?> submittedFuture;
        /**
         * {@link System#currentTimeMillis()} when the task should be next executed.
         */
        protected final AtomicLong nextExecutionTime = new AtomicLong();

        /**
         * A wrapper delegating to {@link #future} that serves as a ScheduledFuture.
         */
        protected ScheduledFuture<V> scheduledFuture = new AbstractDelegatedScheduledFuture<>(future) {
            @Override
            public long getDelay(@NotNull TimeUnit unit) {
                return TimeUnit.MILLISECONDS.convert(System.currentTimeMillis() - nextExecutionTime.get(), unit);
            }
        };

        public DelayedTask(@NotNull Callable<V> callable, boolean repeated) {
            this.callable = callable;
            this.repeated = repeated;
        }

        @Override
        public void run() {
            try {
                if (isLive()) {
                    V result = callable.call();
                    if (!repeated) {
                        future.complete(result);
                    }
                }
            } catch (Exception exception) {
                LOG.warn("Abnormal termination of task: {} in @{}", exception, System.identityHashCode(this), exception);
                future.completeExceptionally(exception);
            }
        }

        @Override
        public int compareTo(@NotNull DelayedTask<V> o) {
            return Long.compare(nextExecutionTime.get(), o.nextExecutionTime.get());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DelayedTask)) return false;
            return compareTo((DelayedTask) o) == 0;
        }

        protected boolean isLive() {
            return !future.isDone() && !future.isCancelled();
        }


        @Override
        public String toString() {
            return "DelayedTask@" + System.identityHashCode(DelayedTask.this);
        }
    }

    @NotNull
    @Override
    public ScheduledFuture<?> schedule(@NotNull Runnable command, long delay, @NotNull TimeUnit unit) {
        Callable<Void> callable = () -> {
            command.run();
            return null;
        };
        return schedule(callable, delay, unit);
    }

    @NotNull
    @Override
    public <V> ScheduledFuture<V> schedule(@NotNull Callable<V> callable, long delay, @NotNull TimeUnit unit) {
        DelayedTask<V> task = new DelayedTask<>(callable, false);
        task.nextExecutionTime.set(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(delay, unit));
        return queue(task);
    }

    @NotNull
    protected <V> ScheduledFuture<V> queue(DelayedTask<V> task) {
        if (task.isLive()) {
            synchronized (queueLockObject) {
                queue.add(task);
                queueLockObject.notifyAll();
            }
        }
        return task.scheduledFuture;
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NotNull Runnable command, long initialDelay, long period, @NotNull TimeUnit unit) {
        DelayedTask<Void> task = new DelayedTask<>(() -> {
            command.run();
            return null;
        }, true) {
            @Override
            public void run() {
                nextExecutionTime.addAndGet(period);
                super.run();
                queue(this);
            }
        };
        task.nextExecutionTime.set(System.currentTimeMillis() + initialDelay);
        return queue(task);
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable command, long initialDelay, long delay, @NotNull TimeUnit unit) {
        DelayedTask<Void> task = new DelayedTask<>(() -> {
            command.run();
            return null;
        }, true) {
            @Override
            public void run() {
                super.run();
                nextExecutionTime.set(System.currentTimeMillis() + delay);
                queue(this);
            }
        };
        task.nextExecutionTime.set(System.currentTimeMillis() + initialDelay);
        return queue(task);
    }

    // delegated methods

    @Override
    public void shutdown() {
        executorService.shutdown();
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        return executorService.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return executorService.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return executorService.awaitTermination(timeout, unit);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Callable<T> task) {
        return executorService.submit(task);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Runnable task, T result) {
        return executorService.submit(task, result);
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull Runnable task) {
        return executorService.submit(task);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executorService.invokeAll(tasks);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return executorService.invokeAll(tasks, timeout, unit);
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executorService.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return executorService.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@NotNull Runnable command) {
        executorService.execute(command);
    }

    /**
     * Presents a {@link Future} as a {@link ScheduledFuture}: all calls are forwarded to future, except
     * {@link #getDelay(TimeUnit)} which needs to be implemented in derived classes.
     */
    protected static abstract class AbstractDelegatedScheduledFuture<V> implements ScheduledFuture<V> {

        protected final Future<V> future;

        protected AbstractDelegatedScheduledFuture(Future<V> future) {
            this.future = future;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return future.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return future.get();
        }

        @Override
        public V get(long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }

        @Override
        public int compareTo(@NotNull Delayed o) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AbstractDelegatedScheduledFuture)) return false;
            return compareTo((AbstractDelegatedScheduledFuture) o) == 0;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "{" + executorService + "}";
    }

}
