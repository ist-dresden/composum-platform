package com.composum.platform.commons.credentials;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.Restricted;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Writer;

import static com.composum.platform.commons.credentials.CredentialServlet.SERVICE_KEY;

/**
 * Servlet with functionality related to the {@link CredentialServlet}.
 */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Credential Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/security/credentials",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_PUT
        })
@Restricted(key = SERVICE_KEY)
public class CredentialServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialServlet.class);

    public static final String SERVICE_KEY = "platform/security/credentials";

    public enum Extension {raw}

    public enum Operation {encryptPassword}

    protected final ServletOperationSet<Extension, Operation> operations = new ServletOperationSet<>(Extension.raw);

    @Reference
    protected CredentialService credentialService;

    @Override
    public void init() throws ServletException {
        super.init();
        // we use PUT since that doesn't store anything - we just read the stream.
        operations.setOperation(ServletOperationSet.Method.PUT, Extension.raw, Operation.encryptPassword,
                new EncryptPasswordOperation());
    }

    @Override
    @NotNull
    protected ServletOperationSet<Extension, Operation> getOperations() {
        return operations;
    }

    /**
     * Encrypts a password with the master password.
     * E.g. curl -X PUT -d 'abcdefg' http://localhost:9090/bin/cpm/platform/security/credentials.encryptPassword.raw
     */
    protected class EncryptPasswordOperation implements ServletOperation {
        @Override
        public void doIt(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            String passwd = IOUtils.toString(request.getInputStream(), request.getCharacterEncoding());
            String encoded = credentialService.encodePassword(passwd);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/plain");
            try (Writer writer = response.getWriter()) {
                writer.write(encoded);
            }
        }
    }
}
