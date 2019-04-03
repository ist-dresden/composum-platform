package com.composum.sling.platform.staging.testutil;

import org.apache.commons.collections4.ComparatorUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.hamcrest.Matchers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;

public class AssertionCodeGenerator {

    protected final String variableName;

    protected final Object object;

    protected final StringBuilder allAssertionsBuf = new StringBuilder();

    protected final StringBuilder assertionBuf = new StringBuilder();

    protected boolean useErrorCollector = false;

    protected String message;

    protected Set<String> ignoredPropertySet = new HashSet<>();

    public AssertionCodeGenerator(@Nonnull String variableName, @Nullable Object object) {
        this.variableName = variableName;
        this.object = object;
    }

    /** Prints the neccesary imports. */
    public AssertionCodeGenerator printImports() {
        allAssertionsBuf.append("import static org.hamcrest.Matchers.*;\n");
        allAssertionsBuf.append("import static " + SlingMatchers.class.getName() + ".*;\n");
        if (useErrorCollector)
            allAssertionsBuf.append("import " + ErrorCollectorAlwaysPrintingFailures.class.getName() + ";\n\n" +
                    "    @Rule\n" +
                    "    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures();\n\n");
        allAssertionsBuf.append("    // ASSERTIONS FOR OBJECT ").append(variableName)
                .append(" OF CLASS ").append(object.getClass()).append(" : \n");
        return this;
    }

    /** Uses the ErrorCollectorAlwaysPrintingFailures rule to catch all errors at the same time. */
    @Nonnull
    public AssertionCodeGenerator useErrorCollector() {
        useErrorCollector = true;
        return this;
    }

    /** Specifies a message to log. */
    @Nonnull
    public AssertionCodeGenerator withMessage(String message) {
        this.message = message;
        return this;
    }

    /** Spezifies some properties to ignore. */
    @Nonnull
    public AssertionCodeGenerator ignoreProperties(String... ignoredProperties) {
        ignoredPropertySet.addAll(Arrays.asList(ignoredProperties));
        return this;
    }

    /**
     * Generates code for assertions according to all public properties of the passed in object.
     * This saves some of the tedious effort do generate assertions checking the state of an object.
     * You can employ this in your unitest temporarily instead of the assertions,
     * manually check whether it is as expected, and copy the printed code into the test once it was run.
     */
    public void printAssertions() {
        allAssertionsBuf.append("\n");
        Set<String> checkedMethods = new HashSet<>();
        try {
            if (object == null) {
                appendAssertionStart("");
                assertionBuf.append("nullValue()");
                appendAssertionEnd();
            } else {
                Method[] methods = object.getClass().getMethods();
                Arrays.sort(methods, Comparator.comparing(
                        (m) -> m.getName().toLowerCase().replaceAll("^(get|has|list|is)", "") + m.getName()));
                for (Method m : methods) {
                    if (m.getDeclaringClass().equals(Object.class)) continue;
                    if (m.getParameters().length == 0) {
                        if (checkedMethods.contains(m.getName()) || ignoredPropertySet.contains(m.getName())) continue;
                        checkedMethods.add(m.getName());
                        Object value;
                        try {
                            value = MethodUtils.invokeMethod(object, m.getName());
                        } catch (Exception e) {
                            e.printStackTrace();
                            allAssertionsBuf.append("        // error generating statement for ").append(m.getName())
                                    .append(" : ").append(e.getMessage()).append("\n");
                            continue;
                        }
                        try {
                            appendAssertionStart("." + m.getName() + "()");
                            createMatcher(value);
                            appendAssertionEnd();
                        } catch (SkipPropertyException e) {
                            allAssertionsBuf.append("        // Skipping ")
                                    .append(m.getName()).append(" of type ").append(value).append("\n");
                        } catch (Exception e) {
                            allAssertionsBuf.append("        // COULD NOT GENERATE ASSERTION FOR ")
                                    .append(m.getName()).append(" : ").append(e.getMessage()).append("\n");
                        }
                    }
                }
            }
        } finally {
            System.out.println(allAssertionsBuf);
        }
    }

    protected void appendQuotedString(String string) {
        if (string == null) assertionBuf.append("null");
        else assertionBuf.append('"').append(string.replaceAll("\"", "\\\"")).append('"');
        // yes, this is yet missing support for special chars
    }

    /** Appends a matcher to buf that matches if a value is just like {value}. */
    protected void createMatcher(Object value) {
        if (value == null) {
            assertionBuf.append("nullValue()");
        } else {
            int prelen = assertionBuf.length();
            createMatcherHook(value);
            if (assertionBuf.length() != prelen) {
                // OK, was already handled by subclasses
            } else if (value instanceof String) {
                // e251c418-4483-4dfb-84cd-05410cfcf0f8
                if (((String) value).matches("[0-9a-f-]{36}")) { // assume it's a varible uuid
                    assertionBuf.append("stringMatchingPattern(\"[0-9a-f-]{36}\")");
                } else {
                    assertionBuf.append("is(");
                    appendQuotedString((String) value);
                    assertionBuf.append(")");
                }
            } else if (value instanceof Integer || value instanceof Boolean || value instanceof Double) {
                assertionBuf.append("is(").append(value).append(")");
            } else if (value instanceof Long) {
                assertionBuf.append("is(").append(value).append("L)");
            } else if (value instanceof Calendar) {
                // Calendar calendar = (Calendar) value;
                // SlingMatchers.mappedMatches(Calendar::getTimeInMillis, is(calendar.getTimeInMillis()));
                // assertionBuf.append("SlingMatchers.mappedMatches(Calendar::getTimeInMillis, ");
                // createMatcher(calendar.getTimeInMillis());
                // assertionBuf.append(")");
                assertionBuf.append("notNullValue(java.util.Calendar.class)");
            } else if (value instanceof List) {
                createMatcherForList((List) value);
            } else if (value instanceof Map) {
                createMatcherForMap((Map) value);
            } else if (value instanceof Iterable) {
                createMatcherForIterable(IteratorUtils.toList(((Iterable) value).iterator()));
            } else if (value instanceof Iterator) {
                assertionBuf.append("iteratorWithSize(")
                        .append(IteratorUtils.toList((Iterator) value).size()).append(")");
            } else if (TypeUtils.isArrayType(value.getClass())) {
                List<Object> contents = Arrays.asList((Object[]) value);
                assertionBuf.append("arrayContaining(");
                boolean first = true;
                for (Object entry : contents) {
                    if (!first) assertionBuf.append(",");
                    createMatcher(entry);
                    first = false;
                }
                assertionBuf.append(")");
            } else {
                throw new UnsupportedOperationException("Type not yet supported, please extend: " + value.getClass());
            }
        }
    }

    /** Creates a matcher for a generic Iterable. Difficult. We just compare lengths for a start. */
    protected void createMatcherForIterable(List contents) {
        assertionBuf.append("iterableWithSize(").append(contents.size()).append(")");
    }

    /** Creates a matcher for a Map. Difficult. We just compare toStrings for a start. */
    protected void createMatcherForMap(@Nonnull Map<?, ?> map) {
        /* assertionBuf.append("mappedMatches(SlingMatchers::sortedToString, is(");
        appendQuotedString(sortedToString(map));
        assertionBuf.append(")"); */
        if (map.size() == 0) {
            assertionBuf.append("hasMapSize(0)");
        } else {
            assertionBuf.append("allOf(\n")
                    .append("            hasMapSize(").append(map.size()).append(")");
            for (Map.Entry entry : map.entrySet()) {
                assertionBuf.append(",\n            SlingMatchers.hasEntryMatching(");
                createMatcher(entry.getKey());
                assertionBuf.append(", ");
                createMatcher(entry.getValue());
                assertionBuf.append(")");
            }
            assertionBuf.append("\n        )");
        }
    }

    /** Creates a matcher for a Map. Difficult. We just compare toStrings for a start. */
    protected void createMatcherForList(@Nonnull Collection<?> collection) {
        assertionBuf.append("mappedMatches(Object::toString, is(");
        appendQuotedString(collection.toString());
        assertionBuf.append(")");
    }

    /**
     * Room for extensions: should append a matcher to buf that matches if a value is just like {value}.
     * If nothing was appended to {@link AssertionCodeGenerator#assertionBuf}, we assume it was not handled.
     */
    protected void createMatcherHook(@Nonnull Object value) {
        // do nothing.
    }

    protected void appendAssertionStart(String invocation) {
        assertionBuf.setLength(0);
        if (useErrorCollector) {
            assertionBuf.append("        errorCollector.checkThat(");
            if (message != null) {
                assertionBuf.append(message);
                assertionBuf.append(", ");
            }
            assertionBuf.append(variableName).append(invocation).append(", ");
        } else {
            assertionBuf.append("        assertThat(");
            if (message != null) {
                assertionBuf.append(message);
                assertionBuf.append(", ");
            }
            assertionBuf.append(variableName).append(invocation).append(", ");
        }
    }

    protected void appendAssertionEnd() {
        assertionBuf.append(");\n");
        allAssertionsBuf.append(assertionBuf);
        assertionBuf.setLength(0);
    }

    /** Marker that the current property should be skipped - perhaps since it's an unknown type */
    protected static class SkipPropertyException extends RuntimeException {
        //empty
    }

}
