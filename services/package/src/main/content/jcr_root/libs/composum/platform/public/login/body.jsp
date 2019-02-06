<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/><%
    // redirect to login target if always or meanwhile logged in
    String userId = slingRequest.getResourceResolver().getUserID();
    if (userId != null && (userId = userId.trim()).length() > 0 && !"anonymous".equals(userId)) {
        String suffix = slingRequest.getRequestPathInfo().getSuffix();
        if (suffix == null || (suffix = suffix.trim()).length() < 1) {
            suffix = "/";
        }
        slingResponse.sendRedirect(suffix);
        return;
    }
%>
<div class="composum-platform-public_content login-page"
     style="background-image:url(${slingRequest.contextPath}/libs/composum/platform/public/login/bg-ist-w-rg.png)">
    <sling:include path="../page" replaceSelectors="header"/>
    <sling:call script="form.jsp"/>
    <sling:include path="../page" replaceSelectors="footer"/>
</div>
