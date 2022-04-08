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

    /** For quick time simulation. */
    private long currentTime = System.currentTimeMillis();

    TokenizedShorttermStoreServiceImpl store = new TokenizedShorttermStoreServiceImpl() {
        {storealias = store;}

        @Override
        protected long getCurrentTimeMillis() {
            return currentTime;
        }
    };

    @Test
    public void storeAndRetrieve() {
        Object stored = new Object();
        String token = store.checkin(stored, 1000);
        ec.checkThat(token, Matchers.notNullValue());
        ec.checkThat(token.length(), Matchers.is(32));
        ec.checkThat(store.peek(token, stored.getClass()), sameInstance(stored));
        ec.checkThat(store.checkout(token, stored.getClass()), sameInstance(stored));
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
        currentTime += 100;
        ec.checkThat(store.checkout(token, stored.getClass()), sameInstance(stored));

        token = store.checkin(stored, 200);
        currentTime += 300;
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.nullValue());
    }

    @Test
    public void cleanup() throws InterruptedException {
        Object stored = new Object();
        for (int i = 0; i < 10; ++i) {
            store.checkin(stored, 200);
        }
        ec.checkThat(storealias.size(), Matchers.is(10));

        currentTime += 100;
        // at 100ms, other items have 100ms to time out
        for (int i = 0; i < 20; ++i) {
            store.checkin(stored, 200);
        }
        ec.checkThat(storealias.size(), Matchers.is(30));

        currentTime += 150;
        // at 250ms the first items have timed out, but the others not
        store.checkout("bla", Object.class); // calls cleanup
        ec.checkThat(storealias.size(), Matchers.is(20));

        currentTime += 100;
        // at 350ms all items have timed out
        store.checkout("bla", Object.class); // calls cleanup
        ec.checkThat(storealias.size(), Matchers.is(0));
    }

    @Test
    public void push() throws InterruptedException {
        Object stored = new Object();
        String token = store.checkin(stored, 200);
        currentTime += 100;
        ec.checkThat(store.peek(token, stored.getClass()), sameInstance(stored));

        // store something that should be valid for 300 more milliseconds
        Object replacement = new Object();
        store.push(token, replacement, 300);

        // now check after 200 milliseconds that the replacement is there - the original would have been timed out.
        currentTime += 200;
        ec.checkThat(store.peek(token, stored.getClass()), sameInstance(replacement));

        // after 100 more milliseconds the replacement should have been timed out.
        currentTime += 100;
        ec.checkThat(store.checkout(token, stored.getClass()), Matchers.nullValue());
    }

}
