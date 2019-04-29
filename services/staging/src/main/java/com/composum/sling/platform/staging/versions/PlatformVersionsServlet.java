/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Calendar;

/** This is a thin servlet making the {@link PlatformVersionsService} accessible - see there for description of the operations. */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Versions Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/versions",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET,
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_POST
        })
public class PlatformVersionsServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformVersionsServlet.class);

    @Reference
    protected PlatformVersionsService versionsService;

    protected BundleContext bundleContext;

    @Activate
    private void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Override
    protected boolean isEnabled() {
        return true;
    }

    //
    // Servlet operations
    //

    public enum Extension {
        json
    }

    public enum Operation {
        status,
        activate, deactivate,
        purge
    }

    protected VersionsOperationSet operations = new VersionsOperationSet();

    @Override
    protected ServletOperationSet getOperations() {
        return operations;
    }

    /** setup of the servlet operation set for this servlet instance */
    @Override
    @SuppressWarnings("Duplicates")
    public void init() throws ServletException {
        super.init();

        // GET
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json,
                Operation.status, new GetVersionableStatus());

        // POST
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json,
                Operation.activate, new ActivateVersionable());
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json,
                Operation.deactivate, new DeactivateVersionable());
    }

    public class VersionsOperationSet extends ServletOperationSet<Extension, Operation> {

        public VersionsOperationSet() {
            super(Extension.json);
        }
    }

    protected Resource getVersionable(Resource resource) {
        return resource;
    }

    protected abstract class VersionableOperation implements ServletOperation {

        abstract void performIt(@Nonnull final SlingHttpServletRequest request,
                                @Nonnull final SlingHttpServletResponse response,
                                @Nonnull final Resource versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException;

        @Override
        public void doIt(SlingHttpServletRequest request, SlingHttpServletResponse response,
                         ResourceHandle resource)
                throws IOException {
            Resource versionable = getVersionable(resource);
            if (versionable != null) {
                String releaseKey = request.getParameter("release");
                try {
                    performIt(request, response, versionable, releaseKey);
                } catch (PersistenceException | RepositoryException ex) {
                    LOG.error(ex.getMessage(), ex);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "resource is not versionable (" + request.getRequestURI() + ")");
                }
            } else {
                String msg = "resource is not versionable (" + request.getRequestURI() + ")";
                LOG.error(msg);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
            }
        }
    }

    //
    // status
    //

    protected void writeJsonStatus(@Nonnull final JsonWriter writer,
                                   @Nonnull final Resource versionable, @Nullable String releaseKey)
            throws RepositoryException, IOException {
        PlatformVersionsService.Status status = versionsService.getStatus(versionable, releaseKey);
        writer.beginObject();
        writer.name("name").value(versionable.getName());
        writer.name("path").value(versionable.getPath());
        writer.name("status").value(status.getActivationState().name());
        writer.name("lastModified").value(status.getLastModified().getTimeInMillis());
        Calendar time;
        if ((time = status.getLastActivated()) != null) {
            writer.name("lastActivated").value(time.getTimeInMillis());
        }
        if ((time = status.getLastDeactivated()) != null) {
            writer.name("lastDeactivated").value(time.getTimeInMillis());
        }
        writer.endObject();
    }

    protected class GetVersionableStatus extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Resource versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable, releaseKey);
        }
    }

    //
    // changes
    //

    protected class ActivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Resource versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            String versionUuid = StringUtils.defaultIfBlank(request.getParameter("versionUuid"), null);
            versionsService.activate(versionable, releaseKey, versionUuid);
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable, releaseKey);
        }
    }

    protected class DeactivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Resource versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            versionsService.deactivate(versionable, releaseKey);
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable, releaseKey);
        }
    }

    protected class PurgeVersions extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Resource versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            versionsService.purgeVersions(versionable);
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable, releaseKey);
        }
    }
}
