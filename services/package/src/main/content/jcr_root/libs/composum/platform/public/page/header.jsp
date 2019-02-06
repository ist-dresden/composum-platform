<%@page session="false" pageEncoding="utf-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component id="model" type="com.composum.platform.models.simple.SimpleModel">
    <div class="composum-platform-public_header">
        <cpn:text tagName="h1" class="composum-platform-public_title" type="rich">${model.properties.title}</cpn:text>
        <cpn:text tagName="h4" class="composum-platform-public_subtitle"
                  type="rich">${model.properties.subtitle}</cpn:text>
    </div>
</cpn:component>
