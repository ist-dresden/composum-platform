package com.composum.sling.platform.staging.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Tests for {@link SearchtermParser}. */
public class SearchtermParserTest {

    @Test
    public void testParsing() throws SearchTermParseException {
        String terms = "  hi -bye \"good afternoon\" -\"good bye\"";
        SearchtermParser p = new SearchtermParser(terms);
        assertEquals("[hi, good afternoon]", p.positiveSearchterms.toString());
        assertEquals("[bye, good bye]", p.negativeSearchterms.toString());
    }

    @Test
    public void parsingFailures() {
        for (String failing : new String[]{"\"hi", "-\"hi", "\"hi", "\"hi\"ha\"", "\"hi\"ha\"ho"}) {
            try {
                new SearchtermParser(failing);
                fail("Parsing should fail: " + failing);
            } catch (SearchTermParseException e) {
                // expected
            }
        }
    }
}
