<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.platform.commons.request.ErrorPage">
    <%
        if (model.redirectToLogin(slingRequest, slingResponse)) {
            return;
        }
        slingResponse.setStatus((Integer) slingRequest.getAttribute("errorpage.status"));
    %>
</cpn:component>
<html>
<head>
    <sling:call script="../head.jsp"/>
</head>
<body>
<div class="error-page"
     style="background-image:url(${cpn:url(slingRequest,'/libs/composum/platform/public/login/bg-ist-w-rg.png')})">
    <div class="error-panel panel panel-default">
        <div class="panel-body">
            <h1>${cpn:i18n(slingRequest,'Unauthorized')} <em>(401)</em></h1>
            <p>${cpn:i18n(slingRequest,'Authorization is needed to access the requested object.')}</p>
        </div>
    </div>
</div>
</body>
</html>
