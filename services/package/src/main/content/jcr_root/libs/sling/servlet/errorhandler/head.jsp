<%@page session="false" pageEncoding="utf-8"%>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2"%>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0"%>
<sling:defineObjects />
  <meta charset="UTF-8"/>
  <!-- responsive viewport -->
  <meta name="viewport" content="width=device-width, minimum-scale=1, maximum-scale=1, user-scalable=no"/>
  <!-- full screen application mode -->
  <meta name="mobile-web-app-capable" content="yes"/>
  <meta name="apple-mobile-web-app-capable" content="yes"/>
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/>
  <!-- library styles -->
  <link rel="stylesheet" href="${cpn:url(slingRequest,'/libs/jslibs/bootstrap/3.3.7/css/bootstrap.css')}" />
  <!-- login styles -->
  <link rel="stylesheet" href="${cpn:url(slingRequest,'/libs/sling/servlet/errorhandler/page.css')}" />