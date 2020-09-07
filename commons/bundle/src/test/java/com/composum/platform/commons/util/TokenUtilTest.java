package com.composum.platform.commons.util;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import static com.composum.platform.commons.util.TokenUtil.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.fail;

/**
 * Tests for {@link TokenUtil}.
 */
public class TokenUtilTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Test
    public void testJoin() {
        ec.checkThat(join(), is("0|")); // empty array
        ec.checkThat(join((Object) null), is("1|-"));
        ec.checkThat(join("hello"), is("1|5:hello"));
        ec.checkThat(join("hello", "world"), is("2|5:hello|5:world"));
        ec.checkThat(join(5, null, 28), is("3|1:5|-|2:28"));
    }

    @Test
    public void testExtract() {
        ec.checkThat(extract("0|"), Matchers.emptyCollectionOf(String.class));
        ec.checkThat(extract("1|-"), Matchers.contains((String) null));
        ec.checkThat(extract("1|5:hello"), Matchers.contains("hello"));
        ec.checkThat(extract("2|5:hello|5:world"), Matchers.contains("hello", "world"));
        ec.checkThat(extract("3|1:5|-|2:28"), Matchers.contains("5", null, "28"));
    }

    @Test
    public void testExtractErrors() {
        for (String errorneous : new String[]{null, "x", "5|", "2|-", "1|a5", "2|4:hello|5:world", "8|5:hello|5:world"}) {
            try {
                extract(errorneous);
                fail("Exception expected for " + errorneous);
            } catch (IllegalArgumentException e) {
                // OK.
            }
        }
    }

    @Test
    public void testAddHash() {
        System.out.println(addHash("2|5:hello|5:world"));
        ec.checkThat(checkHash(addHash("2|5:hello|5:world")), is(true));
        ec.checkThat(checkHash(addHash("2|5:hello|5:world").replace("hello", "hallo")), is(false));
        ec.checkThat(addHash("bla").replaceAll("bla", "blu"), not(is(addHash("blu"))));
        ec.checkThat(StringUtils.getLevenshteinDistance(addHash("bla"), addHash("blu")), Matchers.greaterThan(20));

        ec.checkThat(removeHash(addHash("1|2|3")), is("1|2|3"));

        ec.checkThat(addHash("bla"), not(is(addHash("bla")))); // variable seed.
    }

}
