package com.composum.platform.commons.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tests for {@link JSonOnTheFlyCollectionAdapter}. */
public class JSonOnTheFlyCollectionAdapterTest {

    @Test
    public void testSerialization() {
        Gson gson = new GsonBuilder().create();
        TestSerialized obj = new TestSerialized();
        obj.attribute = Arrays.asList(1, 2, 3);
        String json = gson.toJson(obj);
        assertEquals("{\"attribute\":[1,2,3]}", json);

        obj = new TestSerialized();
        obj.attribute = Collections.emptyList();
        json = gson.toJson(obj);
        assertEquals("{\"attribute\":[]}", json);

        obj = new TestSerialized();
        json = gson.toJson(obj);
        assertEquals("{}", json);
    }

    @Test
    public void testDeserialization() {
        Gson gson = new GsonBuilder().create();
        TestDeserialized deserialized = gson.fromJson("{\"attribute\":[1,2,3]}", TestDeserialized.class);
        assertNotNull(deserialized);
        assertNotNull(deserialized.attribute);
        assertTrue(deserialized.attribute.closed);
        assertEquals(3, deserialized.attribute.received.size());
        assertEquals("[1, 2, 3]", deserialized.attribute.received.toString());

        deserialized = gson.fromJson("{\"attribute\":[]}", TestDeserialized.class);
        assertNotNull(deserialized);
        assertNotNull(deserialized.attribute);
        assertTrue(deserialized.attribute.closed);
        assertEquals(0, deserialized.attribute.received.size());

        deserialized = gson.fromJson("{}", TestDeserialized.class);
        assertNotNull(deserialized);
        assertNull(deserialized.attribute);
    }

    @Test
    public void testCollectionSerializationDeserialization() {
        Gson gson = new GsonBuilder().create();

        TestForCollection col = new TestForCollection();
        col.maplist = new ConcurrentLinkedQueue<>();
        col.maplist.add(Collections.singletonMap("key", 17));

        String json = gson.toJson(col);
        assertEquals("{\"maplist\":[{\"key\":17}]}", json);

        TestForCollection re = gson.fromJson(json, TestForCollection.class);
        assertNotNull(re);
        assertNotNull(re.maplist);
        assertEquals(1, re.maplist.size());
        assertEquals("{key=17}", re.maplist.peek().toString());

    }

    static class TestSerialized {

        @JsonAdapter(JSonOnTheFlyCollectionAdapter.class)
        Iterable<Integer> attribute;

    }

    static class TestDeserialized {
        @JsonAdapter(JSonOnTheFlyCollectionAdapter.class)
        TestDeserializationConsumer attribute;
    }

    static class TestDeserializationConsumer implements Consumer<Integer>, Closeable {

        boolean closed;
        List<Integer> received = new ArrayList<>();

        @Override
        public void accept(Integer integer) {
            received.add(integer);
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    static class TestForCollection {
        @JsonAdapter(JSonOnTheFlyCollectionAdapter.class)
        ConcurrentLinkedQueue<Map<String, Integer>> maplist;
    }

}
