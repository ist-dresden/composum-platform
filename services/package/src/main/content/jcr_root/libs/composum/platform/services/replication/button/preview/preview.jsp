<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<button type="button" class="composum-platform-replication-button btn btn-default release-preview"
        title="${cpn:i18n(slingRequest,'Switch Preview Release to the selected release')}..."><i
        class="fa fa-eye"></i>${cpn:i18n(slingRequest,'Preview')}</button>
