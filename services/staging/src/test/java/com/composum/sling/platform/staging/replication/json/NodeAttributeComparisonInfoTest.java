package com.composum.sling.platform.staging.replication.json;

import com.composum.sling.platform.staging.replication.json.NodeAttributeComparisonInfo;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.jcr.Session;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.composum.sling.core.util.CoreConstants.MIX_CREATED;
import static com.composum.sling.core.util.CoreConstants.PROP_MIXINTYPES;
import static com.composum.sling.core.util.CoreConstants.PROP_PRIMARY_TYPE;
import static com.composum.sling.core.util.CoreConstants.TYPE_UNSTRUCTURED;
import static org.apache.jackrabbit.JcrConstants.MIX_VERSIONABLE;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class NodeAttributeComparisonInfoTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    public final void makeAttributeInfo() throws Exception {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(1579778000000L);
        Resource node = context.build().resource("/some/node", makeProperties(calendar)).commit().getCurrentParent();

        context.resourceResolver().adaptTo(Session.class).getWorkspace().getNodeTypeManager();

        NodeAttributeComparisonInfo attcinfo = NodeAttributeComparisonInfo.of(node, null);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(attcinfo));
        ec.checkThat(gson.toJson(attcinfo), is("{\n" +
                "  \"path\": \"/some/node\",\n" +
                "  \"propertyHashes\": {\n" +
                "    \"bigdec\": \"D:12.3\",\n" +
                "    \"date\": \"C:1579778000000\",\n" +
                "    \"double\": \"n:3.141592653589793\",\n" +
                "    \"jcr:mixinTypes\": \"S:40ee9673a56cc0bf\",\n" +
                "    \"jcr:primaryType\": \"S:nt:unstructured\",\n" +
                "    \"long\": \"n:1579778715114\",\n" +
                "    \"longstr\": \"S:d71a0ee77f01428d\",\n" +
                "    \"num\": \"n:15\",\n" +
                "    \"str\": \"S:hello!\",\n" +
                "    \"stream\": \"B:6c58da8f83345ab1\"\n" +
                "  }\n" +
                "}"));

        NodeAttributeComparisonInfo deserialized = gson.fromJson(gson.toJson(attcinfo), NodeAttributeComparisonInfo.class);
        ec.checkThat(deserialized, is(attcinfo));
        ec.checkThat(gson.toJson(deserialized), is(gson.toJson(attcinfo)));

        Resource node2 = context.build().resource("/prefix/some/node", makeProperties(calendar)).commit().getCurrentParent();
        NodeAttributeComparisonInfo attcinfo2 = NodeAttributeComparisonInfo.of(node2, "/prefix");
        ec.checkThat(attcinfo2, is(attcinfo));

        ec.checkThat(NodeAttributeComparisonInfo.of(node2, null), not(attcinfo));

        ec.checkThat(NodeAttributeComparisonInfo.of(node2, "/prefix"), is(attcinfo2));
        node2.adaptTo(ModifiableValueMap.class).put("stream", new ByteArrayInputStream("othercontent".getBytes(StandardCharsets.UTF_8)));
        ec.checkThat(NodeAttributeComparisonInfo.of(node2, "/prefix"), not(attcinfo2));

    }

    @Nonnull
    protected Map<String, Object> makeProperties(Calendar calendar) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        res.put(PROP_MIXINTYPES, new String[]{MIX_CREATED, MIX_VERSIONABLE});
        res.put("num", 15);
        res.put("str", "hello!");
        res.put("longstr", "This is a very very very long string that should be encoded as hash to make it shorter");
        res.put("date", calendar);
        res.put("long", 1579778715114L);
        res.put("double", Math.PI);
        res.put("bigdec", BigDecimal.valueOf(123, 1));
        res.put("stream", new ByteArrayInputStream("somecontent".getBytes(StandardCharsets.UTF_8)));
        return res;
    }

}
