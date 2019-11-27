package com.composum.platform.commons.crypt;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
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

/** Default implementation of {@link CryptoService}. */
@Component
public class CryptoServiceImpl implements CryptoService {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoServiceImpl.class);

    private final SecureRandom secureRandom = SecureRandom.getInstanceStrong();

    public static final String ALGORITHM = "AES/GCM/NoPadding";

    /** Initialization vector length for {@link #ALGORITHM} = Cipher#getBlockSize(). */
    public static final int IVLEN = 16;

    /**
     * Random salt length for encoding password. This adds some protection for the password, which might be used in
     * various locations.
     */
    public static final int SALTLEN = 8;

    public CryptoServiceImpl() throws NoSuchPaddingException, NoSuchAlgorithmException {
        // encrypt("test", "test"); // just check immediately that there is no problem
        // FIXME(hps,27.11.19) reactivate this
    }

    @Nonnull
    protected Cipher makeCipher(String key, byte[] salt, byte[] iv, int mode) throws NoSuchAlgorithmException,
            NoSuchPaddingException, InvalidKeySpecException, InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(key.toCharArray(), salt, 65536, 192);
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
        Objects.requireNonNull(key);
        if (message == null) { return null; }
        try {
            byte[] salt = new byte[SALTLEN];
            secureRandom.nextBytes(salt);

            byte[] iv = new byte[IVLEN];
            secureRandom.nextBytes(iv);

            Cipher cipher = makeCipher(key, salt, iv, Cipher.ENCRYPT_MODE);
            byte[] cipherText = cipher.doFinal(message);

            byte[] result = new byte[4 + SALTLEN + 4 + IVLEN + cipherText.length];
            ByteBuffer buf = ByteBuffer.wrap(result);
            buf.putInt(salt.length);
            buf.put(salt);
            buf.putInt(iv.length);
            buf.put(iv);
            buf.put(cipherText);

            Arrays.fill(salt, (byte) 0); // minize traces in memory.
            Arrays.fill(iv, (byte) 0);
            return result;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) { // impossible here since tested in constructor
            throw new IllegalStateException(e);
        } catch (BadPaddingException | IllegalBlockSizeException | InvalidKeyException | InvalidKeySpecException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(@Nullable byte[] cipherText, @Nonnull String key) {
        Objects.requireNonNull(key);
        if (cipherText == null) { return null; }
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(cipherText);
            int saltLength = byteBuffer.getInt();
            if (saltLength < 1 || saltLength > 16) {
                throw new IllegalArgumentException("invalid salt len " + saltLength);
            }
            byte[] salt = new byte[saltLength];
            byteBuffer.get(salt);

            int ivLength = byteBuffer.getInt();
            if (ivLength < 12 || ivLength > 16) { throw new IllegalArgumentException("invalid iv length " + ivLength);}
            byte[] iv = new byte[ivLength];
            byteBuffer.get(iv);

            byte[] strippedCipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(strippedCipherText);

            Cipher cipher = makeCipher(key, salt, iv, Cipher.DECRYPT_MODE);
            byte[] message = cipher.doFinal(strippedCipherText);

            Arrays.fill(salt, (byte) 0); // minize traces in memory.
            Arrays.fill(iv, (byte) 0);
            return message;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) { // impossible here since tested in constructor
            throw new IllegalStateException(e);
        } catch (BadPaddingException | IllegalBlockSizeException | InvalidKeyException | InvalidKeySpecException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
