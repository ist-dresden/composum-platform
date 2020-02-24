<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.replication.model.ReplicationStatus"
               path="${cpn:filter(slingRequest.requestPathInfo.suffix)}" scope="request">
    <sling:call script="content.jsp"/>
</cpn:component>
