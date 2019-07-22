<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component id="model" type="com.composum.platform.models.simple.SimpleModel">
    <div class="composum-platform-public_footer">
        <cpn:text tagName="h4" class="composum-platform-public_strong" type="rich">${model.properties.footer}</cpn:text>
        <cpn:text tagName="p" class="composum-platform-public_text" type="rich">${model.properties.copyright}</cpn:text>
    </div>
</cpn:component>
