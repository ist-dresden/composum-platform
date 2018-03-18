<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<sling:defineObjects/>
<html>
<head>
    <sling:call script="head.jsp"/>
</head>
<body>
<sling:call script="body.jsp"/>
<sling:call script="script.jsp"/>
</body>
</html>
