<%@page session="false" pageEncoding="utf-8"
        import="com.composum.sling.core.util.XSS" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/><%
    String redirect = XSS.filter(slingRequest.getParameter("resource"));
    if (redirect == null) {
        redirect = XSS.filter(slingRequest.getParameter("target"));
    }
    if (redirect == null) {
        redirect = XSS.filter(slingRequest.getRequestPathInfo().getSuffix());
    }
%>
<form accept-charset="UTF-8" action="${slingRequest.contextPath}/j_security_check" method="post">
    <div class="composum-platform-public_panel login-panel panel panel-default">
        <div class="panel-body">

            <div class="alert alert-hidden" role="alert"></div>

            <input type="hidden" name="_charset_" value="UTF-8"/>
            <input type="hidden" name="j_validate" value="true"/>
            <input type="hidden" name="resource" value="<%=redirect%>"/>

            <div class="form-group">
                <label for="j_username" class="control-label">${cpn:i18n(slingRequest,'Username')}</label>
                <input id="j_username" name="j_username" type="text" accesskey="u" class="form-control"
                       autocorrect="off" autocapitalize="none" autofocus>
            </div>
            <div class="form-group">
                <label for="j_password" class="control-label">${cpn:i18n(slingRequest,'Password')}</label>
                <input id="j_password" name="j_password" type="password" accesskey="p" class="form-control">
            </div>

            <div class="buttons">
                <button type="submit" class="btn btn-primary login">${cpn:i18n(slingRequest,'Login')}</button>
            </div>
        </div>
    </div>
</form>
