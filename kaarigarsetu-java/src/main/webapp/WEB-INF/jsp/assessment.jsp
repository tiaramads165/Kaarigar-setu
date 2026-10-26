<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf-token" content="<c:out value='${csrfToken}'/>">
  <title>Voice assessment</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Voice assessment &middot; आवाज़ से आकलन</span><a class="btn secondary" href="<c:out value='${pageContext.request.contextPath}'/>/worker-dashboard">Back</a></header>
  <main>
    <section class="card" id="intro">
      <p>You will hear 10 questions (about 10 to 15 minutes), from simple to harder. After each one, tap the microphone, answer by speaking, and tap again to stop.<br>
         आपसे 10 सवाल पूछे जाएंगे (करीब 10 से 15 मिनट), आसान से कठिन की ओर। हर सवाल के बाद माइक दबाकर बोलें और फिर से दबाकर रोकें।</p>
      <div class="btn-row"><button class="btn" id="start" type="button">Start &middot; शुरू करें</button></div>
      <p class="msg" id="msg" role="status"></p>
    </section>
    <section class="card" id="stage" hidden>
      <div id="log"></div>
      <div class="center" id="dock"></div>
    </section>
  </main>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/common.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/wav.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/assessment.js" defer></script>
</body>
</html>
