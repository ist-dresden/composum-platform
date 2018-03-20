<%@page session="false" pageEncoding="utf-8"
        import="java.net.URLEncoder,
                com.composum.sling.core.util.LinkUtil" %><%
%><%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %><%
%><sling:defineObjects/><%
    if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
        // send the raw status code in the case of an Ajax request
        slingResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    } else {
        String url = "/libs/composum/platform/security/login.html?resource="
                + URLEncoder.encode(slingRequest.getRequestURI(), "UTF-8");
        slingResponse.sendRedirect(LinkUtil.getUnmappedUrl(slingRequest, url));
    }
%>