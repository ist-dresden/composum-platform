package com.composum.platform.commons.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Some support utilities to create a token containing several bits of information such that these can be put together and later extracted safely again.
 * We use a format using the length of each part as prefix: a token consisting of "part1", "thepart2" and "apart3" will look like
 * "3|5:part1|8:part2|6:apart3" - the first number is the number of parts as a quick sanity check that nothing was lost.
 */
public class TokenUtil {

    private static final Logger LOG = LoggerFactory.getLogger(TokenUtil.class);

    /**
     * Joins some parts into a token. We take the {@link String#valueOf(Object)} of each part.
     */
    @Nonnull
    public static String join(@Nonnull Object... parts) {
        StringBuilder token = new StringBuilder().append(parts.length).append("|");
        boolean notFirst = false;
        for (Object part : parts) {
            if (notFirst) {
                token.append("|");
            }
            notFirst = true;
            if (part == null) {
                token.append("-");
            } else {
                String partString = String.valueOf(part);
                token.append(partString.length()).append(":").append(partString);
            }
        }
        return token.toString();
    }

    /**
     * Undoes {@link #join(Object...)} in that it extracts the string forms of the parts that were joined into the token.
     *
     * @param token the token to decode
     * @return a list of the parts, some may be null
     * @throws IllegalArgumentException if there was something wrong with the token
     */
    @Nonnull
    public static List<String> extract(@Nullable String token) throws IllegalArgumentException {
        try {
            List<String> res = new ArrayList<>();
            int position = 0;
            int lengthEnd = token.indexOf('|');
            int numberOfParts = Integer.valueOf(token.substring(0, lengthEnd));
            position = lengthEnd + 1;
            while (position < token.length()) {
                if (token.charAt(position) == '-') {
                    res.add(null);
                    position++;
                } else {
                    int endLength = token.indexOf(':', position);
                    int length = Integer.valueOf(token.substring(position, endLength));
                    String part = token.substring(endLength + 1, endLength + 1 + length);
                    res.add(part);
                    position = endLength + 1 + length;
                }
                if (position < token.length()) {
                    if (token.charAt(position) != '|') {
                        throw new IllegalArgumentException("Expecting | at position " + (position + 1));
                    }
                    position++;
                }
            }
            if (res.size() != numberOfParts) {
                throw new IllegalArgumentException("Some parts were missing: got " + res.size() + " but should have " + numberOfParts);
            }
            return res;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // wrap it into IllegalArgumentException - there are several possibilities
            // which might occur if the format was broken.
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Undoes {@link #join(Object...)} in that it extracts the string forms of the parts that were joined into the token.
     *
     * @param token          the token to decode
     * @param expectedLength the number of parts the token should have
     * @return a list of the parts, has size {expectedLength}, some may be null
     * @throws IllegalArgumentException if there was something wrong with the token
     */
    @Nonnull
    public static List<String> extract(@Nullable String token, int expectedLength) throws IllegalArgumentException {
        List<String> extracted = extract(token);
        if (extracted.size() != expectedLength) {
            throw new IllegalArgumentException("Expected token with " + expectedLength + " parts.");
        }
        return extracted;
    }

    /**
     * Makes the token resistant against accidential tampering by adding a hash. If the result of this is encrypted, this results in some kind of digital signature.
     */
    @Nonnull
    public static String addHash(@Nonnull String token) {
        String salt = RandomStringUtils.randomAlphanumeric(8);
        String hash = Base64.getUrlEncoder().encodeToString(DigestUtils.sha256(salt + token));
        return "#" + salt + "|" + hash + "|" + token;
    }

    /**
     * Removes a hash added with {@link #addHash(String)}.
     */
    public static String removeHash(@Nonnull String tokenWithHash) {
        int firstBar = StringUtils.indexOf(tokenWithHash, '|');
        int secondBar = StringUtils.indexOf(tokenWithHash, '|', firstBar + 1);
        if (!StringUtils.startsWith(tokenWithHash, "#") || firstBar < 0 || secondBar < 0) {
            LOG.debug("Not a token with hash: {}", tokenWithHash);
            throw new IllegalArgumentException("Not a token with hash.");
        }
        return tokenWithHash.substring(secondBar + 1);
    }

    /**
     * Checks whether something corresponds to {@link #addHash(String)}.
     */
    @Nonnull
    public static boolean checkHash(@Nonnull String tokenWithHash) {
        if (tokenWithHash == null || !tokenWithHash.startsWith("#")) {
            return false;
        }
        int firstBar = tokenWithHash.indexOf('|');
        int secondBar = tokenWithHash.indexOf('|', firstBar + 1);
        String salt = tokenWithHash.substring(1, firstBar);
        String hash = tokenWithHash.substring(firstBar + 1, secondBar);
        String token = tokenWithHash.substring(secondBar + 1);
        String realHash = Base64.getUrlEncoder().encodeToString(DigestUtils.sha256(salt + token));
        return StringUtils.equals(hash, realHash);
    }

}
