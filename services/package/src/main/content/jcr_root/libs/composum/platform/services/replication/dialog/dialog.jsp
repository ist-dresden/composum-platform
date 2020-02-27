<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.model.ReplicationStatus"
               path="${cpn:filter(slingRequest.requestPathInfo.suffix)}" scope="request">
    <div class="composum-platform-replication-dialog dialog modal fade" role="dialog" aria-hidden="true"
         data-path="${model.path}" data-stage="${model.stage}">
        <div class="modal-dialog modal-lg">
            <div class="modal-content form-panel default">
                <div class="modal-header">
                    <h4 class="modal-title"><span>${cpn:i18n(slingRequest,'Publishing')} <span
                            class="composum-platform-replication-dialog_stage label label-primary">${cpn:i18n(slingRequest,model.stage)}</span>
                    </h4>
                </div>
                <div class="composum-platform-replication-dialog_body modal-body">
                    <div class="composum-platform-replication-dialog_messages messages">
                        <div class="panel panel-warning hidden">
                            <div class="panel-heading"></div>
                            <div class="panel-body hidden"></div>
                        </div>
                    </div>
                    <input name="_charset_" type="hidden" value="UTF-8"/>
                    <div class="composum-platform-replication-dialog_content">
                        <sling:call script="target.jsp"/><%-- extension hook --%>
                        <sling:include path="${model.path}"
                                       resourceType="composum/platform/services/replication/status"/>
                    </div>
                </div>
                <div class="composum-platform-replication-dialog_footer modal-footer buttons">
                    <button type="button"
                            class="abort button-left btn btn-danger hidden">${cpn:i18n(slingRequest,'Abort')}</button>
                    <button type="button" class="cancel btn btn-default"
                            data-dismiss="modal">${cpn:i18n(slingRequest,'Cancel')}</button>
                    <button type="button" class="publish btn btn-primary">${cpn:i18n(slingRequest,'Publish')}</button>
                    <button type="button" class="exit btn btn-primary hidden">${cpn:i18n(slingRequest,'Close')}</button>
                </div>
            </div>
        </div>
    </div>
</cpn:component>
