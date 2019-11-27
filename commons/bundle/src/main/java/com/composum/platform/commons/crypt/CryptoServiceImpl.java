package com.composum.platform.commons.crypt;

import org.apache.commons.io.IOUtils;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Default implementation of {@link CryptoService}.
 *
 * @see "https://proandroiddev.com/security-best-practices-symmetric-encryption-with-aes-in-java-7616beaaade9"
 */
@Component(service = CryptoService.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Crypto Service"
        })
public class CryptoServiceImpl implements CryptoService {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoServiceImpl.class);

    private final SecureRandom secureRandom = SecureRandom.getInstanceStrong();

    protected static final String ALGORITHM = "AES/GCM/NoPadding";

    /** Initialization vector length for {@link #ALGORITHM} = Cipher#getBlockSize(). */
    protected static final int IVLEN = 16;

    /**
     * This is the number of rounds used to distribute the entropy in the password. 100 is not much to provide
     * additional protection of the password against brute force, but otherwise this decreases performance for small
     * stuff significantly.
     */
    protected static final int ITERATION_COUNT = 100;

    /**
     * Random salt length for encoding password. This adds some protection for the password, which might be used in
     * various locations.
     */
    protected static final int SALTLEN = 8;

    public CryptoServiceImpl() throws NoSuchAlgorithmException, IllegalArgumentException {
        // check immediately that there is no problem with the chosen algorithms
        String result = decrypt(encrypt("test", "testkey"), "testkey");
        if (!"test".equals(result)) { // BUG!
            throw new IllegalArgumentException("Crypt + decrypt quicktest yielded wrong result: " + result);
        }
    }

    @Nonnull
    protected Cipher makeCipher(String key, byte[] salt, byte[] iv, int mode) throws NoSuchAlgorithmException,
            NoSuchPaddingException, InvalidKeySpecException, InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(key.toCharArray(), salt, ITERATION_COUNT, 192);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        cipher.init(mode, secretKey, parameterSpec);
        return cipher;
    }

    @Override
    @Nonnull
    public String makeKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String key = Base64.getUrlEncoder().encodeToString(bytes).replaceAll("=", "");
        return key;
    }

    @Nullable
    @Override
    public String encrypt(@Nullable CharSequence text, @Nonnull String key) {
        Objects.requireNonNull(key);
        if (text == null) { return null; }
        byte[] ciphered = encrypt(text.toString().getBytes(StandardCharsets.UTF_8), key);
        String ciphertext = Base64.getUrlEncoder().encodeToString(ciphered);
        return ciphertext;
    }

    @Nullable
    @Override
    public String decrypt(@Nullable CharSequence ciphertext, @Nonnull String key) {
        Objects.requireNonNull(key);
        if (ciphertext == null) { return null; }
        byte[] ciphered = Base64.getUrlDecoder().decode(ciphertext.toString().getBytes(StandardCharsets.UTF_8));
        byte[] message = decrypt(ciphered, key);
        return new String(message, StandardCharsets.UTF_8);
    }

    @Nullable
    @Override
    public byte[] encrypt(@Nullable byte[] message, @Nonnull String key) {
        if (message == null) { return null; }
        ByteArrayOutputStream cipherStream = new ByteArrayOutputStream();
        try {
            encrypt(new ByteArrayInputStream(message), cipherStream, key);
        } catch (IOException e) { // should be impossible on ByteArrayOutputStream
            throw new IllegalArgumentException(e);
        }
        return cipherStream.toByteArray();
    }

    @Nullable
    @Override
    public byte[] decrypt(@Nullable byte[] ciphertext, @Nonnull String key) throws IllegalArgumentException {
        if (ciphertext == null) { return null; }
        ByteArrayOutputStream messageStream = new ByteArrayOutputStream();
        try {
            decrypt(new ByteArrayInputStream(ciphertext), messageStream, key);
        } catch (IOException e) { // should be impossible on ByteArrayOutputStream
            throw new IllegalArgumentException(e);
        }
        return messageStream.toByteArray();
    }

    @Override
    public boolean encrypt(@Nullable InputStream messageStream, @Nonnull OutputStream cipherStream, @Nonnull String key) throws IOException {
        Objects.requireNonNull(key);
        if (messageStream == null) { return false; }
        byte[] salt = new byte[SALTLEN];
        byte[] iv = new byte[IVLEN];
        try {
            secureRandom.nextBytes(salt);
            cipherStream.write((byte) salt.length);
            cipherStream.write(salt);

            secureRandom.nextBytes(iv);
            cipherStream.write((byte) iv.length);
            cipherStream.write(iv);

            Cipher cipher = makeCipher(key, salt, iv, Cipher.ENCRYPT_MODE);
            try (CipherOutputStream cipherOutputStream = new CipherOutputStream(cipherStream, cipher)) {
                IOUtils.copy(messageStream, cipherOutputStream);
            }

        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) { // impossible here since tested in constructor
            throw new IllegalStateException(e);
        } catch (InvalidKeyException | InvalidKeySpecException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Arrays.fill(salt, (byte) 0); // minize traces in memory.
            Arrays.fill(iv, (byte) 0);
        }
        return true;
    }

    @Override
    public boolean decrypt(@Nullable InputStream cipherStream, @Nonnull OutputStream messageStream, @Nonnull String key) throws IllegalArgumentException, IOException {
        Objects.requireNonNull(key);
        if (cipherStream == null) { return false; }
        byte[] salt = null;
        byte[] iv = null;
        try {
            int saltLength = cipherStream.read();
            if (saltLength < 1 || saltLength > 16) {
                throw new IllegalArgumentException("invalid salt len " + saltLength);
            }
            salt = new byte[saltLength];
            int read = cipherStream.read(salt);
            if (read != saltLength) {
                throw new IllegalArgumentException("Could not read complete salt but only " + read);
            }

            int ivLength = cipherStream.read();
            if (ivLength < 12 || ivLength > 16) { throw new IllegalArgumentException("invalid iv length " + ivLength);}
            iv = new byte[ivLength];
            read = cipherStream.read(iv);
            if (read != ivLength) {
                throw new IllegalArgumentException("Could not read complete iv but only " + read);
            }

            Cipher cipher = makeCipher(key, salt, iv, Cipher.DECRYPT_MODE);
            try (CipherInputStream cipherInputStream = new CipherInputStream(cipherStream, cipher)) {
                IOUtils.copy(cipherInputStream, messageStream);
            }
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) { // impossible here since tested in constructor
            throw new IllegalStateException(e);
        } catch (InvalidKeyException | InvalidKeySpecException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e);
        } finally { // minize traces in memory.
            if (salt != null) { Arrays.fill(salt, (byte) 0); }
            if (iv != null) { Arrays.fill(iv, (byte) 0); }
        }
        return true;
    }

}
