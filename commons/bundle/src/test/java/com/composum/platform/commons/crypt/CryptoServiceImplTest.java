package com.composum.platform.commons.crypt;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests {@link CryptoServiceImpl}.
 *
 * @see "https://proandroiddev.com/security-best-practices-symmetric-encryption-with-aes-in-java-7616beaaade9"
 */
public class CryptoServiceImplTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    private CryptoService service = new CryptoServiceImpl();

    public CryptoServiceImplTest() throws NoSuchPaddingException, NoSuchAlgorithmException {
    }

    @Test
    public void encryptDecrypt() {
        String key = "whateverencryptionkey";
        System.out.println(service.encrypt("test", key));
        ec.checkThat(service.decrypt(service.encrypt("test", key), key), is("test"));
        ec.checkThat(new String(service.decrypt(service.encrypt("test".getBytes(), key), key)), is("test"));
    }

    @Test
    public void encryptDecryptNull() {
        String key = "whateverencryptionkey";
        ec.checkThat(service.encrypt((String) null, key), nullValue());
        ec.checkThat(service.encrypt((byte[]) null, key), nullValue());
        ec.checkThat(service.decrypt((String) null, key), nullValue());
        ec.checkThat(service.decrypt((byte[]) null, key), nullValue());
    }

    @Test
    public void encryptedVaries() {
        String key = "key";
        String text = "test";
        String first = service.encrypt(text, key);
        String second = service.encrypt(text, key);
        ec.checkThat(first, not(is(second)));
        ec.checkThat(service.decrypt(first, key), is(text));
        ec.checkThat(service.decrypt(second, key), is(text));
    }

    @Test(expected = IllegalArgumentException.class)
    public void keyMatters() {
        ec.checkThat(service.decrypt(service.encrypt("test", "key1"), "key2"), not(is("test")));
    }

    @Test
    public void generateKey() {
        String key1 = service.makeKey();
        String key2 = service.makeKey();
        ec.checkThat(key1, not(isEmptyOrNullString()));
        ec.checkThat(key2, not(isEmptyOrNullString()));
        ec.checkThat(key1, not(is(key2)));
        ec.checkThat(key1.length(), greaterThanOrEqualTo(40));
    }

    @Test
    public void tryCryptAndDecrypt() throws Exception {
        final String key = "testkey";
        final String message = "somemessage";
        final byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        SecureRandom secureRandom = SecureRandom.getInstanceStrong();

        byte[] salt = new byte[8];
        secureRandom.nextBytes(salt);

        byte[] iv = new byte[16];
        secureRandom.nextBytes(iv);

        Cipher cipher = getCipher(key, salt, iv, Cipher.ENCRYPT_MODE);

        byte[] ciphertext = cipher.doFinal(messageBytes);

        // secureRandom.nextBytes(salt); // both salt and iv have to be kept for this to work.
        // secureRandom.nextBytes(iv);

        cipher = getCipher(key, salt, iv, Cipher.DECRYPT_MODE);

        byte[] decodedBytes = cipher.doFinal(ciphertext);
        String decodedMessage = new String(decodedBytes, StandardCharsets.UTF_8);
        ec.checkThat(decodedMessage, is(message));
    }

    @Test
    public void testMisc() throws Exception {
        Cipher cipher = Cipher.getInstance(CryptoServiceImpl.ALGORITHM);
        ec.checkThat(cipher.getBlockSize(), is(CryptoServiceImpl.IVLEN));
    }

    @Nonnull
    protected Cipher getCipher(String key, byte[] salt, byte[] iv, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        ec.checkThat(cipher.getBlockSize(), is(16));
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(key.toCharArray(), salt, 65536, 192);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        cipher.init(mode, secretKey, parameterSpec);
        return cipher;
    }

}
