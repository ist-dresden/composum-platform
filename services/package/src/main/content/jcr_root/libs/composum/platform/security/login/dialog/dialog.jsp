<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<sling:defineObjects/>
<div id="composum-platform-commons-login-dialog" class="composum-platform-dialog dialog modal fade"
     role="dialog" aria-labelledby="${cpn:i18n(slingRequest,'Login')}" aria-hidden="true"
     data-message="${cpn:i18n(slingRequest,'Unauthorized - probably session expired - refresh login!')}">
    <div class="modal-dialog">
        <div class="modal-content form-panel">
            <form accept-charset="UTF-8" action="${slingRequest.contextPath}/j_security_check" method="post">
                <div class="modal-header composum-platform-dialog_header">
                    <button type="button" class="composum-platform-dialog_button-close fa fa-close"
                            data-dismiss="modal" aria-label="Close"></button>
                    <h4 class="modal-title composum-platform-dialog_dialog-title">
                        ${cpn:i18n(slingRequest,'Composum Platform')}
                    </h4>
                </div>
                <div class="modal-body composum-platform-dialog_content">
                    <div class="composum-platform-dialog_messages messages">
                        <div class="panel panel-warning">
                            <div class="panel-heading"></div>
                            <div class="panel-body hidden"></div>
                        </div>
                    </div>
                    <input type="hidden" name="_charset_" value="UTF-8"/>
                    <input type="hidden" name="selectedAuthType" value="form"/>
                    <input type="hidden" name="accessmode" value=""/>
                    <input type="hidden" name="resource" value=""/>

                    <div class="form-group">
                        <label class="control-label">${cpn:i18n(slingRequest,'Username')}</label>
                        <input name="j_username" type="text" accesskey="u" class="form-control"
                               autocorrect="off" autocapitalize="none" autofocus>
                    </div>
                    <div class="form-group">
                        <label class="control-label">${cpn:i18n(slingRequest,'Password')}</label>
                        <input name="j_password" type="password" accesskey="p" class="form-control">
                    </div>
                </div>
                <div class="modal-footer composum-platform-dialog_footer">
                    <div class="composum-platform-dialog_hints">
                    </div>
                    <button type="button" class="composum-platform-dialog_button-cancel btn btn-default"
                            data-dismiss="modal">${cpn:i18n(slingRequest,'Cancel')}</button>
                    <button type="submit"
                            class="composum-platform-dialog_button-submit btn btn-primary">${cpn:i18n(slingRequest,'Login')}</button>
                </div>
            </form>
        </div>
    </div>
</div>