package com.composum.platform.commons.credentials.impl;

import com.composum.platform.commons.credentials.CredentialService;
import com.composum.platform.commons.crypt.CryptoServiceImpl;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import java.util.HashMap;
import java.util.Map;

import static com.composum.platform.commons.credentials.impl.CredentialServiceImpl.*;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for {@link CredentialServiceImpl}.
 */
public class CredentialServiceImplTest {

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    static final String MASTERPWD = "782452hiwuf8s7w";
    static final String credId = "some/credential/id";
    static final String aclRefPath = "/content/some/path/to/check/access/to";

    Map<String, Object> credValues = new HashMap<>();
    @Mock
    ResourceResolver resolver;
    @Mock
    Resource credResource;
    @Mock
    ResourceResolverFactory resolverFactory;

    @Mock
    CredentialServiceImpl.Configuration config;

    CredentialService service = new CredentialServiceImpl();

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        CredentialServiceImpl impl = (CredentialServiceImpl) service;
        impl.config = this.config;
        when(config.enabled()).thenReturn(true);
        when(config.masterPassword()).thenReturn(MASTERPWD);
        impl.cryptoService = new CryptoServiceImpl();
        impl.resolverFactory = resolverFactory;

        when(resolver.getResource(PATH_CONFIGS + "/" + credId)).thenReturn(credResource);
        when(credResource.getValueMap()).thenReturn(new ValueMapDecorator(credValues));
        credValues.put(PROP_ENCRYPTED_USER, impl.cryptoService.encrypt("theuser", MASTERPWD));
        credValues.put(PROP_ENCRYPTED_PASSWD, impl.cryptoService.encrypt("thepwd", MASTERPWD));
        credValues.put(PROP_TYPE, new String[]{TYPE_EMAIL, TYPE_HTTP});
        credValues.put(PROP_REFERENCEPATH, aclRefPath);
        when(resolver.getResource(aclRefPath)).thenReturn(mock(Resource.class));

        when(resolverFactory.getServiceResourceResolver(null)).thenReturn(resolver);
    }

    @Test
    public void emailCredentials() throws Exception {
        Authenticator auth = service.getMailAuthenticator(credId, resolver);
        PasswordAuthentication pwdAuth = (PasswordAuthentication) MethodUtils.invokeMethod(auth, true, "getPasswordAuthentication");
        ec.checkThat(pwdAuth.getUserName(), is("theuser"));
        ec.checkThat(pwdAuth.getPassword(), is("thepwd"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void typeForbidden() throws Exception {
        credValues.put(PROP_TYPE, new String[]{TYPE_HTTP});
        emailCredentials();
    }

}
