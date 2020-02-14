package com.composum.platform.commons.logging;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.MockResourceBundle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import org.apache.sling.api.SlingHttpServletRequest;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static org.hamcrest.Matchers.is;

/** Tests for {@link MessageContainer} and {@link Message}. */
public class TestMessageContainer {

    public static final String TIMESTAMP_REGEX = "157[0-9]{10}";

    @Rule
    public ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    private static final Logger LOG = LoggerFactory.getLogger(TestMessageContainer.class);

    @Test
    public void jsonizeAndBack() {
        MessageContainer container = new MessageContainer(LOG);
        container.add(new Message(Message.Level.warn, "Some problem with {} number {}", "foo", 17))
                .add(new Message(null, "Minimal message"))
                .add(new Message(Message.Level.info, "Message {} with details", 3)
                        .addDetail(new Message(Message.Level.debug, "Detail {}", 1))
                        .addDetail(new Message(Message.Level.info, "Detail {}", 2))
                );
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(container);
        MessageContainer readback = gson.fromJson(json, MessageContainer.class);
        ec.checkThat(readback.getMessages().size(), is(3));
        String json2 = gson.toJson(readback);
        ec.checkThat(json2.replaceAll("\\.0", ""), is(json)); // 3 becomes 3.0 - difficult to change and we ignore that

        compare(json, "[\n" +
                "  {\n" +
                "    \"level\": \"warn\",\n" +
                "    \"message\": \"Some problem with foo number 17\",\n" +
                "    \"rawMessage\": \"Some problem with {} number {}\",\n" +
                "    \"arguments\": [\n" +
                "      \"foo\",\n" +
                "      17\n" +
                "    ],\n" +
                "    \"timestamp\": <timestamp>\n" +
                "  },\n" +
                "  {\n" +
                "    \"message\": \"Minimal message\",\n" +
                "    \"rawMessage\": \"Minimal message\",\n" +
                "    \"timestamp\": <timestamp>\n" +
                "  },\n" +
                "  {\n" +
                "    \"level\": \"info\",\n" +
                "    \"message\": \"Message 3 with details\",\n" +
                "    \"rawMessage\": \"Message {} with details\",\n" +
                "    \"arguments\": [\n" +
                "      3\n" +
                "    ],\n" +
                "    \"details\": [\n" +
                "      {\n" +
                "        \"level\": \"debug\",\n" +
                "        \"message\": \"Detail 1\",\n" +
                "        \"rawMessage\": \"Detail {}\",\n" +
                "        \"arguments\": [\n" +
                "          1\n" +
                "        ],\n" +
                "        \"timestamp\": <timestamp>\n" +
                "      },\n" +
                "      {\n" +
                "        \"level\": \"info\",\n" +
                "        \"message\": \"Detail 2\",\n" +
                "        \"rawMessage\": \"Detail {}\",\n" +
                "        \"arguments\": [\n" +
                "          2\n" +
                "        ],\n" +
                "        \"timestamp\": <timestamp>\n" +
                "      }\n" +
                "    ],\n" +
                "    \"timestamp\": <timestamp>\n" +
                "  }\n" +
                "]");

        System.out.println(json.replaceAll(TIMESTAMP_REGEX, "<timestamp>"));

        String textrep = container.getMessages().stream()
                .map(Message::toFormattedMessage)
                .collect(Collectors.joining("\n"));
        compare(textrep, "Some problem with foo number 17\n" +
                "Minimal message\n" +
                "Message 3 with details\n" +
                "    Details:\n" +
                "    debug: Detail 1\n" +
                "    Detail 2");
    }

    @Test
    public void i18n() {
        SlingHttpServletRequest request = Mockito.mock(SlingHttpServletRequest.class);
        MockResourceBundle.forRequestMock(request)
                .add("Some problem with {} number {}", "Ein Problem mit {} Nummer {}");

        MessageContainer container = new MessageContainer(LOG);
        container.add(new Message(Message.Level.warn, "Some problem with {} number {}", "foo", 17));
        container.i18n(request);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        compare(gson.toJson(container), "[\n" +
                "  {\n" +
                "    \"level\": \"warn\",\n" +
                "    \"message\": \"Ein Problem mit foo Nummer 17\",\n" +
                "    \"rawMessage\": \"Some problem with {} number {}\",\n" +
                "    \"arguments\": [\n" +
                "      \"foo\",\n" +
                "      17\n" +
                "    ],\n" +
                "    \"timestamp\": <timestamp>\n" +
                "  }\n" +
                "]");
    }

    @Test
    public void i18nViaTypeAdapterFactory() {
        SlingHttpServletRequest request = Mockito.mock(SlingHttpServletRequest.class);
        MockResourceBundle.forRequestMock(request)
                .add("Some problem with {} number {}", "Ein Problem mit {} Nummer {}");

        MessageContainer container = new MessageContainer(LOG);
        container.add(new Message(Message.Level.warn, "Some problem with {} number {}", "foo", 17));
        // not: container.i18n(request);

        Gson gson = new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapterFactory(new MessageTypeAdapterFactory(request))
                .create();

        compare(gson.toJson(container), "[\n" +
                "  {\n" +
                "    \"level\": \"warn\",\n" +
                "    \"message\": \"Ein Problem mit foo Nummer 17\",\n" +
                "    \"rawMessage\": \"Some problem with {} number {}\",\n" +
                "    \"arguments\": [\n" +
                "      \"foo\",\n" +
                "      17\n" +
                "    ],\n" +
                "    \"timestamp\": <timestamp>\n" +
                "  }\n" +
                "]");
    }

    protected void compare(String json, String expectedJson) {
        System.out.println(json.replaceAll(TIMESTAMP_REGEX, "<timestamp>"));
        System.out.println("\n");
        ec.checkThat(json.replaceAll(TIMESTAMP_REGEX, "<timestamp>"), is(expectedJson));
    }

    static class WithMessage {
        MessageContainer messages;
        String bar = "thebar";

        class Wrapped {
            String foo = "thefoo";
        }

        Wrapped asWrapped() {
            return new Wrapped();
        }
    }

    @JsonAdapter(MessageTypeAdapterFactory.class)
    static class SubclassOfMessage extends Message {
        String something = "else";

        public SubclassOfMessage(Level level, String message) {
            super(level, message);
        }
    }

    @Test
    public void derivedClasses() {
        SlingHttpServletRequest request = Mockito.mock(SlingHttpServletRequest.class);
        MockResourceBundle.forRequestMock(request).add("derivedclass", "abgeleitete Klasse");

        Gson gson = new GsonBuilder().setPrettyPrinting()
                .registerTypeAdapterFactory(new MessageTypeAdapterFactory(request))
                .create();

        WithMessage container = new WithMessage();
        compare(gson.toJson(container), "{\n" +
                "  \"bar\": \"thebar\"\n" +
                "}");

        container.messages = new MessageContainer();

        compare(gson.toJson(container), "{\n" +
                "  \"messages\": [],\n" +
                "  \"bar\": \"thebar\"\n" +
                "}");

        container.messages = new MessageContainer();
        container.messages.add(Message.info("Information."));
        container.messages.add(new SubclassOfMessage(Message.Level.error, "derivedclass"));
        ec.checkThat(Message.class.isAssignableFrom(TypeToken.get(SubclassOfMessage.class).getRawType()), is(true));

        compare(gson.toJson(container), "{\n" +
                "  \"messages\": [\n" +
                "    {\n" +
                "      \"level\": \"info\",\n" +
                "      \"message\": \"Information.\",\n" +
                "      \"rawMessage\": \"Information.\",\n" +
                "      \"timestamp\": <timestamp>\n" +
                "    },\n" +
                "    {\n" +
                "      \"something\": \"else\",\n" +
                "      \"level\": \"error\",\n" +
                "      \"message\": \"abgeleitete Klasse\",\n" +
                "      \"rawMessage\": \"derivedclass\",\n" +
                "      \"timestamp\": <timestamp>\n" +
                "    }\n" +
                "  ],\n" +
                "  \"bar\": \"thebar\"\n" +
                "}");

        // For an inner class the outer class is ignored by the default writer.
        compare(gson.toJson(container.asWrapped()), "{\n" +
                "  \"foo\": \"thefoo\"\n" +
                "}");
    }

}
