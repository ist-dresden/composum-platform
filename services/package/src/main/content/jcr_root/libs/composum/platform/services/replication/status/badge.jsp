<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.model.ReplicationStatus"
               scope="request">
    <div data-path="${model.path}" data-state="${model.replicationState.jsonSummary}"
         class="composum-platform-replication-status_badge widget badge badge-pill ${model.replicationState.state}"
         title="${cpn:i18n(slingRequest,model.stage)}: ${cpn:i18n(slingRequest,model.replicationState.state)}"><i
            class="fa fa-${model.stage=='public'?'globe':'eye'}"></i></div>
</cpn:component>
