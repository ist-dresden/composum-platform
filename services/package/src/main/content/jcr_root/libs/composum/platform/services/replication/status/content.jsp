<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.model.ReplicationStatus"
               scope="request">
    <div data-path="${model.path}" data-stage="${model.stage}" data-release="${model.release.path}"
         data-state="${model.replicationState.jsonSummary}"
         class="composum-platform-replication-status_view panel panel-default">
        <div id="${model.domId}_${model.stage}_toggle"
             class="composum-platform-replication-status_heading panel-heading">
            <h4 class="composum-platform-replication-status_stage">
                <a role="button" data-toggle="collapse" href="#${model.domId}_${model.stage}_panel"
                   aria-expanded="true" aria-controls="${model.domId}_${model.stage}_panel"
                   class="composum-platform-replication-status_toggle">
                    <div class="composum-platform-replication-status_title">${cpn:i18n(slingRequest,model.stage)}</div>
                    <div class="composum-platform-replication-status_state"><span
                            class="badge badge-pill ${model.replicationState.state}">${cpn:i18n(slingRequest,model.replicationState.state)}</span>
                    </div>
                    <div class="composum-platform-replication-status_progress">
                        <div class="progress ${model.replicationState.synchronized?' hidden':''}">
                            <div class="progress-bar progress-bar-striped" role="progressbar"
                                 aria-valuenow="${model.replicationState.progress}" aria-valuemin="0"
                                 aria-valuemax="100" style="width: ${model.replicationState.progress}%;">
                                    ${model.replicationState.progress}%
                            </div>
                        </div>
                    </div>
                    <div class="composum-platform-replication-status_toolbar">
                        <div class="composum-platform-replication-status_abort${model.replicationState.running?'':' hidden'}">
                            <button type="button" class="btn btn-danger">${cpn:i18n(slingRequest,'Abort')}</button>
                        </div>
                        <div class="composum-platform-replication-status_synchronize${model.replicationState.running?' hidden':''}">
                            <button type="button"
                                    class="btn btn-primary">${cpn:i18n(slingRequest,'Synchronize')}</button>
                        </div>
                    </div>
                </a>
            </h4>
        </div>
        <div id="${model.domId}_${model.stage}_panel" aria-labelledby="${model.domId}_${model.stage}_toggle"
             class="panel-collapse collapse in" aria-expanded="true" role="tabpanel">
            <ul class="composum-platform-replication-status_process-list list-group">
                <c:forEach items="${model.replicationProcessState}" var="process">
                    <li class="composum-platform-replication-status_process list-group-item"
                        data-state="${process.json}">
                        <h5 class="composum-platform-replication-status_title">
                            <i class="composum-platform-replication-status_enabled fa fa-toggle-${process.enabled?'on':'off'}"></i>
                                ${cpn:text(process.title)}
                        </h5>
                        <div class="composum-platform-replication-status_general">
                            <div class="composum-platform-replication-status_state"><span
                                    class="badge badge-pill ${process.state}">${cpn:i18n(slingRequest,process.state)}</span>
                            </div>
                            <div class="composum-platform-replication-status_type"><span
                                    class="value">${cpn:i18n(slingRequest,process.type)}</span></div>
                            <cpn:div test="${process.synchronized}"
                                     class="composum-platform-replication-status_timestamp"><span
                                    class="key">${cpn:i18n(slingRequest,'last replication')}</span> : <span
                                    class="value">${process.lastReplication}</span></cpn:div>
                            <cpn:div test="${!process.synchronized && process.enabled}"
                                     class="composum-platform-replication-status_timestamp"><span
                                    class="key">${cpn:i18n(slingRequest,'started at')}</span> : <span
                                    class="value">${process.startedAt}</span></cpn:div>
                        </div>
                        <cpn:div test="${!process.synchronized && process.enabled}"
                                 class="composum-platform-replication-status_progress">
                            <div class="progress">
                                <div class="progress-bar progress-bar-striped" role="progressbar"
                                     aria-valuenow="${process.progress}" aria-valuemin="0" aria-valuemax="100"
                                     style="width: ${process.progress}%;">${process.progress}%
                                </div>
                            </div>
                        </cpn:div>
                    </li>
                </c:forEach>
            </ul>
        </div>
    </div>
</cpn:component>
