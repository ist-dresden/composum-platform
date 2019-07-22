<%@page session="false" pageEncoding="utf-8"
        import="com.composum.sling.core.CoreConfiguration" %><%
%><%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%><%
%><%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0"%><%
%><sling:defineObjects/><%
    CoreConfiguration configuration = sling.getService(CoreConfiguration.class);
    if (configuration == null ||
            // try to forward to a custom error page
            !configuration.forwardToErrorpage(slingRequest, slingResponse, HttpServletResponse.SC_NOT_FOUND)) {
        slingResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
%>
<html>
<head>
    <sling:call script="/libs/sling/servlet/errorhandler/head.jsp"/>
</head>
<body>
<div class="error-page"
     style="background-image:url(${cpn:url(slingRequest,'/libs/composum/platform/public/login/bg-ist-w-rg.png')})">
    <div class="error-panel panel panel-default">
        <div class="panel-body">
            <h1>${cpn:i18n(slingRequest,'Not Found')} <em>(404)</em></h1>
            <p>${cpn:i18n(slingRequest,'The requested object was not found on this server.')}</p>
        </div>
    </div>
</div>
</body>
</html>
<%
    }
%>