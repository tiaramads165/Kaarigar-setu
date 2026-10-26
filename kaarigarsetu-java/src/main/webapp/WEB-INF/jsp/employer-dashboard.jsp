<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf-token" content="<c:out value='${csrfToken}'/>">
  <title>Employer Dashboard</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Kaarigar Setu &middot; Employer</span><button class="btn secondary" id="logout" type="button">Sign out</button></header>
  <main>
    <section class="card">
      <h2>Attest a worker's completed work</h2>
      <div id="att-form">
        <label for="att-phone">Worker's mobile number</label><input id="att-phone" type="tel" inputmode="numeric" maxlength="14">
        <label for="att-role">Work done (role)</label><input id="att-role" type="text" maxlength="80" placeholder="e.g. house wiring">
        <div class="row">
          <div><label for="att-days">Duration (days)</label><input id="att-days" type="number" min="0" max="3650"></div>
          <div><label for="att-date">Work date</label><input id="att-date" type="date"></div>
          <div><label for="att-hire">Would you hire again?</label><select id="att-hire"><option value="">Prefer not to say</option><option value="true">Yes</option><option value="false">No</option></select></div>
        </div>
        <h3>How was the work? &middot; काम कैसा रहा?</h3>
        <p class="muted">1 = poor, 5 = excellent. Your honest answers decide how much this confirmation helps the worker, and employers who rate everyone the same count for less.</p>
        <div class="row">
          <div><label for="att-quality">Quality of work</label><select id="att-quality"><option value="">Choose</option><option>5</option><option>4</option><option>3</option><option>2</option><option>1</option></select></div>
          <div><label for="att-punctuality">Punctuality and reliability</label><select id="att-punctuality"><option value="">Choose</option><option>5</option><option>4</option><option>3</option><option>2</option><option>1</option></select></div>
          <div><label for="att-conduct">Conduct and honesty</label><select id="att-conduct"><option value="">Choose</option><option>5</option><option>4</option><option>3</option><option>2</option><option>1</option></select></div>
        </div>
        <div class="btn-row"><button class="btn" id="att-request" type="button">Send me a code</button></div>
      </div>
      <div id="att-code-stage" hidden>
        <p class="muted">A 6-digit code was sent to your phone. Enter it to confirm this attestation.</p>
        <label for="att-code">Code</label><input id="att-code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code">
        <div class="btn-row"><button class="btn" id="att-verify" type="button">Confirm attestation</button></div>
      </div>
      <p class="msg" id="att-msg" role="status"></p>
    </section>

    <section class="card">
      <h3>Verify a worker's credential</h3>
      <p class="muted">Paste the credential payload and signature from the worker's downloaded JSON, or enter its reference. The server re-checks everything; nothing you paste is trusted.</p>
      <label for="vr-ref">Credential reference</label><input id="vr-ref" type="text" maxlength="80">
      <div class="btn-row"><button class="btn secondary" id="vr-go" type="button">Check reference</button></div>
      <label for="vr-payload">Payload (JSON)</label><textarea id="vr-payload" rows="4"></textarea>
      <label for="vr-sig">Signature</label><textarea id="vr-sig" rows="2"></textarea>
      <div class="btn-row"><button class="btn secondary" id="vr-check" type="button">Verify pasted credential</button></div>
      <p class="msg" id="vr-msg" role="status"></p>
    </section>

    <section class="card">
      <h3>My attestations</h3>
      <c:choose>
        <c:when test="${empty attestations}"><p class="muted">None yet.</p></c:when>
        <c:otherwise>
          <div class="table-wrap"><table><thead><tr><th>Work</th><th>Date</th><th>Hire again</th><th>Status</th></tr></thead><tbody>
            <c:forEach items="${attestations}" var="a">
              <tr><td><c:out value="${a.jobRole}"/></td><td><c:out value="${a.workDate}"/></td><td><c:out value="${a.wouldHireAgain}" default="-"/></td>
                  <td><span class="badge <c:out value='${a.status}'/>"><c:out value="${a.status}"/></span></td></tr>
            </c:forEach>
          </tbody></table></div>
        </c:otherwise>
      </c:choose>
    </section>
    <section class="card" id="privacy-card">
      <h3>Your data &middot; आपका डेटा</h3>
      <p class="muted">Deleting removes your name and phone number. Endorsements you already gave stay, because they belong to the workers. You can delete it at any time. This cannot be undone. Type <strong>ERASE MY DATA</strong> to confirm.</p>
      <label for="erase-confirm">Confirmation phrase</label>
      <input id="erase-confirm" type="text" autocomplete="off" maxlength="40">
      <div class="btn-row"><button class="btn danger" id="erase-btn" type="button" data-endpoint="/api/employer/erase">Delete my data</button></div>
      <p class="msg" id="erase-msg" role="status"></p>
    </section>
  </main>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/common.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/employer.js" defer></script>
</body>
</html>
