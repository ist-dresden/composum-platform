package com.composum.platform.commons.credentials;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Writer;

/** Servlet with functionality related to the {@link CredentialServlet}. */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Credential Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/security/credentials",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_PUT
        })
public class CredentialServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialServlet.class);

    public enum Extension {raw}

    public enum Operation {encodePassword}

    protected final ServletOperationSet<Extension, Operation> operations = new ServletOperationSet<>(Extension.raw);

    @Reference
    protected CredentialService credentialService;

    @Override
    protected boolean isEnabled() {
        return credentialService.isEnabled();
    }

    @Override
    public void init() throws ServletException {
        super.init();
        // we use PUT since that doesn't store anything - we just read the stream.
        operations.setOperation(ServletOperationSet.Method.PUT, Extension.raw, Operation.encodePassword,
                new EncodePasswordOperation());
    }

    @Override
    protected ServletOperationSet getOperations() {
        return operations;
    }

    protected class EncodePasswordOperation implements ServletOperation {
        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            String passwd = IOUtils.toString(request.getReader());
            String encoded = credentialService.encodePassword(passwd);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/plain");
            try (Writer writer = response.getWriter()) {
                writer.write(encoded);
            }
        }
    }
}
