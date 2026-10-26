<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isErrorPage="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Something went wrong</title>
<link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css"></head>
<body><header class="top"><span class="brand">Kaarigar Setu</span></header>
<main><section class="card"><h2>That page is not available</h2><p class="muted">Please go back and try again.</p>
<a class="btn" href="<c:out value='${pageContext.request.contextPath}'/>/">Home</a></section></main></body></html>
