<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<%@ page import="java.util.Iterator" %>
<%@ page import="javax.jcr.query.Query" %>
<%@ page import="org.apache.sling.api.resource.Resource" %>
<%@ page import="com.composum.sling.core.util.ResourceUtil" %>
<%@ page import="java.util.regex.Pattern" %>
<%@ page import="java.util.regex.Matcher" %>
<%@ page import="javax.jcr.Session" %>
<sling:defineObjects/>
<%
    /** Since the orphaned versionables are only then accessible to the user when they are restored in a path readable by the user,
     * we move them into a special folder cpl:attic within the site. */
    // example /var/composum/content/cpl:attic/1aa7c9ac-11fd-4bf5-8ffb-cba0d73bd7fc/content/ist/composum/assets/general/adaptTo.png/jcr:content
    // moved to /content/ist/composum/cpl:attic/1aa7c9ac-11fd-4bf5-8ffb-cba0d73bd7fc/assets/general/adaptTo.png/jcr:content .
    String queryReleased = "/jcr:root/var/composum/content/cpl:attic//element(*,mix:versionable)";
    Pattern pat = Pattern.compile("/var/composum/content/cpl:attic/(?<id>[0-9a-f-]+)(?<site>/content/[^/]+/[^/]+)(?<path>.*/jcr:content)");
    StringBuilder report = new StringBuilder("Report: ");
    Session session = resourceResolver.adaptTo(Session.class);
    for (Iterator<Resource> it = resourceResolver.findResources(queryReleased, Query.XPATH); it.hasNext(); ) {
        Resource r = it.next();
        Matcher m = pat.matcher(r.getPath());
        if (m.matches()) {
            String newpath = m.group("site") + "/cpl:attic/" + m.group("id") + m.group("path");
            report.append(r.getPath()).append("<br>==> ").append(newpath).append("<br>");
            String destAbsPath = ResourceUtil.getParent(newpath);
            ResourceUtil.getOrCreateResource(resourceResolver, destAbsPath);
            session.move(r.getPath(), newpath);
            // resourceResolver.move(r.getPath(), destAbsPath); doesn't work on Jackrabbit 1.8.8 (does a copy!)
        } else {
            report.append("NOT MATCHED: " + r.getPath()).append("<br>");
        }
    }
%>
<hr>
<%= report.toString() %>
DONE
<%
    resourceResolver.commit();
%>
