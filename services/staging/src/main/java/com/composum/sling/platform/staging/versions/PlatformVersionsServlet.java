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
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.core.servlet.Status;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
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
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** This is a thin servlet making the {@link PlatformVersionsService} accessible - see there for description of the operations. */
// FIXME(hps,2019-05-27) i18n of error messages?
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
    public static final String PARAM_TARGET = "target[]";
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
                                @Nonnull final Status status,
                                @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey);

        @Override
        public void doIt(SlingHttpServletRequest request, SlingHttpServletResponse response,
                         ResourceHandle resource)
                throws IOException {
            Status status = new Status(request, response);
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
            if (status.isValid()) {
                if (versionable.size() > 0) {
                    String releaseKey = request.getParameter("release");
                    performIt(request, response, status, versionable, releaseKey);
                } else {
                    status.withLogging(LOG).error("resource is not versionable ({})", request.getRequestURI());
                }
            }
            status.sendJson();
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

    protected class GetVersionableStatus extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response, @Nonnull final Status requestStatus,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey) {
            try {
                Resource target = versionable.iterator().next();
                PlatformVersionsService.Status status = versionsService.getStatus(target, releaseKey);
                Map<String, Object> data = requestStatus.data("data");
                data.put("name", target.getName());
                data.put("path", target.getPath());
                if (status != null) {
                    data.put("status", status.getActivationState().name());
                    Calendar time;
                    if ((time = status.getLastModified()) != null) {
                        data.put("lastModified", time.getTimeInMillis());
                    }
                    if ((time = status.getLastActivated()) != null) {
                        data.put("lastActivated", time.getTimeInMillis());
                    }
                    if ((time = status.getLastDeactivated()) != null) {
                        data.put("lastDeactivated", time.getTimeInMillis());
                    }
                }
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
                requestStatus.error(ex.getMessage());
            }
        }
    }

    //
    // changes
    //

    protected class ActivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response, @Nonnull final Status status,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey) {
            String versionUuid = StringUtils.defaultIfBlank(request.getParameter("versionUuid"), null);
            Set<String> references = addParameter(addParameter(new HashSet<>(), request, PARAM_PAGE_REFS), request, PARAM_ASSET_REFS);
            if (StringUtils.isNotBlank(versionUuid)) {
                if (references.size() > 0) { // we have no method that takes both a versionUuid and references. Implement if needed.
                    String msg = "Version uuid given *and* additional referred pages. That does not (yet) supported";
                    LOG.error(msg);
                    status.error(msg);
                } else {
                    try {
                        versionsService.activate(releaseKey, versionable.iterator().next(), versionUuid);
                        request.getResourceResolver().commit();
                    } catch (Exception ex) {
                        LOG.error(ex.getMessage(), ex);
                        status.error(ex.getMessage());
                    }
                }
            } else {
                List<Resource> toActivate = new ArrayList<>(versionable);
                for (String referencedPath : references) {
                    if (!StringUtils.startsWith(referencedPath, "/content")) {
                        status.withLogging(LOG).error("Can only activate references from /content, but got: {}", referencedPath);
                    } else {
                        Resource reference = request.getResourceResolver().getResource(referencedPath);
                        if (reference == null) {
                            status.withLogging(LOG).error("Reference to activate not found: {}", referencedPath);
                        } else {
                            toActivate.add(reference);
                        }
                    }
                }
                if (status.isValid()) {
                    try {
                        PlatformVersionsService.ActivationResult result = versionsService.activate(releaseKey, toActivate);
                        // TODO(hps,2019-05-20) actually use result
                        request.getResourceResolver().commit();
                    } catch (Exception ex) {
                        LOG.error(ex.getMessage(), ex);
                        status.error(ex.getMessage());
                    }
                }
            }
        }
    }

    protected class DeactivateVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response, @Nonnull final Status status,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey) {
            Set<String> referrers = addParameter(new HashSet<>(), request, PARAM_PAGE_REFS);
            List<Resource> toDeactivate = new ArrayList<>(versionable);
            for (String referrerPath : referrers) {
                Resource referrer = request.getResourceResolver().getResource(referrerPath);
                if (referrer == null) {
                    status.withLogging(LOG).error("Referrer to deactivate not found: {}", referrerPath);
                } else {
                    toDeactivate.add(referrer);
                }
            }
            if (status.isValid()) {
                try {
                    versionsService.deactivate(releaseKey, toDeactivate);
                    request.getResourceResolver().commit();
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                    status.error(ex.getMessage());
                }
            }
        }
    }

    protected class RevertVersionable extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response, @Nonnull final Status status,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey) {
            Set<String> referrers = addParameter(new HashSet<>(), request, PARAM_PAGE_REFS);
            List<Resource> toRevert = new ArrayList<>(versionable);
            for (String pagePath : referrers) {
                Resource referrer = request.getResourceResolver().getResource(pagePath);
                if (referrer == null) {
                    status.withLogging(LOG).error("Page to revert not found: {}", pagePath);
                } else {
                    toRevert.add(referrer);
                }
            }
            if (status.isValid()) {
                try {
                    PlatformVersionsService.ActivationResult result = versionsService.revert(releaseKey, toRevert);
                    // TODO(hps,2019-05-21) do something with result
                    request.getResourceResolver().commit();
                } catch (Exception ex) {
                    LOG.error(ex.getMessage(), ex);
                    status.error(ex.getMessage());
                }
            }
        }
    }

    protected class PurgeVersions extends VersionableOperation {

        @Override
        public void performIt(@Nonnull final SlingHttpServletRequest request,
                              @Nonnull final SlingHttpServletResponse response, @Nonnull final Status status,
                              @Nonnull final Collection<Resource> versionable, @Nullable final String releaseKey) {
            try {
                Resource target = versionable.iterator().next();
                versionsService.purgeVersions(target);
                request.getResourceResolver().commit();
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
                status.error(ex.getMessage());
            }
        }
    }
}
