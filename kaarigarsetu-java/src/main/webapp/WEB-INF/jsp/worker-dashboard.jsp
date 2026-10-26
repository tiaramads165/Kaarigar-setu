<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf-token" content="<c:out value='${csrfToken}'/>">
  <title>Skill and Trust Passport</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Kaarigar Setu</span><button class="btn secondary" id="logout" type="button">Sign out</button></header>
  <main>
    <section class="card">
      <h2>Skill &amp; Trust Passport &middot; कौशल और भरोसा पासपोर्ट</h2>
      <p>Worker: <strong><c:out value="${passport.name}" default="Not available"/></strong> &middot; <c:out value="${passport.trade}"/> &middot; <c:out value="${passport.language}"/></p>
      <div class="scores">
        <div class="score"><div class="num"><c:out value="${passport.skillScore}" default="-"/></div><div class="lbl">Skill score / कौशल स्कोर</div></div>
        <div class="score"><div class="num"><c:out value="${passport.trustScore}" default="-"/></div><div class="lbl">Trust score / भरोसा स्कोर</div></div>
        <div class="score"><div class="num"><c:out value="${passport.verifiedEmployerCount}" default="0"/></div><div class="lbl">Verified employers</div></div>
      </div>
      <c:if test="${not empty passport.skillBand}">
        <p>Result: <span class="badge band-<c:out value='${passport.skillBand}'/>"><c:out value="${passport.skillBandLabel}"/></span>
          <c:if test="${passport.nearBoundary}"><span class="muted"> &middot; close to the edge of this band, so a retake could change it</span></c:if></p>
      </c:if>
      <c:if test="${not empty passport.areas}">
        <h3>Skill by area &middot; क्षेत्र के अनुसार</h3>
        <div class="table-wrap"><table><tbody>
          <c:forEach items="${passport.areas}" var="a">
            <tr><td><c:out value="${a.label}"/></td><td><progress max="100" value="<c:out value='${a.score}'/>"></progress></td><td><c:out value="${a.score}"/></td></tr>
          </c:forEach>
        </tbody></table></div>
      </c:if>
      <div class="btn-row"><a class="btn" href="<c:out value='${pageContext.request.contextPath}'/>/assessment">Take the voice assessment &middot; आकलन शुरू करें</a></div>
    </section>

    <section class="card" id="trust-card">
      <h3>Trust report &middot; भरोसा रिपोर्ट</h3>
      <c:choose>
        <c:when test="${empty passport.trustBand}"><p class="muted">Not calculated yet.</p></c:when>
        <c:otherwise>
          <p>Trust score: <strong><c:out value="${passport.trustScore}"/></strong> / 100 &middot; <span class="badge trust-<c:out value='${passport.trustBand}'/>"><c:out value="${passport.trustBandLabel}"/></span></p>
          <div class="table-wrap"><table><tbody>
            <c:if test="${not empty passport.trust.employerCount}"><tr><th>Employers who confirmed your work</th><td><c:out value="${passport.trust.employerCount}"/> (<c:out value="${passport.trust.feedbackCount}"/> confirmations)</td></tr></c:if>
            <c:if test="${not empty passport.trust.satisfaction}"><tr><th>Overall employer satisfaction</th><td><progress max="1" value="<c:out value='${passport.trust.satisfaction}'/>"></progress></td></tr></c:if>
            <c:if test="${not empty passport.trust.dimensionAverages.quality}"><tr><th>Quality of work</th><td><c:out value="${passport.trust.dimensionAverages.quality}"/> / 5</td></tr></c:if>
            <c:if test="${not empty passport.trust.dimensionAverages.punctuality}"><tr><th>Punctuality and reliability</th><td><c:out value="${passport.trust.dimensionAverages.punctuality}"/> / 5</td></tr></c:if>
            <c:if test="${not empty passport.trust.dimensionAverages.conduct}"><tr><th>Conduct and honesty</th><td><c:out value="${passport.trust.dimensionAverages.conduct}"/> / 5</td></tr></c:if>
            <c:if test="${not empty passport.trust.wouldHireAgainRate}"><tr><th>Employers who would hire you again</th><td><c:out value="${passport.trust.wouldHireAgainRate * 100}"/> %</td></tr></c:if>
            <c:if test="${not empty passport.trust.averageEmployerCredibility}"><tr><th>Credibility of those employers</th><td><c:out value="${passport.trust.averageEmployerCredibility}"/> (0 to 1)</td></tr></c:if>
            <c:if test="${not empty passport.trust.monthsSinceLastFeedback}"><tr><th>Latest confirmation</th><td><c:out value="${passport.trust.monthsSinceLastFeedback}"/> months ago</td></tr></c:if>
            <c:if test="${not empty passport.trust.consistency}"><tr><th>Consistency of feedback</th><td><c:out value="${passport.trust.consistency}"/> / 100</td></tr></c:if>
            <c:if test="${not empty passport.trust.networkStanding}"><tr><th>Network standing (PageRank)</th><td><progress max="100" value="<c:out value='${passport.trust.networkStanding}'/>"></progress> <c:out value="${passport.trust.networkStanding}"/> / 100<br><span class="muted">How strongly employers' confirmations point at you compared with other workers. It is relative, so it moves when others join, and it does not change your trust score above.</span></td></tr></c:if>
            <c:if test="${passport.trust.integrityFlags > 0}"><tr><th>Confirmations ruled fraudulent</th><td><c:out value="${passport.trust.integrityFlags}"/></td></tr></c:if>
          </tbody></table></div>
          <c:if test="${not empty passport.trust.tips}">
            <h4>How to improve it</h4>
            <ul><c:forEach items="${passport.trust.tips}" var="tip"><li><c:out value="${tip}"/></li></c:forEach></ul>
          </c:if>
        </c:otherwise>
      </c:choose>
    </section>

    <section class="card">
      <h3>Latest assessment</h3>
      <c:choose>
        <c:when test="${empty passport.answers}"><p class="muted">No assessment yet.</p></c:when>
        <c:otherwise>
          <div class="table-wrap"><table><thead><tr><th>Question</th><th>Score</th><th>Status</th></tr></thead><tbody>
            <c:forEach items="${passport.answers}" var="a">
              <tr><td><c:out value="${a.questionNo}"/></td><td><c:out value="${a.score}"/></td>
                  <td><c:choose><c:when test="${a.needsReview}"><span class="badge PENDING_REVIEW">under review</span></c:when><c:otherwise>ok</c:otherwise></c:choose></td></tr>
            </c:forEach>
          </tbody></table></div>
        </c:otherwise>
      </c:choose>
    </section>

    <section class="card" id="credential-card" data-ref="<c:out value='${passport.credentialRef}'/>" data-revoked="<c:out value='${passport.credentialRevoked}'/>">
      <h3>Your signed credential</h3>
      <p class="muted">A portable, tamper-evident passport. Anyone can scan the QR code to check it, with no login.</p>
      <div id="credential-view" hidden>
        <img class="qr" id="qr-img" alt="Credential QR code">
        <p class="muted">Status: <span class="badge" id="cred-state">-</span></p>
        <p class="muted qr-link">The QR opens: <span id="verify-url">-</span></p>
        <p class="msg err" id="verify-warn" role="alert" hidden>This address only works on this computer, so a phone cannot open it. Set PUBLIC_BASE_URL to an address your phone can reach (see the README), then issue the credential again.</p>
        <div class="btn-row"><button class="btn" id="live-btn" type="button">Show live code (valid 5 minutes)</button></div>
        <p class="muted" id="live-note" role="status">The code above is a fixed link: anyone with a photo of it can open it. A live code proves it came from your signed-in account just now.</p>
        <div class="btn-row"><a class="btn secondary" id="dl-link" href="#">Download signed JSON</a><a class="btn secondary" id="open-verify" href="#" target="_blank" rel="noopener">Open verification page</a></div>
      </div>
      <div class="btn-row"><button class="btn" id="issue-btn" type="button">Issue / refresh my credential</button></div>
      <p class="msg" id="cred-msg" role="status"></p>
    </section>

    <section class="card" id="confirmations-card">
      <h3>Attestations from employers</h3>
      <p class="muted">An employer confirms your work with a code sent to <em>their</em> phone, so you are not asked first. You can <strong>accept</strong> a confirmation (just a record) or
        <strong>dispute</strong> one that is untrue or unfair: a disputed confirmation stops counting until a moderator rules on it. You cannot hide a rating by refusing it, only by showing it is wrong.</p>
      <p class="msg" id="conf-banner" role="status" hidden></p>
      <div id="conf-list" aria-live="polite"><p class="muted">Loading your confirmations...</p></div>
      <p class="msg" id="conf-msg" role="status"></p>
    </section>
    <section class="card" id="privacy-card">
      <h3>Your data &middot; आपका डेटा</h3>
      <p class="muted">Deleting removes your profile, voice transcripts, scores, credentials and the endorsements about you. You can delete it at any time. This cannot be undone. Type <strong>ERASE MY DATA</strong> to confirm.</p>
      <label for="erase-confirm">Confirmation phrase</label>
      <input id="erase-confirm" type="text" autocomplete="off" maxlength="40">
      <div class="btn-row"><button class="btn danger" id="erase-btn" type="button" data-endpoint="/api/worker/erase">Delete my data</button></div>
      <p class="msg" id="erase-msg" role="status"></p>
    </section>
  </main>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/common.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/worker.js" defer></script>
</body>
</html>
