package com.composum.platform.commons.util;

import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.*;

/**
 * Adapter for a Sling {@link ThreadPool} which presents it as an {@link java.util.concurrent.ExecutorService},
 * since that is required for many uses of thread pools. Thus, it's possible to use Slings configuration features
 * with more advanced uses for threadpools.
 */
public class SlingThreadPoolExecutorService extends AbstractExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(SlingThreadPoolExecutorService.class);

    protected final String name;

    @Nonnull
    protected ThreadPoolManager threadPoolManager;

    protected volatile ThreadPool threadPool;

    /**
     * Retrieves the threadpool with the given name. Remember to {@link #shutdown()}!
     */
    public SlingThreadPoolExecutorService(@Nonnull ThreadPoolManager threadPoolManager, @Nonnull String name) {
        this.threadPoolManager = threadPoolManager;
        threadPool = threadPoolManager.get(name);
        this.name = name;
    }

    /**
     * Frees resources - remember to call this when no longer used.
     */
    @Override
    public synchronized void shutdown() {
        ThreadPool oldPool = threadPool;
        threadPool = null;
        if (oldPool != null) {
            threadPoolManager.release(oldPool);
        }
    }

    /**
     * Last resort in case of misuses.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (threadPool != null) {
                LOG.error("Executor was not shutdown!");
            }
            shutdown();
        } finally {
            super.finalize();
        }
    }

    /**
     * Not implemented.
     *
     * @deprecated not implemented; tell us if you really need this.
     */
    @Deprecated
    @Nonnull
    @Override
    public List<Runnable> shutdownNow() {
        throw new UnsupportedOperationException("Not implemented yet: SlingThreadPoolExecutorService.shutdownNow");
    }

    @Override
    public boolean isShutdown() {
        return threadPool == null;
    }

    /**
     * Not really implemented - we log a warning and return false to be on the cautious side.
     *
     * @deprecated not implemented; tell us if you really need this.
     */
    @Deprecated
    @Override
    public boolean isTerminated() {
        LOG.warn("SlingThreadPoolExecutorService.isTerminated is not really implemented but was called", new Exception("Not thrown - just for stacktrace logging"));
        return false;
    }

    /**
     * Not implemented.
     *
     * @deprecated not implemented; tell us if you really need this.
     */
    @Deprecated
    @Override
    public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException("Not implemented yet: SlingThreadPoolExecutorService.awaitTermination");
    }

    @Override
    public void execute(@Nonnull Runnable command) throws RejectedExecutionException {
        getSlingThreadpool().execute(command);
    }

    @Nonnull
    protected ThreadPool getSlingThreadpool() throws RejectedExecutionException {
        ThreadPool theThreadpool = threadPool;
        if (isShutdown() || theThreadpool == null) {
            throw new RejectedExecutionException("Executor is already shut down");
        }
        return theThreadpool;
    }

    @Nonnull
    @Override
    public Future<?> submit(Runnable task) throws RejectedExecutionException {
        return getSlingThreadpool().submit(task);
    }

    @Nonnull
    @Override
    public <T> Future<T> submit(Callable<T> task) throws RejectedExecutionException {
        return getSlingThreadpool().submit(task);
    }

    @Override
    public String toString() {
        return super.toString() + "{" + name + "}";
    }

}
