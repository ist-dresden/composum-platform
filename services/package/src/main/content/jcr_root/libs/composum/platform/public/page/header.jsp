<%@ page import="com.composum.sling.core.CoreConfiguration" %>
<%@ page import="org.apache.commons.lang3.StringUtils" %>
<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<sling:defineObjects/>
<cpn:component id="model" type="com.composum.platform.models.simple.SimpleModel">
    <c:set var="logouturl" value="<%=StringUtils.defaultIfBlank((String) model.getService(CoreConfiguration.class).getLogoutUrl(), "/system/sling/logout.html?logout=true&GLO=true")%>"/>
    <div class="composum-platform-public_header">
        <div class="composum-platform-public_titles">
            <cpn:text tagName="h1" class="composum-platform-public_title"
                      type="rich">${model.properties.title}</cpn:text>
            <cpn:text tagName="h4" class="composum-platform-public_subtitle"
                      type="rich">${model.properties.subtitle}</cpn:text>
        </div>
        <div class="composum-platform-public_user">
            <cpn:text class="composum-platform-public_username">${model.username}</cpn:text>
            <cpn:link class="composum-platform-public_logout"
                      href="${logouturl}">${cpn:i18n(slingRequest, 'Logout')}</cpn:link>
        </div>
    </div>
</cpn:component>
