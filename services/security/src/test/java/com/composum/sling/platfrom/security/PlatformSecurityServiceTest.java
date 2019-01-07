package com.composum.sling.platfrom.security;

import com.composum.sling.platform.security.PlatformSecurityService;
import com.google.gson.stream.JsonReader;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.jcr.Session;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PlatformSecurityServiceTest {

    public static final String JAVA_RESOURCE_BASE = "/com/composum/platfrom/security/";

    public static class TestService extends PlatformSecurityService {

        @Override
        public void addAcl(@Nonnull final Session session, @Nonnull final String path,
                           @Nonnull final String principal, boolean allow,
                           @Nonnull final String[] privileges,
                           @Nonnull final Map restrictions) {
            System.out.println("addAcl(" + path + "," + principal + ","
                    + allow + "," + Arrays.toString(privileges) + "," + restrictions + ")");
        }
    }

    @Test
    public void testAclFromJson() throws Exception {
        TestService service = new TestService();
        try (
                InputStream stream = getClass().getResourceAsStream(JAVA_RESOURCE_BASE + "acl.json");
                Reader streamReader = new InputStreamReader(stream, UTF_8);
                JsonReader reader = new JsonReader(streamReader)
        ) {
            service.addJsonAcl(null, reader);
        }
    }
}
