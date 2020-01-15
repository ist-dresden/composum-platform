package com.composum.sling.platform.staging.impl;

import com.composum.platform.commons.logging.Message;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.servlet.AbstractServiceServlet;
import com.composum.sling.core.servlet.ServletOperation;
import com.composum.sling.core.servlet.ServletOperationSet;
import com.composum.sling.core.servlet.Status;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.AggregatedReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher.ReplicationStateInfo;
import com.composum.sling.platform.staging.ReleaseChangeProcess;
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

    public enum Operation {replicationState, aggregatedReplicationState}

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
        operations = new ServletOperationSet(Extension.json);
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json, Operation.replicationState,
                new ReplicationStateOperation());
        operations.setOperation(ServletOperationSet.Method.GET, Extension.json, Operation.aggregatedReplicationState,
                new AggregatedReplicationStateOperation());
    }

    /** Interfaces {@link ReleaseChangeEventPublisher#replicationState(Resource)}. */
    protected class ReplicationStateOperation implements ServletOperation {
        @Override
        public void doIt(@Nonnull SlingHttpServletRequest request, @Nonnull SlingHttpServletResponse response, @Nullable ResourceHandle resource) throws RepositoryException, IOException, ServletException {
            Status status = new Status(request, response);
            try {
                Map<String, ReplicationStateInfo> result = service.replicationState(resource);
                Map<String, Object> map = status.data("replicationStates");
                for (Map.Entry<String, ReplicationStateInfo> entry : result.entrySet()) {
                    entry.getValue().messages.i18n(request);
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
            Status status = new Status(request, response);
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
}
