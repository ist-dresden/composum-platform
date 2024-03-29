package com.composum.platform.commons.util;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.junitcategory.SlowTest;
import com.composum.sling.platform.testing.testutil.junitcategory.TimingSensitive;
import org.apache.commons.lang3.RandomUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@RunWith(Parameterized.class)
@Category({SlowTest.class, TimingSensitive.class})
public class ScheduledExecutorServiceFromExecutorServiceTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();


    protected final ScheduledExecutorService service;
    protected final boolean fixedDelay;

    protected long begin;
    protected long executionTime;

    @Parameterized.Parameters(name = "{index}: service={0}, fixedDelay={1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {true, true}, {true, false}, {false, true}, {false, false},
        });
    }

    public ScheduledExecutorServiceFromExecutorServiceTest(boolean checkService, boolean fixedDelay) {
        if (checkService) { // the actual class to test
            service = new ScheduledExecutorServiceFromExecutorService(Executors.newFixedThreadPool(2));
        } else { // as a reference a normal scheduled executor
            service = new ScheduledThreadPoolExecutor(2);
        }
        this.fixedDelay = fixedDelay;
    }

    @Before
    public void startUp() {
        this.begin = System.currentTimeMillis();
    }

    @After
    public void shutDown() {
        service.shutdown();
    }

    @Test
    public void testSubmit() throws Exception {
        Future<Integer> future = service.submit(() -> {
            timesExecuted.set(1);
            return 1;
        });
        // ec.checkThat(timesExecuted, is(0)); // does usually but not always work
        ec.checkThat(future.isDone(), is(false));
        Thread.sleep(100);
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(timesExecuted.get(), is(1));
        ec.checkThat(future.get(5, TimeUnit.SECONDS), is(1));
    }

    @Test
    public void checkSchedule() throws Exception {
        System.out.println(System.currentTimeMillis());
        ScheduledFuture<Integer> future = service.schedule(() -> {
            executionTime = System.currentTimeMillis();
            System.out.println("Done: " + System.currentTimeMillis());
            return 1;
        }, 100, TimeUnit.MILLISECONDS);
        ec.checkThat(future.isDone(), is(false));
        Thread.sleep(200);
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(future.get(5, TimeUnit.SECONDS), is(1));
        long timing = executionTime - begin;
        ec.checkThat("" + timing, timing >= 100, is(true));
        ec.checkThat("" + timing, timing < 200, is(true));
    }

    @Test
    public void checkPeriodicScheduling() throws Exception {
        ScheduledFuture<?> future;
        if (fixedDelay) {
            future = service.scheduleWithFixedDelay(this::execute3Times, 300, 200, TimeUnit.MILLISECONDS);
        } else {
            future = service.scheduleAtFixedRate(this::execute3Times, 300, 200, TimeUnit.MILLISECONDS);
        }
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted.get(), is(0));
        Thread.sleep(200);
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted.get(), is(0));
        System.out.println(future.isDone());
        Thread.sleep(200);
        ec.checkThat(timesExecuted.get(), is(1));
        System.out.println(future.isDone());
        Thread.sleep(200);
        System.out.println(future.isDone());
        Thread.sleep(200);
        System.out.println(future.isDone());
        Thread.sleep(200);
        System.out.println(future.isDone());
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(service.shutdownNow().size(), is(0));
        try {
            future.get(5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            ec.checkThat(e.getCause(), instanceOf(IllegalStateException.class));
        }
        ec.checkThat(timesExecuted.get(), is(3));
    }

    @Test
    public void checkCancelPeriodical() throws Exception {
        ScheduledFuture<?> future;
        if (fixedDelay) {
            future = service.scheduleWithFixedDelay(this::execute3Times, 150, 100, TimeUnit.MILLISECONDS);
        } else {
            future = service.scheduleAtFixedRate(this::execute3Times, 150, 100, TimeUnit.MILLISECONDS);
        }
        while (timesExecuted.get() < 2) {
            Thread.sleep(20);
        }
        future.cancel(true);
        Thread.sleep(300);
        ec.checkThat(timesExecuted.get(), is(2)); // wasn't run again.
    }

    protected AtomicInteger counter = new AtomicInteger();

    @Test
    public void submitManyTasks() throws Exception {
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        int count = 20; // this works with 100000 too, but we use just a small number for regular tests.
        for (int i = 0; i < count; ++i) {
            ScheduledFuture<?> future = service.schedule(() -> {
                counter.incrementAndGet();
            }, RandomUtils.nextInt(1, 3), TimeUnit.MILLISECONDS);
            futures.add(future);

        }
        for (ScheduledFuture<?> future : futures) {
            future.get(1, TimeUnit.SECONDS);
        }
        ec.checkThat(counter.get(), is(count));
    }

    @Test
    public void checkOrdering() throws Exception {
        ScheduledFuture<?> future1 = service.schedule(() -> {
            timesExecuted.set(1);
        }, 200, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> future2 = service.schedule(() -> {
            otherExecuted.set(1);
        }, 100, TimeUnit.MILLISECONDS);
        future2.get(1, TimeUnit.SECONDS);
        ec.checkThat(timesExecuted.get(), is(0));
        ec.checkThat(otherExecuted.get(), is(1));
        future1.get(1, TimeUnit.SECONDS);
        ec.checkThat(timesExecuted.get(), is(1));
        ec.checkThat(otherExecuted.get(), is(1));
    }

    AtomicInteger timesExecuted = new AtomicInteger();
    AtomicInteger otherExecuted = new AtomicInteger();

    protected void execute3Times() {
        System.out.println("execute3Times at " + (System.currentTimeMillis() - begin));
        timesExecuted.incrementAndGet();
        if (timesExecuted.get() >= 3) {
            throw new IllegalStateException();
        }
    }

}
