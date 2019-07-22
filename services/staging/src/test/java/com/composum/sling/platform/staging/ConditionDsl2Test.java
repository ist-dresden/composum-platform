package com.composum.sling.platform.staging;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for another variant of a Condition DSL for Query.
 */
public class ConditionDsl2Test {

    @Test
    public void makeACondition2() {
        assertEquals("n.[foo] = 'hallo' AND n.[whatever] <> 17", new HpsCondition2().property("foo").eq().value("hallo").and().property
                ("whatever").ne().value(17)
                .getSQL2());
    }

    /**
     * Exploration for a better condition DSL.
     */
    public static class HpsCondition2 {

        protected StringBuilder buf = new StringBuilder();

        public CondOperator property(String property) {
            buf.append("n.[").append(property).append("]");
            return new CondOperator();
        }

        public class CondOperator {
            public CondStaticValue eq() {
                buf.append(" = ");
                return new CondStaticValue();
            }

            public CondStaticValue ne() {
                buf.append(" <> ");
                return new CondStaticValue();
            }
        }

        public class CondStaticValue {
            public CondFinal value(String val) {
                buf.append("'").append(val.replaceAll("'", "''")).append("'");
                return new CondFinal();
            }

            public CondFinal value(int i) {
                buf.append(i);
                return new CondFinal();
            }
        }

        public class CondFinal {
            public HpsCondition2 and() {
                buf.append(" AND ");
                return HpsCondition2.this;
            }

            public HpsCondition2 or() {
                buf.append(" OR ");
                return HpsCondition2.this;
            }

            public String getSQL2() {
                return buf.toString();
            }
        }
    }
}
