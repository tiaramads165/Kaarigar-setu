<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf-token" content="<c:out value='${csrfToken}'/>">
  <title>Demo accounts</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Kaarigar Setu &middot; Demo accounts</span></header>
  <main>
    <section class="card">
      <h2>Sign in as a demo account</h2>
      <p class="muted">Development only. These are made-up people created by <code>scripts/demo-seed.sh</code>; one click signs you in without a code. This page does not exist unless <code>DEMO_LOGIN=true</code> and <code>APP_ENV=dev</code>.</p>
      <p class="msg" id="demo-msg" role="status"></p>
    </section>
    <section class="card">
      <h3>Workers</h3>
      <c:choose>
        <c:when test="${empty workers}"><p class="muted">No demo workers yet. Run <code>./scripts/demo-seed.sh</code>.</p></c:when>
        <c:otherwise>
          <div class="table-wrap"><table><thead><tr><th>Name</th><th>Trade</th><th>Skill</th><th>Trust</th><th></th></tr></thead><tbody>
            <c:forEach items="${workers}" var="w">
              <tr><td><c:out value="${w.name}"/></td><td><c:out value="${w.trade}"/></td><td><c:out value="${w.skill}"/></td>
                  <td><c:out value="${w.trust}"/> <span class="badge trust-<c:out value='${w.trustBand}'/>"><c:out value="${w.trustBand}"/></span></td>
                  <td><button class="btn" type="button" data-role="WORKER" data-id="<c:out value='${w.id}'/>">Sign in</button></td></tr>
            </c:forEach>
          </tbody></table></div>
        </c:otherwise>
      </c:choose>
    </section>
    <section class="card">
      <h3>Employers</h3>
      <c:choose>
        <c:when test="${empty employers}"><p class="muted">No demo employers yet.</p></c:when>
        <c:otherwise>
          <div class="table-wrap"><table><thead><tr><th>Name</th><th>Verified</th><th></th></tr></thead><tbody>
            <c:forEach items="${employers}" var="e">
              <tr><td><c:out value="${e.name}"/></td><td><c:out value="${e.verified}"/></td>
                  <td><button class="btn" type="button" data-role="EMPLOYER" data-id="<c:out value='${e.id}'/>">Sign in</button></td></tr>
            </c:forEach>
          </tbody></table></div>
        </c:otherwise>
      </c:choose>
    </section>
  </main>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/common.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/demo.js" defer></script>
</body>
</html>
