package com.composum.sling.platform.staging;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.composum.sling.platform.staging.ReleaseNumberCreator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/** Tests for {@link ReleaseNumberCreator}. */
public class ReleaseNumberCreatorTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Test
    public void bumpNumbers() {
        String rel = "";
        rel = MAJOR.bumpRelease(rel);
        ec.checkThat(rel, is("r0"));
        rel = MAJOR.bumpRelease(rel);
        ec.checkThat(rel, is("r1"));
        rel = BUGFIX.bumpRelease(rel);
        ec.checkThat(rel, is("r1.0.1"));
        rel = MINOR.bumpRelease(rel);
        ec.checkThat(rel, is("r1.1"));
        rel = BUGFIX.bumpRelease(rel);
        ec.checkThat(rel, is("r1.1.1"));
        rel = MAJOR.bumpRelease(rel);
        ec.checkThat(rel, is("r2"));
        rel = MINOR.bumpRelease(rel);
        ec.checkThat(rel, is("r2.1"));

        ec.checkThat(MINOR.bumpRelease("quaqua1ququ6BU!"), is("r1.7")); // invalid input doesn't hurt.
        ec.checkThat(MINOR.bumpRelease("!@#@"), is("r0.1"));
    }

    @Test
    public void sorting() {
        List<String> rels = new ArrayList<>(Arrays.asList("r10.9", "r1.2.1", "r3.2.2", "r1.2", "", "r3.10.2", "jcr:current", "r", "r3.5"));
        Collections.sort(rels, COMPARATOR_RELEASES);
        ec.checkThat(rels, contains("", "jcr:current", "r", "r1.2", "r1.2.1", "r3.2.2", "r3.5", "r3.10.2", "r10.9"));
        ec.checkThat(-1, is(COMPARATOR_RELEASES.compare("r1", "r1.2")));
        ec.checkThat(1, is(COMPARATOR_RELEASES.compare("r10.9", "r1.2")));
        ec.checkThat(-1, is(COMPARATOR_RELEASES.compare("r1.2", "r10.9")));
    }

}
