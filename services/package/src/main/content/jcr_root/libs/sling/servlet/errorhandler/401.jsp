<%@page session="false" pageEncoding="utf-8"
        import="java.net.URLEncoder,
                com.composum.sling.core.util.LinkUtil" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%>
<sling:defineObjects/>
<%
    String url = "/libs/composum/platform/security/login.html?resource="
               + URLEncoder.encode (slingRequest.getRequestURI(), "UTF-8");
    slingResponse.sendRedirect (LinkUtil.getUnmappedUrl(slingRequest, url));
%>