package com.composum.sling.platform.staging.query;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.apache.sling.testing.resourceresolver.MockResource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.jcr.Session;
import javax.jcr.query.QueryManager;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import static com.composum.sling.platform.staging.query.QueryConditionDsl.builder;
import static javax.jcr.query.Query.JCR_SQL2;
import static org.apache.jackrabbit.JcrConstants.JCR_PATH;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.junit.Assert.*;

/**
 * Tests for {@link QueryConditionDsl}.
 * TODO: check variable ranges; check whether = '' is the same as is null.
 * check null binding values - are they bound?
 */
@RunWith(Parameterized.class)
public class QueryConditionDsl2Test {

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data() throws URISyntaxException {
        Resource resource = new MockResource("/something",
                Collections.<String, Object>singletonMap(JCR_UUID, "uuid"), null);
        return Arrays.asList(new Object[][]{
                {builder().property("bla").eq().val("hallo").and()
                        .startGroup().lower().property("bla").eq().val(17).endGroup(),
                        "n.[bla] = $val1 AND ( LOWER( n.[bla] ) = $val2 ) "},
                {builder().lower().localName().eq().val("lcl"), "LOWER( LOCALNAME(n) ) = $val1 "},
                {builder().contains("test").and().score().gt().val(3),
                        "CONTAINS(n.[*] , $val1 ) AND SCORE() > $val2 "},
                {builder().contains("prop", "test").or().upper().name().neq().val("node"),
                        "CONTAINS(n.[prop] , $val1 ) OR UPPER( NAME(n) ) <> $val2 "},
                {builder().not().isNotNull("ha").or().not().startGroup().isChildOf("/somewhere"),
                        "NOT n.[ha] IS NOT NULL OR NOT ( ISCHILDNODE(n,'/somewhere' ) ) "},
                {builder().isDescendantOf("/what").and().startGroup().isSameNodeAs("/where"),
                        "ISDESCENDANTNODE(n,'/what' ) AND ( ISSAMENODE(n,'/where' ) ) "},
                {builder().isNull("that").and().startGroup().length("what").geq().val(17),
                        "n.[that] IS NULL AND ( LENGTH(n.[what] ) >= $val1 ) "},
                {builder().property("that").gt().val(Double.MAX_VALUE).and().property("what").geq().val(Long.MAX_VALUE)
                        .and().property(JCR_PATH).like().val("/bla/%").and().property("p4").lt().val(Long.MIN_VALUE)
                        .and().property("p5").leq().val(Double.MIN_NORMAL)
                        .and().property("p6").eq().val(Calendar.getInstance())
                        .and().property("p7").neq().val(new URI("http://www.example.net/"))
                        , "n.[that] > $val1 AND n.[what] >= $val2 AND n.[jcr:path] LIKE $val3 AND n.[p4] < $val4 AND " +
                        "n" +
                        ".[p5] <= $val5 AND n.[p6] = $val6 AND n.[p7] <> $val7 "},
                {builder().property("g1").eq().val(true).and().property("g2").neq().pathOf(resource)
                        .and().property("g3").gt().val(new BigDecimal(17))
                        .and().property("g4").eq().uuidOf(resource),
                        "n.[g1] = $val1 AND n.[g2] <> $val2 AND n.[g3] > $val3 AND n.[g4] = $val4 "},
                {QueryConditionDsl.fromString("CONTAINS(n.[*] , 'foo' ) AND ISCHILDNODE(n,'/somewhere' ) " +
                        "AND LOWER ( LOCALNAME(n) ) = 'bar' " +
                        "OR n.[jcr:created] > CAST('2008-01-01T00:00:00.000Z' AS DATE) "),
                        "CONTAINS(n.[*] , 'foo' ) AND ISCHILDNODE(n,'/somewhere' ) " +
                                "AND LOWER ( LOCALNAME(n) ) = 'bar' " +
                                "OR n.[jcr:created] > CAST('2008-01-01T00:00:00.000Z' AS DATE) "},
                {builder().in("prop", "hu", "ha", "ho"),
                        "( n.[prop] = $val1 OR n.[prop] = $val2 OR n.[prop] = $val3 ) "}
        });
    }

    @Parameterized.Parameter(0)
    public QueryConditionDsl.QueryCondition queryCondition;

    @Parameterized.Parameter(1)
    public String expected;

    protected static SlingContext context;
    protected static QueryManager queryManager;

    /** Ensure the values fit and can be compiled. */
    @Test
    public void checkTranslationAndCompileability() throws Exception {
        // check that there are no compile errors from these. We don't expect any results.
        String testQuery = "SELECT n.* FROM [nt:file] AS n WHERE " + queryCondition.getSQL2();
        javax.jcr.query.Query query = queryManager.createQuery(testQuery, JCR_SQL2);
        queryCondition.applyBindingValues(query, context.resourceResolver());
        query.execute();

        testQuery = "SELECT n.* FROM [nt:file] AS n WHERE " + queryCondition.getVersionedSQL2();
        query = queryManager.createQuery(testQuery, JCR_SQL2);
        queryCondition.applyBindingValues(query, context.resourceResolver());
        query.execute();

        assertEquals(expected, queryCondition.getSQL2());
    }

    @BeforeClass
    public static void initContext() throws Exception {
        context = new SlingContext(ResourceResolverType.JCR_OAK);
        Method m = SlingContextImpl.class.getDeclaredMethod("setUp");
        m.setAccessible(true);
        m.invoke(context);
        queryManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getQueryManager();
    }

    @AfterClass
    public static void destroyContext() throws Exception {
        Method m = SlingContextImpl.class.getDeclaredMethod("tearDown");
        m.setAccessible(true);
        m.invoke(context);
    }

}
