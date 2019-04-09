package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy.Result;
import com.composum.sling.platform.staging.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

import static com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy.Result.*;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link SiblingOrderUpdateStrategy}. We just test the ordering results here - the JCR methods are tested through
 * {@link com.composum.sling.platform.staging.service.DefaultStagingReleaseManager}.
 */
public class SiblingOrderUpdateStrategyTest {

    @Rule
    public ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Test
    public void simpleCases() {
        src("a").node("a").dest("a").ordersTo(unchanged, "a");
        src("a").node("a").dest("a", "b").ordersTo(unchanged, "a", "b");
        src("b").node("b").dest("a", "b").ordersTo(unchanged, "a", "b");
        src("a", "b", "c", "d").node("b").dest("b").ordersTo(unchanged, "b");
    }

    @Test
    public void noNeedToChange() {
        src("a", "b", "c").node("a").dest("a", "b", "c").ordersTo(unchanged, "a", "b", "c");
        src("a", "b", "c").node("a").dest("a", "b", "c", "i").ordersTo(unchanged, "a", "b", "c", "i");
        src("a", "b", "c").node("b").dest("a", "b", "c").ordersTo(unchanged, "a", "b", "c");
        src("a", "b", "c").node("c").dest("a", "b", "c").ordersTo(unchanged, "a", "b", "c");

        src("a", "b", "c").node("b").dest("a", "b").ordersTo(unchanged, "a", "b");
        src("a", "b", "c").node("a").dest("a", "b").ordersTo(unchanged, "a", "b");
    }

    @Test
    public void ambiguousButNoNeedToChange() {
        src("a", "b", "c").dest("a", "b", "i", "c").node("b").ordersTo(unchanged, "a", "b", "i", "c");
        src("a", "b", "c").dest("a", "i", "b", "j", "c").node("b").ordersTo(unchanged, "a", "i", "b", "j", "c");
        src("a", "b", "c").dest("a", "i", "b", "c").node("b").ordersTo(unchanged, "a", "i", "b", "c");
    }

    @Test
    public void unAmbiguousReorder() {
        src("a", "b", "c").dest("b", "a", "c").node("b").ordersTo(deterministicallyReordered, "a", "b", "c");
        src("a", "b", "c").dest("a", "c", "b").node("b").ordersTo(deterministicallyReordered, "a", "b", "c");

        src("a", "b", "c", "d", "e").node("c").dest("e", "b", "c", "d", "a").ordersTo(deterministicallyReordered, "a", "b", "c", "d", "e");
    }

    @Test
    public void ambiguousReorder() {
        src("a", "b", "c").dest("b", "a", "i", "j", "c").node("b").ordersTo(heuristicallyReordered, "a", "b", "i", "j", "c");
        src("a", "b", "c").dest("a", "i", "j", "c", "b").node("b").ordersTo(heuristicallyReordered, "a", "b", "i", "j", "c");
    }

    @Test
    public void simpleContradiction() { // all nodes present on both sides
        src("a", "b", "c").dest("c", "b", "a").node("b").ordersTo(deterministicallyReordered, "a", "b", "c");
        src("a", "b", "c").dest("b", "c", "a").node("b").ordersTo(deterministicallyReordered, "a", "b", "c");
    }

    @Test
    public void deterministicContradiction() {
        src("a", "b", "c").node("b").dest("i", "c", "b", "a").ordersTo(deterministicallyReordered, "i", "a", "b", "c");
        src("a", "b", "c").node("b").dest("c", "b", "a", "i", "j").ordersTo(deterministicallyReordered, "a", "b", "c", "i", "j");
        src("a", "b", "c").node("b").dest("i", "j", "c", "a", "b", "k").ordersTo(deterministicallyReordered, "i", "j", "a", "b", "c", "k");

        src("a", "b", "c").node("b").dest("c", "b", "a", "i").ordersTo(deterministicallyReordered, "a", "b", "c", "i");
    }

    @Test
    public void indeterministicContradiction() { // additional nodes in dest could have several places
        src("a", "b", "c").node("b").dest("c", "i", "b", "a").ordersTo(heuristicallyReordered, "i", "a", "b", "c");
        src("a", "b", "c").node("b").dest("c", "b", "i", "a").ordersTo(heuristicallyReordered, "a", "b", "c", "i");

        // majority of relationships decides about ordering of the additional nodes
        src("a", "b", "c", "d", "e", "f", "g").node("d")
                .dest("i", "g", "b", "c", "j", "d", "k", "l", "e", "f", "a", "z")
                .ordersTo(heuristicallyReordered, "i", "a", "b", "c", "j", "d", "k", "l", "e", "f", "g", "z");
    }


    @Nonnull
    private CheckBuilder src(String... srcNodes) {
        return new CheckBuilder(Arrays.asList(srcNodes));
    }

    private class CheckBuilder {
        private final List<String> src;
        private List<String> dest;
        private String node;

        public CheckBuilder(List<String> src) {
            this.src = src;
        }

        @Nonnull
        public CheckBuilder dest(String... destNodes) {
            this.dest = Arrays.asList(destNodes);
            return this;
        }

        @Nonnull
        public CheckBuilder node(String node) {
            this.node = node;
            return this;
        }

        /** Checks that the result of applying the orderer confirms to the given result and ordering. */
        public void ordersTo(Result result, String... resultNodes) {
            SiblingOrderUpdateStrategy.Orderer orderer = new SiblingOrderUpdateStrategy.Orderer(src, dest, node)
                    .run();
            ec.checkThat(orderer.result, equalTo(result));
            ec.checkThat(orderer.ordering, equalTo(Arrays.asList(resultNodes)));
        }
    }
}
