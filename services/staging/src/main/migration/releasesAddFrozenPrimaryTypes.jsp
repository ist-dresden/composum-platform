<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<%--
Adds the missing jcr:frozenPrimaryType attributes on older release contents to avoid the need of accessing the version storage.
The problem was that in DefaultStagingReleaseManager.VersionReferenceImpl.getType a version storage access was triggered,
and users normally do not have any access to version storage, so it fails and breaks the display.
--%>
<%@ page import="java.util.Iterator" %>
<%@ page import="javax.jcr.query.Query" %>
<%@ page import="org.apache.sling.api.resource.ModifiableValueMap" %>
<%@ page import="org.apache.sling.api.resource.Resource" %>
<%@ page import="com.composum.sling.core.util.ResourceUtil" %>
<%@ page import="static org.apache.jackrabbit.JcrConstants.JCR_FROZENNODE" %>
<%@ page import="static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE" %>
<%@ page import="org.apache.jackrabbit.JcrConstants" %>
<%
    String queryReleased = "/jcr:root/var/composum/content//element(*,cpl:VersionReference)[not(@jcr:frozenPrimaryType)]";
    StringBuilder report = new StringBuilder("Report: ");
    for (Iterator<Resource> it = resourceResolver.findResources(queryReleased, Query.XPATH); it.hasNext(); ) {
        Resource r = it.next();
        ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
        String versionId = vm.get("cpl:version", String.class);
        Resource versionResource = ResourceUtil.getByUuid(resourceResolver, versionId);
        String type = versionResource.getValueMap().get(JCR_FROZENNODE + "/" + JCR_FROZENPRIMARYTYPE, String.class);
        report.append(r.getPath() + " : " + type + " <br>\n");
        if (type.contains(":")) {
            vm.put(JcrConstants.JCR_FROZENPRIMARYTYPE, type);
        } else {
            throw new IllegalArgumentException("Strange type " + type + " for " + r.getPath());
        }
    }
%>
<hr>
<%= report.toString() %>
DONE
<%
    resourceResolver.commit();
%>
