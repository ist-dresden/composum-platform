package com.composum.platform.commons.storage;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/** Implementation of {@link TokenizedShorttermStoreService}. */
@Component(
        service = {TokenizedShorttermStoreService.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Auth Token Store"
        }
)
public class TokenizedShorttermStoreServiceImpl implements TokenizedShorttermStoreService {

    private static final Logger LOG = LoggerFactory.getLogger(TokenizedShorttermStoreServiceImpl.class);

    /**
     * The actual store for the information: maps the tokens to the stored information. If a key is contained in
     * here, it is also put into the
     */
    protected final Map<String, Pair<Long, Object>> store = Collections.synchronizedMap(new HashMap<>());

    /**
     * For cleanup: contains pairs of expiration time (in terms of {@link java.lang.System#currentTimeMillis()} ) and
     * the key. Synchronize over it when writing / reading!
     */
    protected final PriorityQueue<Pair<Long, String>> tokenToDeleteQueue = new PriorityQueue<>(128,
            Comparator.comparing(Pair::getKey));

    protected final Random tokenGenerator = new SecureRandom();

    /** Deletes all timed out values. */
    protected void cleanup() {
        synchronized (tokenToDeleteQueue) {
            long now = System.currentTimeMillis();
            Pair<Long, String> item = tokenToDeleteQueue.peek();
            if (item == null || item.getLeft() > now) { return; }
            do {
                item = tokenToDeleteQueue.poll();
                if (item == null) { return; }
                if (item.getLeft() > now) { // put it back since first one isn't timed out yet, stop.
                    tokenToDeleteQueue.add(item);
                    return;
                }
                store.remove(item.getRight());
            } while (!tokenToDeleteQueue.isEmpty());
        }
    }

    @Nonnull
    @Override
    public <T> String checkin(@Nonnull T info, long timeoutms) {
        cleanup();
        long timeoutTime = System.currentTimeMillis() + timeoutms;
        String token;
        do {
            token = RandomStringUtils.random(32, 0, 0, true, true, null, tokenGenerator);
        } while (store.containsKey(token)); // overdoing it a little... :-)
        store.put(token, Pair.of(timeoutTime, info));
        synchronized (tokenToDeleteQueue) {
            tokenToDeleteQueue.add(Pair.of(timeoutTime, token));
        }
        return token;
    }

    @Nullable
    @Override
    public <T> T checkout(@Nonnull String token, Class<T> clazz) {
        return retrieve(token, clazz, true);
    }

    @Nullable
    @Override
    public <T> T peek(@Nonnull String token, Class<T> clazz) {
        return retrieve(token, clazz, false);
    }

    protected <T> T retrieve(@Nonnull String token, Class<T> clazz, boolean delete) {
        cleanup();
        Pair<Long, Object> stored = store.get(token);
        if (stored == null) {
            LOG.debug("No information to retrieve for token {}", token);
            return null;
        }
        if (delete) { store.remove(token); }

        Object value = stored.getRight();
        if (!clazz.isInstance(value)) {
            LOG.warn("Wrong type of information: stored {} but expected {}", value.getClass().getName(),
                    clazz.getName());
            return null;
        }
        if (stored.getLeft() < System.currentTimeMillis()) {
            LOG.debug("Token timed out: {}", token);
            return null;
        }

        cleanup();
        return clazz.cast(value);
    }
}
