<%@page session="false" pageEncoding="utf-8"
        import="com.composum.platform.commons.request.LoginUtil" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<%
    if (LoginUtil.redirectToLogin(slingRequest, slingResponse)) {
        return;
    }
%>
<% slingResponse.setStatus((Integer) slingRequest.getAttribute("errorpage.status")); %>
<html>
<head>
    <sling:call script="../head.jsp"/>
</head>
<body>
<div class="error-page"
     style="background-image:url(${cpn:url(slingRequest,'/libs/composum/platform/public/login/bg-ist-w-rg.png')})">
    <div class="error-panel panel panel-default">
        <div class="panel-body">
            <h1>Unauthorized <em>(401)</em></h1>
            <p>Authorization is needed to access the requested object.</p>
        </div>
    </div>
</div>
</body>
</html>
