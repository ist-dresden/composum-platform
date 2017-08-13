<%@page session="false" pageEncoding="utf-8"
        import="org.apache.sling.api.SlingHttpServletResponse,
            com.composum.sling.core.CoreConfiguration" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0"%>
<sling:defineObjects/>
<%
    CoreConfiguration configuration = sling.getService(CoreConfiguration.class);
    if (configuration == null ||
            !configuration.forwardToErrorpage(slingRequest, slingResponse,
                    HttpServletResponse.SC_FORBIDDEN)) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
%>
<html>
<head>
    <sling:call script="/libs/sling/servlet/errorhandler/head.jsp"/>
</head>
<body>
<div class="error-page" style="background-image:url(${cpn:url(slingRequest,'/libs/composum/platform/security/login/bg-ist-w-rg.png')})">
    <div class="error-panel panel panel-default">
        <div class="panel-body">
            <h1>Forbidden <em>(403)</em></h1>
            <p>The access to the requested object is not allowed.</p>
        </div>
    </div>
</div>
</body>
</html>
<%
    }
%>