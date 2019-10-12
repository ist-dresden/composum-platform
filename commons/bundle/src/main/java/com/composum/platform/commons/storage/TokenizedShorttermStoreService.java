package com.composum.platform.commons.storage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A service to store arbitrary pojos for a short time, generating secure random tokens to retrieve them later.
 * Caution: this is just meant for a couple of seconds or minutes, and not meant for information you cannot lose
 * - the storage will not even survive a restart of the bundle.
 */
public interface TokenizedShorttermStoreService {

    /**
     * Saves some information in the token store, returning a secure random token that can be used to access this later
     * with {@link #checkout(String, Class)} .
     *
     * @param <T>       the type of {info}
     * @param info      the information to store at the token
     * @param timeoutms timeout in milliseconds after which the information will be deleted.
     * @return an alpanumeric token to access the information later
     */
    @Nonnull
    <T> String checkin(@Nonnull T info, long timeoutms);

    /**
     * Retrieves the information stored at the token. The stored information is deleted and cannot be retrieved
     * again.
     *
     * @param <T>   the type of the stored information
     * @param token the token
     * @param clazz the type of the stored information
     * @return the stored information if it hasn't timed out yet and if it conforms to {clazz}, otherwise null
     */
    @Nullable
    <T> T checkout(@Nonnull String token, Class<T> clazz);

    /**
     * Gives a peek at the information stored at the token - it is not deleted.
     *
     * @param <T>   the type of the stored information
     * @param token the token
     * @param clazz the type of the stored information
     * @return the stored information if it hasn't timed out yet and if it conforms to {clazz}, otherwise null
     */
    @Nullable
    <T> T peek(@Nonnull String token, Class<T> clazz);

}
