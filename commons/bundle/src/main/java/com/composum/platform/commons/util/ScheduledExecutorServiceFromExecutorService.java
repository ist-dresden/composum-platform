package com.composum.platform.commons.util;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter that turns an {@link java.util.concurrent.ExecutorService} (such as {@link SlingThreadPoolExecutorService})
 * into an {@link java.util.concurrent.ScheduledExecutorService}. Please remember to shut it down when discarded! It'll shut down the underlying ExecutorService, too.
 */
public class ScheduledExecutorServiceFromExecutorService implements ScheduledExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledExecutorServiceFromExecutorService.class);

    @Nonnull
    protected final ExecutorService executorService;

    /**
     * Queue of timed tasks; synchronize with {@link #queueLockObject}.
     */
    protected final PriorityQueue<DelayedTask<?>> queue = new PriorityQueue<>();

    protected final Object queueLockObject = new Object();

    protected final Future<?> processQueueTask;

    /**
     * Creates the scheduled executor service and sets the used executorService.
     *
     * @param executorService the underlying {@link ExecutorService}; will be shutdown if this is shutdown, too.
     */
    public ScheduledExecutorServiceFromExecutorService(@Nonnull ExecutorService executorService) {
        this.executorService = executorService;
        this.processQueueTask = executorService.submit(this::processQueue);
    }

    /**
     * The task that checks whether there are some scheduled tasks to be done now. An endless loop that ends when the
     * executor is shutdown - this means one thread is tied up with this. :-(
     * // FIXME(hps,01.09.20) use executor repeatedly instead of continuous thread.
     */
    protected void processQueue() {
        try {
            while (!executorService.isShutdown()) {
                DelayedTask<?> nextTask;
                synchronized (queueLockObject) {
                    long waitTime = 10000;
                    nextTask = queue.peek();
                    if (nextTask != null) {
                        waitTime = nextTask.nextExecutionTime.get() - System.currentTimeMillis();
                    }
                    try {
                        LOG.debug("Waiting {}", waitTime);
                        queueLockObject.wait(waitTime, 0);
                    } catch (InterruptedException e) {
                        LOG.debug("Interrupted.");
                    }
                    nextTask = queue.peek();
                    if (nextTask != null && System.currentTimeMillis() < nextTask.nextExecutionTime.get()) {
                        nextTask = null; // repeat loop
                    } else { // work on it
                        nextTask = queue.poll();
                    }
                    LOG.debug("processQueue woken; task {} / {}", nextTask, queue.peek());
                }
                if (nextTask != null) {
                    nextTask.submittedFuture = executorService.submit(nextTask);
                }
            }
        } catch (Exception e) {
            LOG.error("ProcessQueue aborted!", e);
        }
    }

    /**
     * Stores the necessary data for one (scheduled) task to be executed.
     */
    protected static class DelayedTask<V> implements Runnable, Comparable<DelayedTask<V>> {
        @Nonnull
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
            public long getDelay(@Nonnull TimeUnit unit) {
                return TimeUnit.MICROSECONDS.convert(System.currentTimeMillis() - nextExecutionTime.get(), unit);
            }
        };

        public DelayedTask(@Nonnull Callable<V> callable, boolean repeated) {
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
                LOG.warn("Abnormal termination of task: {}", exception);
                future.completeExceptionally(exception);
            }
        }

        @Override
        public int compareTo(@Nonnull DelayedTask<V> o) {
            return new CompareToBuilder()
                    .append(nextExecutionTime, o.nextExecutionTime)
                    .append(callable, callable)
                    .build();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DelayedTask)) return false;
            return compareTo((DelayedTask) o) == 0;
        }

        protected boolean isLive() {
            return !future.isDone() && !future.isCancelled();
        }
    }

    @Nonnull
    @Override
    public ScheduledFuture<?> schedule(@Nonnull Runnable command, long delay, @Nonnull TimeUnit unit) {
        Callable<Void> callable = () -> {
            command.run();
            return null;
        };
        return schedule(callable, delay, unit);
    }

    @Nonnull
    @Override
    public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, @Nonnull TimeUnit unit) {
        DelayedTask<V> task = new DelayedTask<>(callable, false);
        task.nextExecutionTime.set(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(delay, unit));
        return queue(task);
    }

    @Nonnull
    protected <V> ScheduledFuture<V> queue(DelayedTask<V> task) {
        if (task.isLive()) {
            synchronized (queueLockObject) {
                queue.add(task);
                LOG.debug("Notify");
                queueLockObject.notifyAll();
            }
        }
        return task.scheduledFuture;
    }

    @Nonnull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@Nonnull Runnable command, long initialDelay, long period, @Nonnull TimeUnit unit) {
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

    @Nonnull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@Nonnull Runnable command, long initialDelay, long delay, @Nonnull TimeUnit unit) {
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

    @Nonnull
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
    public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        return executorService.awaitTermination(timeout, unit);
    }

    @Nonnull
    @Override
    public <T> Future<T> submit(@Nonnull Callable<T> task) {
        return executorService.submit(task);
    }

    @Nonnull
    @Override
    public <T> Future<T> submit(@Nonnull Runnable task, T result) {
        return executorService.submit(task, result);
    }

    @Nonnull
    @Override
    public Future<?> submit(@Nonnull Runnable task) {
        return executorService.submit(task);
    }

    @Nonnull
    @Override
    public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executorService.invokeAll(tasks);
    }

    @Nonnull
    @Override
    public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        return executorService.invokeAll(tasks, timeout, unit);
    }

    @Nonnull
    @Override
    public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executorService.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return executorService.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@Nonnull Runnable command) {
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
        public V get(long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }

        @Override
        public int compareTo(@Nonnull Delayed o) {
            return new CompareToBuilder()
                    .append(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS))
                    .append(future, future).build();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AbstractDelegatedScheduledFuture)) return false;
            return compareTo((AbstractDelegatedScheduledFuture) o) == 0;
        }
    }

}
