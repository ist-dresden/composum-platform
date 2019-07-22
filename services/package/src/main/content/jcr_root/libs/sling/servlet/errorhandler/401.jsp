<%@page session="false" pageEncoding="utf-8"
        import="com.composum.platform.commons.request.LoginUtil" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<sling:defineObjects/>
<%
    if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With")) ||
            !LoginUtil.redirectToLogin(slingRequest, slingResponse)) {
        // send the raw status code in the case of an Ajax request of ig the login request itself is not allowed
        slingResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
%>