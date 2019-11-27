package com.composum.platform.commons.crypt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Service that makes it easier to use cryptography. */
public interface CryptoService {

    /**
     * Encrypts the text using the given key. Inverse is {@link #decrypt(CharSequence, String)}.
     *
     * @return the base64-encoded ciphertext (filename and URL-safe); null if given text is null
     */
    @Nullable
    String encrypt(@Nullable CharSequence text, @Nonnull String key);

    /**
     * Decrypts the ciphertext using the given key. Inverse of {@link #encrypt(CharSequence, String)}.
     *
     * @param ciphertext the base64-encoded cipertext (filename and URL-safe)
     * @return null if ciphertext is null.
     * @throws IllegalArgumentException whenever the argument could not be decrypted
     */
    @Nullable
    String decrypt(@Nullable CharSequence ciphertext, @Nonnull String key) throws IllegalArgumentException;

    /**
     * Encrypts the text using the given key. Inverse is {@link #decrypt(byte[], String)}.
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
     * Encrypts the contents of a stream using the given key. Inverse is {@link #decrypt(InputStream, OutputStream, String)}.
     *
     * @return true if there actually was a messageStream
     */
    boolean encrypt(@Nullable InputStream messageStream, @Nonnull OutputStream cipherStream, @Nonnull String key) throws IOException;

    /**
     * Decrypts the contents of a stream using the given key. Inverse of
     * {@link #encrypt(InputStream, OutputStream, String)}.
     *
     * @return true if there actually was a cipherStream
     * @throws IllegalArgumentException whenever the argument could not be decrypted
     */
    boolean decrypt(@Nullable InputStream cipherStream, @Nonnull OutputStream messageStream, @Nonnull String key) throws IllegalArgumentException, IOException;

    /**
     * Generates a strong random key that could be used with the other methods. This could be used for instance to
     * generate a key at system deployment time.
     *
     * @return key consisting of about 44 chars in the default implementation, URL- and filenamesafe
     */
    @Nonnull
    String makeKey();

}
