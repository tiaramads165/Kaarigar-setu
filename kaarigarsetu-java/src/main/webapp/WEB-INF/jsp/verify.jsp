<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex">
  <title>Credential verification</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Kaarigar Setu &middot; Credential check</span></header>
  <main>
    <section class="card">
      <h2>Result: <span class="badge <c:out value='${state}'/>"><c:out value="${state}"/></span></h2>
      <c:choose>
        <c:when test="${valid}"><p>This credential is <strong>authentic and current</strong>. It was signed by Kaarigar Setu and has not been altered, revoked or expired.</p></c:when>
        <c:when test="${state == 'REVOKED'}"><p>This credential was <strong>revoked</strong> and must not be relied on. <c:out value="${credential.revocationReason}"/></p></c:when>
        <c:when test="${state == 'EXPIRED'}"><p>This credential <strong>has expired</strong>. Ask the worker for a current one.</p></c:when>
        <c:when test="${state == 'INVALID_SIGNATURE'}"><p><strong>The signature does not match.</strong> The credential may have been tampered with. Do not trust it.</p></c:when>
        <c:otherwise><p>No credential with this reference exists.</p></c:otherwise>
      </c:choose>
    </section>
    <c:if test="${state != 'NOT_FOUND'}">
      <section class="card presentation <c:out value='${presentation}'/>">
        <h3>Is the person showing this the owner?</h3>
        <c:choose>
          <c:when test="${presentation == 'LIVE'}"><p><strong>Shown live.</strong> The worker's own signed-in account produced this code <c:out value="${presentationAge}"/> seconds ago. It goes stale after 5 minutes.</p></c:when>
          <c:when test="${presentation == 'EXPIRED'}"><p><strong>Not live.</strong> This code is more than 5 minutes old, so it may be a saved screenshot or a forwarded link. Ask the worker to open their dashboard and show a fresh live code.</p></c:when>
          <c:when test="${presentation == 'INVALID'}"><p><strong>Do not trust this code.</strong> It is not a valid live code for this credential. Ask the worker to show a fresh live code.</p></c:when>
          <c:otherwise><p><strong>Static link.</strong> The credential itself can be checked above, but anyone holding this link or a photo of its QR code can open this page, so it does not prove who is showing it. For a stronger check ask the worker to show a <em>live code</em> from their dashboard (valid for 5 minutes), and compare their identity in person.</p></c:otherwise>
        </c:choose>
      </section>
    </c:if>
    <c:if test="${not empty credential}">
      <section class="card">
        <h3>Credential details</h3>
        <div class="table-wrap"><table><tbody>
          <tr><th>Trade</th><td><c:out value="${credential.trade}"/></td></tr>
          <tr><th>Skill score</th><td><c:out value="${credential.skillScore}"/></td></tr>
          <c:if test="${not empty credential.skillBand}"><tr><th>Skill band</th><td><c:out value="${credential.skillBand}"/></td></tr></c:if>
          <tr><th>Trust score</th><td><c:out value="${credential.trustScore}"/><c:if test="${not empty credential.trustBand}"> (<c:out value="${credential.trustBand}"/>)</c:if></td></tr>
          <tr><th>Verified employers</th><td><c:out value="${credential.verifiedEmployerCount}"/></td></tr>
          <tr><th>Issued</th><td><c:out value="${credential.issuedAt}"/></td></tr>
          <tr><th>Valid until</th><td><c:out value="${credential.validUntil}"/></td></tr>
          <tr><th>Subject (pseudonymous)</th><td><c:out value="${credential.subject}"/></td></tr>
          <tr><th>Signing key</th><td><c:out value="${credential.keyId}"/></td></tr>
        </tbody></table></div>
        <p class="muted">The worker's name and phone number are deliberately not part of this credential.</p>
      </section>
    </c:if>
  </main>
</body>
</html>
