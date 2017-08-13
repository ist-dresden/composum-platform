<%@page session="false" pageEncoding="utf-8"%>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0"%>
<sling:defineObjects/>
<html>
<head>
<sling:call script="head.jsp"/>
</head>
<body>
  <div class="login-page" style="background-image:url(${slingRequest.contextPath}/libs/composum/platform/security/login/bg-ist-w-rg.png)">
    <div class="top">
      <h1>Composum Platform</h1>
      <h4>an Apache Sling Application Platform</h4>
      <h4>a.s.a.p.</h4>
    </div>
    <form accept-charset="UTF-8" action="${slingRequest.contextPath}/j_security_check" method="post">
      <div class="login-panel panel panel-default">
        <div class="panel-body">
          <div class="alert alert-hidden" role="alert"></div>
          <input type="hidden" name="_charset_" value="UTF-8" />
          <input type="hidden" name="selectedAuthType" value="form" />
          <input type="hidden" name="accessmode" value="" />
          <input type="hidden" name="resource" value="" />

          <div class="form-group">
            <label for="j_username" class="control-label">Username</label>
            <input id="j_username" name="j_username" type="text" accesskey="u" class="form-control"
                   autocorrect="off" autocapitalize="none" autofocus>
          </div>
          <div class="form-group">
            <label for="j_password" class="control-label">Password</label>
            <input id="j_password" name="j_password" type="password" accesskey="p" class="form-control">
          </div>

          <div class="buttons">
            <button type="submit" class="btn btn-primary login">Login</button>
          </div>
        </div>
      </div>
    </form>
    <div class="bottom">
      <h4>a living multi site and multi application live system</h4>
      <h4>based on the <a href="http://sling.apache.org/">Apache Sling</a> framework</h4>
      <p>&copy; 2015 <a href="http://www.ist-software.com/">IST GmbH Dresden, Germany</a></p>
    </div>
  </div>
<sling:call script="script.jsp"/>
</body>
</html>
