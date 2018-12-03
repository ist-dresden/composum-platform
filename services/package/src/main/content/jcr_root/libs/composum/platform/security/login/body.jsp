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
<div class="login-page"
     style="background-image:url(${slingRequest.contextPath}/libs/composum/platform/security/login/bg-ist-w-rg.png)">
    <div class="top">
        <cpn:text tagName="h1">Composum Platform</cpn:text>
        <cpn:text tagName="h4" i18n="true">an Apache Sling Application Platform</cpn:text>
        <h4>a.s.a.p.</h4>
    </div>
    <sling:call script="form.jsp"/>
    <div class="bottom">
        <h4>a living multi site and multi application live system</h4>
        <h4>based on the <a href="http://sling.apache.org/">Apache Sling</a> framework</h4>
        <p>&copy; 2015 <a href="http://www.ist-software.com/">IST GmbH Dresden, Germany</a></p>
    </div>
</div>
