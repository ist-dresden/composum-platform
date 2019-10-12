package com.composum.platform.commons.storage;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/** Tests for {@link TokenizedShorttermStoreServiceImpl}. */
public class TokenizedShorttermStoreServiceImplTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    protected Map<String, Pair<Long, Object>> storealias;

    TokenizedShorttermStoreServiceImpl store = new TokenizedShorttermStoreServiceImpl() {{
        storealias = store;
    }};

    @Test
    public void storeAndRetrieve() {
        Object stored = new Object();
        String token = store.checkin(stored, 1000);
        ec.checkThat(token, Matchers.notNullValue());
        ec.checkThat(token.length(), Matchers.is(32));
        ec.checkThat(store.peek(token, stored.getClass()), Matchers.sameInstance(stored));
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.sameInstance(stored));
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.nullValue());

        String token2 = store.checkin(stored, 100);
        ec.checkThat(token2, Matchers.not(Matchers.equalTo(token)));
    }

    @Test
    public void failingRetrieve() {
        Object stored = Integer.valueOf(35);
        String token = store.checkin(stored, 1000);
        ec.checkThat(token.length(), Matchers.is(32));

        ec.checkThat(store.checkout("invalidtoken", Object.class), Matchers.nullValue());

        Object retrieved = store.checkout(token, String.class);
        ec.checkThat(retrieved, Matchers.nullValue());
    }

    @Test
    public void timeout() throws InterruptedException {
        Object stored = new Object();
        String token = store.checkin(stored, 200);
        Thread.sleep(100);
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.sameInstance(stored));

        token = store.checkin(stored, 200);
        Thread.sleep(300);
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.nullValue());
    }

    @Test
    public void cleanup() throws InterruptedException {
        Object stored = new Object();
        for (int i = 0; i < 10; ++i) {
            store.checkin(stored, 200);
        }
        ec.checkThat(storealias.size(), Matchers.is(10));

        Thread.sleep(100);
        // at 100ms, other items have 100ms to time out
        for (int i = 0; i < 20; ++i) {
            store.checkin(stored, 200);
        }
        ec.checkThat(storealias.size(), Matchers.is(30));

        Thread.sleep(150);
        // at 250ms the first items have timed out, but the others not
        store.checkout("bla", Object.class); // calls cleanup
        ec.checkThat(storealias.size(), Matchers.is(20));

        Thread.sleep(100);
        // at 350ms all items have timed out
        store.checkout("bla", Object.class); // calls cleanup
        ec.checkThat(storealias.size(), Matchers.is(0));
    }

}
