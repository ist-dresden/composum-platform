package com.composum.sling.platform.staging.replication;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

public class ReplicationPathsTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    String src = "/the/src";
    String dst = "/our/dst";
    ReplicationPaths replicationPaths = new ReplicationPaths(src, src, dst, null);

    @Test
    public void checkTranslate() {
        // ec.checkThat(replicationPaths.translate((String) null), nullValue());
        ec.checkThat(replicationPaths.translate(src), is(dst));
        // ec.checkThat(replicationPaths.translate("/whatever"), is("/whatever"));
        ec.checkThat(replicationPaths.translate("/the/src/a/b"), is("/our/dst/a/b"));
        ec.checkThat(replicationPaths.translate("/the/src/a/../b"), is("/our/dst/b"));

        ec.checkThat(replicationPaths.translateMapping("/tmp").apply("/the/src/a/b"), is("/tmp/our/dst/a/b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkTranslateException() {
        ec.checkThat(replicationPaths.translate("/whatever"), is("/whatever"));
    }

    @Test
    public void checkInverseTranslate() {
        ec.checkThat(replicationPaths.inverseTranslate(dst), is(src));
        ec.checkThat(replicationPaths.inverseTranslate("/our/dst/a/b"), is("/the/src/a/b"));
        ec.checkThat(replicationPaths.inverseTranslate("/our/dst/a/../b"), is("/the/src/b"));

        ec.checkThat(replicationPaths.inverseTranslateMapping("/tmp").apply("/tmp/our/dst/a/b"), is("/the/src/a/b"));
    }

}
