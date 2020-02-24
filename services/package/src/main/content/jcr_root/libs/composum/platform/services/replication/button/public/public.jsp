<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<button type="button" class="composum-platform-replication-button btn btn-default release-public"
        title="${cpn:i18n(slingRequest,'Switch Public Release to the selected release (publish)')}...">
    <i class="fa fa-globe"></i>${cpn:i18n(slingRequest,'Public')}</button>
