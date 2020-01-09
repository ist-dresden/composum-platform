package com.composum.platform.commons.logging;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static org.hamcrest.Matchers.is;

/** Tests for {@link MessageContainer} and {@link Message}. */
public class MessageContainerTest {

    public static final String TIMESTAMP_REGEX = "157[0-9]{10}";

    @Rule
    public ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    private static final Logger LOG = LoggerFactory.getLogger(MessageContainerTest.class);

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

        ec.checkThat(json.replaceAll(TIMESTAMP_REGEX, "<timestamp>"), is("{\n" +
                "  \"messages\": [\n" +
                "    {\n" +
                "      \"message\": \"Some problem with {} number {}\",\n" +
                "      \"level\": \"warn\",\n" +
                "      \"arguments\": [\n" +
                "        \"foo\",\n" +
                "        17\n" +
                "      ],\n" +
                "      \"timestamp\": <timestamp>\n" +
                "    },\n" +
                "    {\n" +
                "      \"message\": \"Minimal message\",\n" +
                "      \"timestamp\": <timestamp>\n" +
                "    },\n" +
                "    {\n" +
                "      \"message\": \"Message {} with details\",\n" +
                "      \"level\": \"info\",\n" +
                "      \"arguments\": [\n" +
                "        3\n" +
                "      ],\n" +
                "      \"details\": [\n" +
                "        {\n" +
                "          \"message\": \"Detail {}\",\n" +
                "          \"level\": \"debug\",\n" +
                "          \"arguments\": [\n" +
                "            1\n" +
                "          ],\n" +
                "          \"timestamp\": <timestamp>\n" +
                "        },\n" +
                "        {\n" +
                "          \"message\": \"Detail {}\",\n" +
                "          \"level\": \"info\",\n" +
                "          \"arguments\": [\n" +
                "            2\n" +
                "          ],\n" +
                "          \"timestamp\": <timestamp>\n" +
                "        }\n" +
                "      ],\n" +
                "      \"timestamp\": <timestamp>\n" +
                "    }\n" +
                "  ]\n" +
                "}"));

        System.out.println(json.replaceAll(TIMESTAMP_REGEX, "<timestamp>"));

        String textrep = container.getMessages().stream()
                .map(Message::toFormattedMessage)
                .collect(Collectors.joining("\n"));
        System.out.println(textrep);
        ec.checkThat(textrep.replaceAll(TIMESTAMP_REGEX, "<timestamp>"), is("Some problem with foo number 17\n" +
                "Minimal message\n" +
                "Message 3 with details\n" +
                "    Details:\n" +
                "    debug: Detail 1\n" +
                "    Detail 2"));
    }

}
