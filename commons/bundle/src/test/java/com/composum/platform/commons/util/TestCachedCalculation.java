package com.composum.platform.commons.util;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

/** Tests for {@link CachedCalculation}. */
public class TestCachedCalculation {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    final long timeoutMillis = 100;

    AtomicInteger lastValue = new AtomicInteger(0);

    private ExecutorService executor;

    private CachedCalculation<Integer, InterruptedException> cached =
            new CachedCalculation<Integer, InterruptedException>(this::supplier, timeoutMillis);

    private Integer supplier() throws InterruptedException {
        Thread.sleep(timeoutMillis / 2);
        return lastValue.incrementAndGet();
    }

    @Test
    public void checkCaching() throws InterruptedException {
        ec.checkThat(cached.getCachedValue(false), nullValue());
        ec.checkThat(cached.getCachedValue(true), nullValue());

        {
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue();
            long end = System.currentTimeMillis();
            ec.checkThat(val, is(1));
            ec.checkThat(end - begin, allOf(lessThan(timeoutMillis), greaterThanOrEqualTo(timeoutMillis / 2)));
        }

        ec.checkThat(cached.getCachedValue(false), is(1));
        ec.checkThat(cached.getCachedValue(true), is(1));

        {
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue();
            long end = System.currentTimeMillis();
            ec.checkThat(val, is(1));
            ec.checkThat(end - begin, lessThan(timeoutMillis / 10));
        }

        Thread.sleep(timeoutMillis);

        ec.checkThat(cached.getCachedValue(false), nullValue());
        ec.checkThat(cached.getCachedValue(true), is(1));

        {
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue();
            long end = System.currentTimeMillis();
            ec.checkThat(val, is(2));
            ec.checkThat(end - begin, allOf(lessThan(timeoutMillis), greaterThanOrEqualTo(timeoutMillis / 2)));
        }

        cached.invalidate();

        {
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue(() -> 17, false);
            long end = System.currentTimeMillis();
            ec.checkThat(val, is(17));
            ec.checkThat(end - begin, lessThan(timeoutMillis / 10));

            ec.checkThat(cached.giveValue(() -> 19, false), is(17));
        }

        {
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue(() -> {
                Thread.sleep(timeoutMillis / 2);
                return 21;
            }, true);
            long end = System.currentTimeMillis();
            ec.checkThat(val, is(21));
            ec.checkThat(end - begin, allOf(lessThan(timeoutMillis), greaterThanOrEqualTo(timeoutMillis / 2)));

            ec.checkThat(cached.giveValue(() -> 22, false), is(21));
        }
    }

    @Test
    public void calculationOnlyOnce() throws ExecutionException, InterruptedException {
        Future<Integer> future1 = executor.submit(() -> cached.giveValue());
        Future<Integer> future2 = executor.submit(() -> cached.giveValue());
        ec.checkThat(future1.get(), is(1));
        ec.checkThat(future2.get(), is(1));
        Thread.sleep(timeoutMillis);
        future1 = executor.submit(() -> cached.giveValue());
        future2 = executor.submit(() -> cached.giveValue());
        ec.checkThat(future1.get(), is(2));
        ec.checkThat(future2.get(), is(2));
        future1 = executor.submit(() -> cached.giveValue(null, true));
        future2 = executor.submit(() -> cached.giveValue(null, true));
        ec.checkThat(future1.get(), is(3));
        ec.checkThat(future2.get(), is(3));
    }

    @Test
    public void returnCachedDuringForcedCalculation() throws ExecutionException, InterruptedException {
        ec.checkThat(cached.giveValue(), is(1));
        SynchronousQueue<Boolean> queue = new SynchronousQueue<>();
        Future<Integer> futureForced = executor.submit(() -> {
            return cached.giveValue(() -> {
                queue.put(true);
                return supplier();
            }, true);
        });
        Future<Integer> futureCached = executor.submit(() -> {
            queue.take(); // ensure this is run only after first calculation is started.
            long begin = System.currentTimeMillis();
            Integer val = cached.giveValue();
            long end = System.currentTimeMillis();
            // should not block - done before futureforced is done
            ec.checkThat(end - begin, lessThan(timeoutMillis / 10));
            return val;
        });
        ec.checkThat(futureCached.get(), is(1)); // retrieved during forced calculation - cached value
        ec.checkThat(futureForced.get(), is(2));
    }

    @Before
    public void setup() {
        executor = Executors.newFixedThreadPool(2);
    }

    @After
    public void teardown() {
        executor.shutdownNow();
    }

}
