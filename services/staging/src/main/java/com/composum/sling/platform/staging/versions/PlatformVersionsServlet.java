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
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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
import java.util.*;

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

    /** the parameter name for explicit (multi valued) target resources */
    public static final String PARAM_TARGET = "target";

    /** An array of page references or referrers which should simultaneously be activated / deactivated. */
    public static final String PARAM_PAGE_REFS = "pageRef[]";
    /** An array of assets which should simultaneously be activated / deactivated. */
    public static final String PARAM_ASSET_REFS = "assetRef[]";

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
        activate, deactivate, revert,
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
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json,
                Operation.revert, new RevertVersionable());

        operations.setOperation(ServletOperationSet.Method.POST, Extension.json,
                Operation.purge, new PurgeVersions());
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
                                @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException, StagingReleaseManager.ReleaseClosedException;

        @Override
        public void doIt(SlingHttpServletRequest request, SlingHttpServletResponse response,
                         ResourceHandle resource)
                throws IOException {
            Collection<Resource> versionable = new ArrayList<>();
            String[] targetValues = request.getParameterValues(PARAM_TARGET);
            if (targetValues != null && targetValues.length > 0) {
                ResourceResolver resolver = request.getResourceResolver();
                for (String path : targetValues) {
                    Resource target = resolver.getResource(path);
                    if (target != null && (target = getVersionable(target)) != null) {
                        versionable.add(getVersionable(target));
                    }
                }
            } else {
                Resource target = getVersionable(resource);
                if (target != null) {
                    versionable.add(getVersionable(resource));
                }
            }
            if (versionable.size() > 0) {
                String releaseKey = request.getParameter("release");
                try {
                    performIt(request, response, versionable, releaseKey);
                } catch (PersistenceException | RepositoryException ex) {
                    LOG.error(ex.getMessage(), ex);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "resource is not versionable (" + request.getRequestURI() + ")");
                } catch (StagingReleaseManager.ReleaseClosedException ex) {
                    LOG.error(ex.getMessage(), ex);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Cannot activate in a closed release (" + request.getRequestURI() + ")");
                }
            } else {
                String msg = "resource is not versionable (" + request.getRequestURI() + ")";
                LOG.error(msg);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
            }
        }

        @Nonnull
        protected Set<String> addParameter(Set<String> set, SlingHttpServletRequest request, String name) {
            String[] values = request.getParameterValues(name);
            if (values != null) {
                for (String value : values) {
                    if (StringUtils.isNotBlank(value))
                        set.add(value);
                }
            }
            return set;
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
        if (status != null) {
            writer.name("status").value(status.getActivationState().name());
            Calendar time;
            if ((time = status.getLastModified()) != null) {
                writer.name("lastModified").value(time.getTimeInMillis());
            }
            if ((time = status.getLastActivated()) != null) {
                writer.name("lastActivated").value(time.getTimeInMillis());
            }
            if ((time = status.getLastDeactivated()) != null) {
                writer.name("lastDeactivated").value(time.getTimeInMillis());
            }
        }
        writer.endObject();
    }

    protected class GetVersionableStatus extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable.iterator().next(), releaseKey);
        }
    }

    //
    // changes
    //

    protected class ActivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException, StagingReleaseManager.ReleaseClosedException {
            String versionUuid = StringUtils.defaultIfBlank(request.getParameter("versionUuid"), null);
            Set<String> references = addParameter(addParameter(new HashSet<>(), request, PARAM_PAGE_REFS), request, PARAM_ASSET_REFS);
            if (StringUtils.isNotBlank(versionUuid)) {
                if (references.size() > 0) { // we have no method that takes both a versionUuid and references. Implement if needed.
                    String msg = "Version uuid given *and* additional referred pages. That does not (yet) supported";
                    LOG.error(msg);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
                    return;
                }
                versionsService.activate(releaseKey, versionable.iterator().next(), versionUuid);
            } else {
                List<Resource> toActivate = new ArrayList<>(versionable);
                for (String referencedPath : references) {
                    if (!StringUtils.startsWith(referencedPath, "/content")) {
                        String msg = "Can only activate references from /content, but got: " + referencedPath;
                        LOG.error(msg);
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
                        return;
                    }
                    Resource reference = request.getResourceResolver().getResource(referencedPath);
                    if (reference == null) {
                        String msg = "Reference to activate not found: " + referencedPath;
                        LOG.error(msg);
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
                        return;
                    }
                    toActivate.add(reference);
                }
                versionsService.activate(releaseKey, toActivate);
            }
            request.getResourceResolver().commit();
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable.iterator().next(), releaseKey);
        }
    }

    protected class DeactivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException, StagingReleaseManager.ReleaseClosedException {
            Set<String> referrers = addParameter(new HashSet<>(), request, PARAM_PAGE_REFS);
            List<Resource> toDeactivate = new ArrayList<>(versionable);
            for (String referrerPath : referrers) {
                Resource referrer = request.getResourceResolver().getResource(referrerPath);
                if (referrer == null) {
                    String msg = "Referrer to deactivate not found: " + referrerPath;
                    LOG.error(msg);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
                    return;
                }
                toDeactivate.add(referrer);
            }
            versionsService.deactivate(releaseKey, toDeactivate);
            request.getResourceResolver().commit();
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable.iterator().next(), releaseKey);
        }
    }

    protected class RevertVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException, StagingReleaseManager.ReleaseClosedException {
            Set<String> referrers = addParameter(new HashSet<>(), request, PARAM_PAGE_REFS);
            List<Resource> toRevert = new ArrayList<>(versionable);
            for (String pagePath : referrers) {
                Resource referrer = request.getResourceResolver().getResource(pagePath);
                if (referrer == null) {
                    String msg = "Page to revert not found: " + pagePath;
                    LOG.error(msg);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg);
                    return;
                }
                toRevert.add(referrer);
            }
            versionsService.revert(releaseKey, toRevert);
            request.getResourceResolver().commit();
            writeJsonStatus(new JsonWriter(response.getWriter()), versionable.iterator().next(), releaseKey);
        }
    }

    protected class PurgeVersions extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey)
                throws RepositoryException, IOException {
            Resource target = versionable.iterator().next();
            versionsService.purgeVersions(target);
            request.getResourceResolver().commit();
            writeJsonStatus(new JsonWriter(response.getWriter()), target, releaseKey);
        }
    }
}
