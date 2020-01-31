package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.AggregatedReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.ReplicationStateInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
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

/**
 * Servlet that allows access to some functions of the
 * {@link com.composum.sling.platform.staging.ReleaseChangeEventPublisher} : namely, querying the stati of the
 * replication processes.
 */
@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Release Change Publisher Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/platform/staging/releasechangepublisher",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET,
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_POST
        })
public class ReleaseChangeEventPublisherServlet extends AbstractServiceServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseChangeEventPublisherServlet.class);

    protected ServletOperationSet<Extension, Operation> operations;

    public enum Operation {replicationState, aggregatedReplicationState, compareContent}

    public enum Extension {json}

    @Reference
    ReleaseChangeEventPublisher service;

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
    }

    /** Interfaces {@link ReleaseChangeEventPublisher#replicationState(Resource)}. */
    protected class ReplicationStateOperation implements ServletOperation {
        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                Map<String, ReplicationStateInfo> result = service.replicationState(resource);
                Map<String, Object> map = status.data("replicationStates");
                for (Map.Entry<String, ReplicationStateInfo> entry : result.entrySet()) {
                    map.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                LOG.error("Internal error", e);
                status.error("Internal error");
            } finally {
                status.sendJson();
            }
        }
    }

    /** Interfaces {@link ReleaseChangeEventPublisher#aggregatedReplicationState(Resource)}. */
    protected class AggregatedReplicationStateOperation implements ServletOperation {
        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                AggregatedReplicationStateInfo result = service.aggregatedReplicationState(resource);
                status.data("aggregatedReplicationState").put("result", result);
            } catch (Exception e) {
                LOG.error("Internal error", e);
                status.error("Internal error");
            } finally {
                status.sendJson();
            }
        }
    }

    /** Interfaces {@link ReleaseChangeEventPublisher#aggregatedReplicationState(Resource)}. */
    protected class CompareTreeOperation implements ServletOperation {

        /** Name of the parameter to request different detail levels. */
        public static final String PARAM_DETAILS = "details";
        /**
         * Name of the parameter to request only data from specific
         * {@link com.composum.sling.platform.staging.ReleaseChangeProcess}es.
         */
        public static final String PARAM_PROCESS_ID = "processId";
        /** Name of result parameter. */
        public static final String RESULT_COMPARETREE = "compareTree";

        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response, LOG);
            try {
                if (resource != null) {
                    String detailsParam = request.getParameter(PARAM_DETAILS);
                    int details = StringUtils.isNotBlank(detailsParam) ? Integer.parseInt(detailsParam) : 0;
                    String[] processIdParams = request.getParameterValues(PARAM_PROCESS_ID);
                    service.compareTree(resource, details, processIdParams, status.data(RESULT_COMPARETREE));
                } else {
                    status.error("Resource not found");
                    status.setStatus(SlingHttpServletResponse.SC_NOT_FOUND);
                }
            } catch (Exception e) {
                LOG.error("Internal error", e);
                status.error("Internal error");
            } finally {
                status.sendJson();
            }
        }
    }
}
