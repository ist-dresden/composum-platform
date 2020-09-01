package com.composum.platform.commons.util;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@RunWith(Parameterized.class)
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
            service = new ScheduledThreadPoolExecutor(3);
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
            timesExecuted = 1;
            return 1;
        });
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted, is(0));
        Thread.sleep(100);
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(timesExecuted, is(1));
        ec.checkThat(future.get(), is(1));
    }

    @Test
    public void checkSchedule() throws Exception {
        System.out.println(System.currentTimeMillis());
        ScheduledFuture<Integer> future = service.schedule(() -> {
            executionTime = System.currentTimeMillis();
            System.out.println("Done: " + System.currentTimeMillis());
            return 1;
        }, 50, TimeUnit.MILLISECONDS);
        ec.checkThat(future.isDone(), is(false));
        Thread.sleep(100);
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(future.get(), is(1));
        long timing = executionTime - begin;
        ec.checkThat("" + timing, timing >= 50, is(true));
        ec.checkThat("" + timing, timing < 100, is(true));
    }

    @Test
    public void checkPeriodicScheduling() throws Exception {
        ScheduledFuture<?> future;
        if (fixedDelay) {
            future = service.scheduleWithFixedDelay(this::execute3Times, 75, 50, TimeUnit.MILLISECONDS);
        } else {
            future = service.scheduleAtFixedRate(this::execute3Times, 75, 50, TimeUnit.MILLISECONDS);
        }
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted, is(0));
        Thread.sleep(50);
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted, is(0));
        System.out.println(future.isDone());
        Thread.sleep(50);
        ec.checkThat(timesExecuted, is(1));
        System.out.println(future.isDone());
        Thread.sleep(50);
        System.out.println(future.isDone());
        Thread.sleep(50);
        System.out.println(future.isDone());
        Thread.sleep(50);
        System.out.println(future.isDone());
        ec.checkThat(future.isDone(), is(true));
        ec.checkThat(service.shutdownNow().size(), is(0));
        try {
            future.get(5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            ec.checkThat(e.getCause(), instanceOf(IllegalStateException.class));
        }
        ec.checkThat(timesExecuted, is(3));
    }

    @Test
    public void checkCancelPeriodical() throws Exception {
        ScheduledFuture<?> future;
        if (fixedDelay) {
            future = service.scheduleWithFixedDelay(this::execute3Times, 75, 50, TimeUnit.MILLISECONDS);
        } else {
            future = service.scheduleAtFixedRate(this::execute3Times, 75, 50, TimeUnit.MILLISECONDS);
        }
        Thread.sleep(100);
        ec.checkThat(future.isDone(), is(false));
        ec.checkThat(timesExecuted, is(1));
        future.cancel(true);
        ec.checkThat(future.isDone(), is(true));
        Thread.sleep(150);
        ec.checkThat(timesExecuted, is(1));
    }

    int timesExecuted = 0;

    protected void execute3Times() {
        System.out.println("execute3Times at " + (System.currentTimeMillis() - begin));
        timesExecuted++;
        if (timesExecuted >= 3) {
            throw new IllegalStateException();
        }
    }

}
