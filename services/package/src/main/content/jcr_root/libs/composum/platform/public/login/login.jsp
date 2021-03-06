<%@page session="false" pageEncoding="utf-8"
        import="com.composum.sling.core.util.XSS" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<sling:defineObjects/><%
    // redirect to login target if always or meanwhile logged in
    String userId = slingRequest.getResourceResolver().getUserID();
    if (userId != null && (userId = userId.trim()).length() > 0 && !"anonymous".equals(userId)
            && slingRequest.getParameter("login") == null) {
        String redirect = XSS.filter(slingRequest.getParameter("resource"));
        if (redirect == null) {
            redirect = XSS.filter(slingRequest.getParameter("target"));
        }
        if (redirect == null) {
            redirect = XSS.filter(slingRequest.getRequestPathInfo().getSuffix());
        }
        if (redirect == null || (redirect = redirect.trim()).length() < 1) {
            redirect = "/";
        }
        slingResponse.sendRedirect(redirect);
        return;
    }
%>
<html>
<head>
    <sling:call script="head.jsp"/>
</head>
<body class="composum-platform-public-login_body">
<sling:call script="body.jsp"/>
<sling:call script="script.jsp"/>
</body>
</html>
