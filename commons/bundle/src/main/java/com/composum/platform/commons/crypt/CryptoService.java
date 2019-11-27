package com.composum.platform.commons.crypt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Service that makes it easier to use cryptography. */
public interface CryptoService {

    /**
     * Encrypts the text with the given key. Inverse is {@link #decrypt(String, String)}.
     *
     * @return the base64-encoded ciphertext (filename and URL-safe); null if given text is null
     */
    @Nullable
    String encrypt(@Nullable CharSequence text, @Nonnull String key);

    /**
     * Decrypts the ciphertext using the given key. Inverse of {@link #encrypt(String, String)}.
     *
     * @param ciphertext the base64-encoded cipertext (filename and URL-safe)
     * @return null if ciphertext is null.
     * @throws IllegalArgumentException whenever the argument could not be decrypted
     */
    @Nullable
    String decrypt(@Nullable CharSequence ciphertext, @Nonnull String key) throws IllegalArgumentException;

    /**
     * Encrypts the text with the given key. Inverse is {@link #decrypt(byte[], String)}.
     *
     * @return the base64-encoded ciphertext; null if given text is null
     */
    @Nullable
    byte[] encrypt(@Nullable byte[] message, @Nonnull String key);

    /**
     * Decrypts the ciphertext using the given key. Inverse of {@link #encrypt(byte[], String)}.
     *
     * @param ciphertext the cipertext
     * @return null if ciphertext is null.
     * @throws IllegalArgumentException whenever the argument could not be decrypted
     */
    @Nullable
    byte[] decrypt(@Nullable byte[] ciphertext, @Nonnull String key) throws IllegalArgumentException;

    /**
     * Generates a strong random key that could be used with the other methods. This could be used for instance to
     * generate a key at system deployment time.
     *
     * @return key consisting of about 44 chars in the default implementation, URL- and filenamesafe
     */
    @Nonnull
    String makeKey();

}
