package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.core.util.RequestUtil;
import com.composum.sling.core.util.XSS;
import com.composum.platform.commons.request.AccessMode;
import com.composum.sling.platform.staging.Release;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.AggregatedReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.ReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.replication.ReplicationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
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
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Servlet that provides operations to publish releases and retrieve the replication state.
 */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Staging Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/staging",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET,
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_POST
        })
public class PlatformStagingServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformStagingServlet.class);

    public static final String PARAM_RELEASE_KEY = "releaseKey";
    public static final String PARAM_FULL_SYNC = "fullSync";

    protected ServletOperationSet<Extension, Operation> operations;

    public enum Operation {
        stageRelease, abortReplication,
        replicationState, aggregatedReplicationState,
        compareContent
    }

    public enum Extension {json}

    @Reference
    private StagingReleaseManager releaseManager;

    @Reference
    ReleaseChangeEventPublisher releasePublisher;

    @Override
    protected boolean isEnabled() {
        return true;
    }

    @Override
    protected ServletOperationSet<Extension, Operation> getOperations() {
        return operations;
    }

    @Override
    public void init() throws ServletException {
        super.init();
        operations = new ServletOperationSet<>(Extension.json);
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json, Operation.replicationState,
                new ReplicationStateOperation());
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json, Operation.aggregatedReplicationState,
                new AggregatedReplicationStateOperation());

        // FIXME(hps,21.01.20) this should be only POST since that's an expensive operation, but for now we
        // let it at GET, too, to be easily able to trigger it from the browser
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json, Operation.compareContent,
                new CompareTreeOperation());
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json, Operation.compareContent,
                new CompareTreeOperation());
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json, Operation.stageRelease,
                new StageReleaseOperation());
        operations.setOperation(ServletOperationSet.Method.POST, Extension.json, Operation.abortReplication,
                new AbortReplicationOperation());
    }

    public static String getReleaseKey(@Nonnull final SlingHttpServletRequest request,
                                       @Nullable final Resource resource, @Nonnull final Status status) {
        String releaseKey = RequestUtil.getParameter(request, PARAM_RELEASE_KEY, "");
        if (StringUtils.isBlank(releaseKey) && resource != null) {
            final String path = resource.getPath();
            final Matcher pathMatcher = StagingUtils.RELEASE_PATH_PATTERN.matcher(path);
            if (pathMatcher.matches()) {
                final String sitePath = pathMatcher.group(1);
                releaseKey = pathMatcher.group(2);
            } else {
                status.error("no release path ({})", path);
            }
        }
        return releaseKey;
    }

    protected class StageReleaseOperation implements ServletOperation {

        @Override
        public void doIt(@Nonnull final SlingHttpServletRequest request,
                         @Nonnull final SlingHttpServletResponse response,
                         @Nullable final ResourceHandle resource)
                throws IOException {
            Status status = new Status(request, response);
            if (resource != null && resource.isValid()) {
                try {
                    String releaseKey = getReleaseKey(request, resource, status);
                    Release release = releaseManager.findRelease(resource, releaseKey);
                    RequestPathInfo pathInfo = request.getRequestPathInfo();
                    String[] selectors = pathInfo.getSelectors();
                    try {
                        boolean fullSync = Boolean.parseBoolean(
                                RequestUtil.getParameter(request, PARAM_FULL_SYNC, "false"));
                        String stage = AccessMode.valueOf((selectors.length > 1 ? selectors[1] : "?")
                                .toUpperCase()).name().toLowerCase();
                        // replication is triggered by setMark via the ReleaseChangeEventListener .
                        releaseManager.setMark(stage, release, fullSync);
                        LOG.info("Release '{}' published to stage '{}'.", release, stage);
                        request.getResourceResolver().commit();
                    } catch (IllegalArgumentException iaex) {
                        status.error("no valid stage key specified");
                    }
                } catch (Exception ex) {
                    status.error("error setting release category: {}", ex);
                }
            } else {
                status.error("requests resource not available");
            }
            status.sendJson();
        }
    }

    /**
     * Interfaces {@link ReleaseChangeEventPublisher#replicationState(Resource, String)}.
     */
    protected class ReplicationStateOperation implements ServletOperation {

        /**
         * Name of optional parameter to restrict the result to a certain {@link ReleaseChangeProcess#getStage()}.
         */
        public static final String PARAM_STAGE = "stage";

        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                String stageParam = RequestUtil.getParameter(request, PARAM_STAGE, (String) null);
                Map<String, ReplicationStateInfo> result = releasePublisher.replicationState(resource, stageParam);
                Map<String, Object> map = status.data("replicationStates");
                for (Map.Entry<String, ReplicationStateInfo> entry : result.entrySet()) {
                    map.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                status.error("Internal error", e);
            } finally {
                status.sendJson();
            }
        }
    }

    /**
     * Interfaces {@link ReleaseChangeEventPublisher#aggregatedReplicationState(Resource, String)}.
     */
    protected class AggregatedReplicationStateOperation implements ServletOperation {

        /**
         * Name of optional parameter to restrict the result to a certain {@link ReleaseChangeProcess#getStage()}.
         */
        public static final String PARAM_STAGE = "stage";

        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                String stageParam = RequestUtil.getParameter(request, PARAM_STAGE, (String) null);
                AggregatedReplicationStateInfo result = releasePublisher.aggregatedReplicationState(resource, stageParam);
                status.data("aggregatedReplicationState").put("result", result);
            } catch (Exception e) {
                status.error("Internal error", e);
            } finally {
                status.sendJson();
            }
        }
    }

    /**
     * Interfaces {@link ReleaseChangeEventPublisher#aggregatedReplicationState(Resource, String)}.
     */
    protected class AbortReplicationOperation implements ServletOperation {

        /**
         * Name of optional parameter to restrict the result to a certain {@link ReleaseChangeProcess#getStage()}.
         */
        public static final String PARAM_STAGE = "stage";

        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                String stageParam = RequestUtil.getParameter(request, PARAM_STAGE, (String) null);
                releasePublisher.abortReplication(resource, stageParam);
            } catch (Exception e) {
                status.error("Internal error", e);
            } finally {
                status.sendJson();
            }
        }
    }

    /**
     * Interfaces {@link ReleaseChangeEventPublisher#aggregatedReplicationState(Resource, String)}.
     */
    protected class CompareTreeOperation implements ServletOperation {

        /**
         * Name of the numeric parameter to request different detail levels. 0 just returns detail counts, 1 returns
         * paths, too. Some replication implementations might define further detail levels.
         */
        public static final String PARAM_DETAILS = "details";
        /**
         * Name of the parameter to request only data from specific
         * {@link com.composum.sling.platform.staging.ReleaseChangeProcess}es.
         */
        public static final String PARAM_PROCESS_ID = "processId";
        /**
         * Name of result parameter.
         */
        public static final String RESULT_COMPARETREE = "compareTree";

        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                if (resource != null) {
                    String detailsParam = RequestUtil.getParameter(request, PARAM_DETAILS, "");
                    int details = StringUtils.isNotBlank(detailsParam) ? Integer.parseInt(detailsParam) : 0;
                    String[] processIdParams = XSS.filter(request.getParameterValues(PARAM_PROCESS_ID));
                    releasePublisher.compareTree(resource, details, processIdParams, status.data(RESULT_COMPARETREE));
                } else {
                    status.error("Resource not found");
                    status.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                }
            } catch (ReplicationException e) {
                e.writeIntoStatus(status);
            } catch (Exception e) {
                status.error("Internal error", e);
            } finally {
                status.sendJson();
            }
        }
    }
}
