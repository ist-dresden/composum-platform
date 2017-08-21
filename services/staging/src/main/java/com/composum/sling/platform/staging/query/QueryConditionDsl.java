package com.composum.sling.platform.staging.query;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.lang3.Validate;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;
import static com.composum.sling.core.util.ResourceUtil.PROP_UUID;
import static java.util.regex.Matcher.quoteReplacement;
import static java.util.regex.Pattern.quote;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENUUID;

/**
 * Domain specific language to create the {@link Query}s conditions in fluent API style. It closely resembles the
 * constraintStart from the JCR-SQL2 language, except that we use casts as needed but we don't expose this construct
 * explicitly. This immediately creates the needed JCR SQL2-representation.
 * <p>
 * <p> The general rule is that a complete SQL2 word gets a space appended.
 *
 * @see "http://www.h2database.com/jcr/grammar.html#condition"
 * @see "https://docs.adobe.com/docs/en/spec/jcr/2.0/6_Query.html"
 */
public class QueryConditionDsl {

    private StringBuilder unversionedQuery = new StringBuilder();
    private StringBuilder versionedQuery = new StringBuilder();

    /** Nesting level of parentheses. >=0 */
    private int parenthesesNestingLevel;

    /** Number of needed closing parentheses (after lower / upper) before the comparison operator can start. */
    private int closeParensBeforeComparison;

    private int nextBindingVarNumber = 1;
    /** Maps val1, val2, ... to the values bound */
    private Map<String, Object> bindingVariables = new LinkedHashMap<>();

    private final ComparisonStart comparisonStart = new ComparisonStart();
    private final QueryCondition queryCondition = new QueryCondition();
    private final QueryConditionBuilder constraintStart = new QueryConditionBuilder();
    private final ComparisonOperator comparisonOperator = new ComparisonOperator();
    private final ConditionStaticValue conditionStaticValue = new ConditionStaticValue();

    /** Prevent instantiation - use */
    protected QueryConditionDsl() {
        // empty
    }

    /** Entry point: start building a queryCondition. */
    public static QueryConditionBuilder builder() {
        return new QueryConditionDsl().constraintStart;
    }

    /**
     * Creates a QueryCondition with the given sql2 as condition. This is only provided for special cases, where using
     * the builder is not possible, such as queries saved in configurations. <p><b>Caution:</b> The query must be a SQL2
     * Constraint that applies only to the selector "n", for example:
     * <pre><code>
     *     CONTAINS(n.[*] , 'foo' ) AND ISCHILDNODE(n,'/somewhere' ) AND LOWER( LOCALNAME(n) ) = 'bar'
     *     OR n.[jcr:created] &gt; CAST('2008-01-01T00:00:00.000Z' AS DATE)
     * </code></pre>
     * (There will be other, Query implementation dependent, selectors in place.)
     * <p>
     * To provide transparent access to both resources in a checked in release as unversioned or current resources,
     * accesses to the properties that are changed in frozen nodes will be replaced when accessing the history:
     * <code>n.[jcr:primaryType]</code> will be replaced by <code>n.[jcr:frozenPrimaryType]</code> and
     * <code>n.[jcr:uuid]</code> will be replaced by <code>n.[jcr:frozenUuid]</code>.
     */
    public static QueryCondition fromString(String sql2) {
        QueryConditionDsl res = new QueryConditionDsl();
        res.unversionedQuery.append(sql2);
        String versionedSql2 = sql2.replaceAll(quote("n.[" + PROP_PRIMARY_TYPE + "]"),
                quoteReplacement("n.[" + JCR_FROZENPRIMARYTYPE + "]"));
        versionedSql2 = versionedSql2.replaceAll(quote("n.[" + PROP_UUID + "]"),
                quoteReplacement("n.[" + JCR_FROZENUUID + "]"));
        res.versionedQuery.append(versionedSql2);
        return res.queryCondition;
    }

    /** Appends some SQL2 fragment */
    private QueryConditionDsl append(String fragment) {
        unversionedQuery.append(fragment);
        versionedQuery.append(fragment);
        return this;
    }

    /** Appends a reference to a property of the selected node. */
    private QueryConditionDsl appendPropertyReference(String propertyName) {
        unversionedQuery.append("n.[").append(propertyName).append("] ");
        String versionedPropertyName = propertyName;
        if (PROP_PRIMARY_TYPE.equals(propertyName)) versionedPropertyName = JCR_FROZENPRIMARYTYPE;
        if (PROP_UUID.equals(propertyName)) versionedPropertyName = JCR_FROZENUUID;
        versionedQuery.append("n.[").append(versionedPropertyName).append("] ");
        return this;
    }

    /** Inserts a binding variable into the SQL and saves the value. */
    private QueryConditionDsl appendValue(Object value) {
        if (null == value) throw new IllegalArgumentException("Argument value is null - please use isNull instead.");
        String bindingVariable = "val" + nextBindingVarNumber;
        nextBindingVarNumber++;
        bindingVariables.put(bindingVariable, value);
        append("$").append(bindingVariable).append(" ");
        return this;
    }

    /** Appends a string value, applying quoting. */
    private QueryConditionDsl appendString(@Nonnull String value) {
        if (null == value) append("'' ");
        else if ("*".equals(value)) append("n.* ");
        else append("'").append(value.replaceAll("'", "''")).append("' ");
        return this;
    }

    /** Returns the SQL2 of the current query, as far as constructed. */
    @Override
    public String toString() {
        return unversionedQuery.toString();
    }

    /** Start of a comparison with a dynamic operand. */
    public class ComparisonStart {
        /** Starts a comparison of a property of the node to something. */
        public ComparisonOperator property(String property) {
            appendPropertyReference(property);
            return comparisonOperator;
        }

        /** Starts a comparison of the length of a property of the node to something. */
        public ComparisonOperator length(String property) {
            append("LENGTH(").appendPropertyReference(property).append(") ");
            return comparisonOperator;
        }

        /**
         * Starts a comparison of the JCR name of the node to something.
         * <p>
         * Limitation: this won't work right on the topmost versioned node (often jcr:content) since that is renamed to
         * jcr:frozenNode in the version storage. Please consider using {@link Query#element} if you make a simple
         * equality comparison, which can take this into account.
         */
        public ComparisonOperator name() {
            append("NAME(n) ");
            return comparisonOperator;
        }

        /**
         * Starts a comparison of the JCR local name (i.e., without the namespace) of a property of the node to
         * something.
         * <p>
         * Limitation: this won't work right on the topmost versioned node (often jcr:content) since that is renamed to
         * jcr:frozenNode in the version storage. Please consider using {@link Query#element} if you make a simple
         * equality comparison, which can take this into account.
         */
        public ComparisonOperator localName() {
            append("LOCALNAME(n) ");
            return comparisonOperator;
        }


        /** Starts a comparison of the score of the full-text search score of a node to something. */
        public ComparisonOperator score() {
            append("SCORE() ");
            return comparisonOperator;
        }

        /** Applies LOWER (lowercase) to the following dynamic operand. */
        public ComparisonStart lower() {
            append("LOWER( ");
            closeParensBeforeComparison++;
            return comparisonStart;
        }

        /** Applies UPPER (uppercase) to the following dynamic operand. */
        public ComparisonStart upper() {
            append("UPPER( ");
            closeParensBeforeComparison++;
            return comparisonStart;
        }
    }

    /** Builder for a {@link QueryCondition} in fluent API style. Now we start a constraint with a queryCondition. */
    public class QueryConditionBuilder extends ComparisonStart {
        /** Starts a group of conditions with an opening parenthesis. */
        public QueryConditionBuilder startGroup() {
            append("( ");
            parenthesesNestingLevel++;
            return constraintStart;
        }

        /** Negates the following constraintStart. */
        public QueryConditionBuilder not() {
            append("NOT ");
            return this;
        }

        /** The selected node is exactly the node with the given path. */
        public QueryCondition isSameNodeAs(@Nonnull String path) {
            append("ISSAMENODE(n,").appendString(path).append(") ");
            return queryCondition;
        }

        /** The selected node is the child of the node with the given path. */
        public QueryCondition isChildOf(@Nonnull String path) {
            append("ISCHILDNODE(n,").appendString(path).append(") ");
            return queryCondition;
        }

        /** The selected node is the child of the node with the given path. */
        public QueryCondition isDescendantOf(@Nonnull String path) {
            append("ISDESCENDANTNODE(n,").appendString(path).append(") ");
            return queryCondition;
        }

        /** QueryCondition that the given property is not null. */
        public QueryCondition isNotNull(String property) {
            appendPropertyReference(property).append("IS NOT NULL ");
            return queryCondition;
        }

        /** QueryCondition that the given property is null. */
        public QueryCondition isNull(String property) {
            appendPropertyReference(property).append("IS NULL ");
            return queryCondition;
        }

        /**
         * The selected node contains a property with the search expression. You might want to {@link
         * Query#orderBy(String)} {@link org.apache.jackrabbit.JcrConstants#JCR_SCORE} {@link Query#descending()}.
         *
         * @param fulltextSearchExpression The fulltext search expression. A term not preceded with “-” (minus sign) is
         *                                 satisfied only if the value contains that term. A term preceded with “-”
         *                                 (minus sign) is satisfied only if the value does not contain that term. Terms
         *                                 separated by whitespace are implicitly “ANDed”. Terms separated by “OR” are
         *                                 “ORed”. “AND” has higher precedence than “OR”. Within a term, each “"”
         *                                 (double quote), “-” (minus sign), and “\” (backslash) must be escaped by a
         *                                 preceding “\”.
         */
        public QueryCondition contains(String fulltextSearchExpression) {
            return contains("*", fulltextSearchExpression);
        }

        /**
         * The selected node contains a property with the search expression. You might want to {@link
         * Query#orderBy(String)} {@link org.apache.jackrabbit.JcrConstants#JCR_SCORE} {@link Query#descending()}.
         *
         * @param fulltextSearchExpression The fulltext search expression. A term not preceded with “-” (minus sign) is
         *                                 satisfied only if the value contains that term. A term preceded with “-”
         *                                 (minus sign) is satisfied only if the value does not contain that term. Terms
         *                                 separated by whitespace are implicitly “ANDed”. Terms separated by “OR” are
         *                                 “ORed”. “AND” has higher precedence than “OR”. Within a term, each “"”
         *                                 (double quote), “-” (minus sign), and “\” (backslash) must be escaped by a
         *                                 preceding “\”.
         */
        public QueryCondition contains(String property, String fulltextSearchExpression) {
            append("CONTAINS(").appendPropertyReference(property).append(", ")
                    .appendValue(fulltextSearchExpression).append(") ");
            return queryCondition;
        }

        /** The selected node's property is one of the given values. (Extension over JCR-SQL2). */
        public QueryCondition in(String property, String... values) {
            return in(property, Arrays.asList(values));
        }

        /** The selected node's property is one of the given values. (Extension over JCR-SQL2). */
        public QueryCondition in(String property, Collection<String> values) {
            if (null == values || values.isEmpty()) return isNull(property);
            QueryConditionBuilder cond = this.startGroup();
            QueryCondition res = null;
            for (String value : values) {
                if (null != res) cond = res.or();
                res = cond.property(property).eq().val(value);
            }
            return res.endGroup();
        }
    }

    /** Within a comparison in a queryCondition - the dynamic operant has been given, now expecting an operator. */
    public class ComparisonOperator {
        /** Close parentheses after previous lower / upper */
        private void closeParentheses() {
            while (closeParensBeforeComparison > 0) {
                append(") ");
                closeParensBeforeComparison--;
            }
        }

        /** = */
        public ConditionStaticValue eq() {
            closeParentheses();
            append("= ");
            return conditionStaticValue;
        }

        /** <> */
        public ConditionStaticValue neq() {
            closeParentheses();
            append("<> ");
            return conditionStaticValue;
        }

        /** < */
        public ConditionStaticValue lt() {
            closeParentheses();
            append("< ");
            return conditionStaticValue;
        }

        /** <= */
        public ConditionStaticValue leq() {
            closeParentheses();
            append("<= ");
            return conditionStaticValue;
        }

        /** > */
        public ConditionStaticValue gt() {
            closeParentheses();
            append("> ");
            return conditionStaticValue;
        }

        /** >= */
        public ConditionStaticValue geq() {
            closeParentheses();
            append(">= ");
            return conditionStaticValue;
        }

        /** LIKE */
        public ConditionStaticValue like() {
            closeParentheses();
            append("LIKE ");
            return conditionStaticValue;
        }
    }

    /** A comparison has a dynamic value and operator - now expecting the static operand. */
    public class ConditionStaticValue {
        /** Compares with the given String. */
        public QueryCondition val(@Nonnull String val) {
            appendValue(val);
            return queryCondition;
        }

        /** Compares with the given Number. */
        public QueryCondition val(@Nonnull Number i) {
            appendValue(i);
            return queryCondition;
        }

        /** Compares with the given Calendar. */
        public QueryCondition val(@Nonnull Calendar d) {
            appendValue(d);
            return queryCondition;
        }

        /** Compares with the path of the given resource. */
        public QueryCondition pathOf(@Nonnull Resource resource) {
            appendValue(resource.getPath());
            return queryCondition;
        }

        /** Compares with the {@link ResourceUtil#PROP_UUID} of the given resource. */
        public QueryCondition uuidOf(@Nonnull Resource resource) {
            String uuid = resource.getValueMap().get(PROP_UUID, String.class);
            Validate.notNull(uuid, "Resource has no " + PROP_UUID + ": %s", resource.getPath());
            appendValue(uuid);
            return queryCondition;
        }

        /** Compares with the given Boolean. */
        public QueryCondition val(@Nonnull Boolean i) {
            appendValue(i);
            return queryCondition;
        }

        /** Compares with the given URI (in {@link URI#toString()} representation). */
        public QueryCondition val(@Nonnull URI uri) {
            appendValue(uri.toString());
            return queryCondition;
        }
    }

    /**
     * The builder contains a full syntactically complete queryCondition (possibly up to closing parentheses) which can
     * be used or extended with AND, OR, or closing parenthesis.
     */
    public class QueryCondition {
        public QueryConditionBuilder and() {
            append("AND ");
            return constraintStart;
        }

        public QueryConditionBuilder or() {
            append("OR ");
            return constraintStart;
        }

        /** Finishes a group of conditions with a closing parenthesis. */
        public QueryCondition endGroup() {
            if (parenthesesNestingLevel <= 0) throw new IllegalStateException("There is no group to close left.");
            append(") ");
            parenthesesNestingLevel--;
            return queryCondition;
        }

        /** Returns the generated SQL2 for use with querying the nodes as they are outside the version storage. */
        protected String getSQL2() {
            while (parenthesesNestingLevel > 0) queryCondition.endGroup();
            return unversionedQuery.toString();
        }

        /** Returns the generated SQL2 for use with querying the nodes as they are inside the version storage. */
        protected String getVersionedSQL2() {
            while (parenthesesNestingLevel > 0) queryCondition.endGroup();
            return unversionedQuery.toString();
        }

        /** Returns the values of the binding variables contained in the SQL queries. */
        protected Map<String, Object> getBindingValues() {
            return Collections.unmodifiableMap(bindingVariables);
        }

        /** Sets the saved binding values on a jcrQuery. */
        protected void applyBindingValues(javax.jcr.query.Query jcrQuery, ResourceResolver resolver)
                throws RepositoryException {
            ValueFactory valueFactory = resolver.adaptTo(Session.class).getValueFactory();
            for (Map.Entry<String, Object> entry : bindingVariables.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (null == value) jcrQuery.bindValue(key, valueFactory.createValue((String) null));
                else if (value instanceof String) jcrQuery.bindValue(key, valueFactory.createValue((String) value));
                else if (value instanceof Calendar) jcrQuery.bindValue(key, valueFactory.createValue((Calendar) value));
                else if (value instanceof Boolean) jcrQuery.bindValue(key, valueFactory.createValue((Boolean) value));
                else if (value instanceof BigDecimal)
                    jcrQuery.bindValue(key, valueFactory.createValue((BigDecimal) value));
                else if (value instanceof Character || value instanceof Byte || value instanceof Short ||
                        value instanceof Integer || value instanceof Long)
                    // integral datatypes; conversion to long looses nothing
                    jcrQuery.bindValue(key, valueFactory.createValue(((Number) value).longValue()));
                else if (value instanceof Number)
                    // for weird Number subtypes (e.g. BigInteger) this could involve rounding, but usually not.
                    jcrQuery.bindValue(key, valueFactory.createValue(((Number) value).doubleValue()));
                else // Bug.
                    throw new IllegalArgumentException("Unsupported value " + value + " of class " + value.getClass());
            }
        }

        @Override
        public String toString() {
            return QueryConditionDsl.this.toString();
        }
    }
}
