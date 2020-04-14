<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.model.ReplicationStatus"
               scope="request">
    <div class="composum-platform-replication-status composum-platform-replication-status_stage-${model.stage}">
        <sling:call script="content.jsp"/>
    </div>
</cpn:component>
