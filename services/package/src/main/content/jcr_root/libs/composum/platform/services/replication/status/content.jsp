<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<sling:defineObjects/>
<cpn:component var="model" type="com.composum.sling.platform.staging.replication.model.ReplicationStatus"
               scope="request">
    <div data-path="${model.path}" data-stage="${model.stage}" data-state="${model.replicationState.json}"
         class="composum-platform-replication-status_view panel panel-default">
        <div class="composum-platform-replication-status_heading panel-heading">
            <h3 class="composum-platform-replication-status_stage">
                <div class="composum-platform-replication-status_title">${cpn:i18n(slingRequest,model.stage)}</div>
                <div class="composum-platform-replication-status_state">
                    <div class="badge ${model.replicationState.state}">
                            ${cpn:i18n(slingRequest,model.replicationState.state)}
                    </div>
                </div>
                <c:if test="${!model.replicationState.synchronized}">
                    <div class="composum-platform-replication-status_progress">
                        <div class="progress">
                            <div class="progress-bar progress-bar-striped" role="progressbar"
                                 aria-valuenow="${model.replicationState.progress}" aria-valuemin="0"
                                 aria-valuemax="100" style="width: ${model.replicationState.progress}%;">
                                    ${model.replicationState.progress}%
                            </div>
                        </div>
                    </div>
                </c:if>
            </h3>
        </div>
        <ul class="composum-platform-replication-status_process-list list-group">
            <c:forEach items="${model.replicationProcessState}" var="process">
                <li class="composum-platform-replication-status_process list-group-item" data-state="${process.json}">
                    <h4 class="composum-platform-replication-status_title">
                        <i class="composum-platform-replication-status_enabled fa fa-toggle-${process.enabled?'on':'off'}"></i>
                            ${cpn:text(process.title)}
                    </h4>
                    <div class="composum-platform-replication-status_general">
                        <div class="composum-platform-replication-status_state"><span
                                class="badge ${process.state}">${cpn:i18n(slingRequest,process.state)}</span>
                        </div>
                        <div class="composum-platform-replication-status_type"><span
                                class="value">${cpn:i18n(slingRequest,'In-Place')}</span></div>
                        <c:if test="${process.synchronized}">
                            <div class="composum-platform-replication-status_last-replication"><span
                                    class="key">${cpn:i18n(slingRequest,'replication')} :</span><span
                                    class="value">${process.lastReplication}</span></div>
                        </c:if>
                        <c:if test="${!process.synchronized && process.enabled}">
                            <div class="composum-platform-replication-status_replication-start"><span
                                    class="key">${cpn:i18n(slingRequest,'started')} :</span><span
                                    class="value">${process.startedAt}</span></div>
                        </c:if>
                    </div>
                    <c:if test="${!process.synchronized && process.enabled}">
                        <div class="composum-platform-replication-status_progress">
                            <div class="progress">
                                <div class="progress-bar progress-bar-striped" role="progressbar"
                                     aria-valuenow="${process.progress}" aria-valuemin="0" aria-valuemax="100"
                                     style="width: ${process.progress}%;">${process.progress}%
                                </div>
                            </div>
                        </div>
                    </c:if>
                </li>
            </c:forEach>
        </ul>
    </div>
</cpn:component>
