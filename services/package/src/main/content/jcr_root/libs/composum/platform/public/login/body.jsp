<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<div class="composum-platform-public_content login-page"
     style="background-image:url(${slingRequest.contextPath}/libs/composum/platform/public/login/bg-ist-w-rg.png)">
    <sling:include path="../page" replaceSelectors="header"/>
    <sling:call script="form.jsp"/>
    <sling:include path="../page" replaceSelectors="footer"/>
</div>
