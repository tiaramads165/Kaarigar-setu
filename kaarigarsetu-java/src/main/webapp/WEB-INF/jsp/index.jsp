<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="true" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf-token" content="<c:out value='${csrfToken}'/>">
  <title>Kaarigar Setu</title>
  <link rel="stylesheet" href="<c:out value='${pageContext.request.contextPath}'/>/static/css/app.css">
</head>
<body>
  <header class="top"><span class="brand">Kaarigar Setu &middot; कारीगर सेतु</span></header>
  <main>
    <section class="card" id="login-card">
      <h2>Sign in &middot; साइन इन</h2>
      <div id="login-phone-stage">
        <label for="login-role">I am a &middot; मैं हूं</label>
        <select id="login-role"><option value="WORKER">Worker &middot; कारीगर</option><option value="EMPLOYER">Employer &middot; नियोक्ता</option></select>
        <label for="login-phone">Mobile number &middot; मोबाइल नंबर</label>
        <input id="login-phone" type="tel" inputmode="numeric" maxlength="14" autocomplete="tel" placeholder="10-digit number">
        <div class="btn-row"><button class="btn" id="login-send" type="button">Send code &middot; कोड भेजें</button></div>
      </div>
      <div id="login-code-stage" hidden>
        <label for="login-code">6-digit code &middot; 6 अंकों का कोड</label>
        <input id="login-code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code">
        <div class="btn-row"><button class="btn" id="login-verify" type="button">Sign in &middot; साइन इन</button>
          <button class="btn secondary" id="login-back" type="button">Back</button></div>
      </div>
      <p class="msg" id="login-msg" role="status"></p>
    </section>

    <section class="card" id="register-card">
      <h2>New here? Register &middot; नया खाता</h2>
      <label for="reg-role">I am a &middot; मैं हूं</label>
      <select id="reg-role"><option value="WORKER">Worker &middot; कारीगर</option><option value="EMPLOYER">Employer &middot; नियोक्ता</option></select>
      <label for="reg-name">Name &middot; नाम</label>
      <input id="reg-name" type="text" maxlength="120" autocomplete="name">
      <label for="reg-phone">Mobile number &middot; मोबाइल नंबर</label>
      <input id="reg-phone" type="tel" inputmode="numeric" maxlength="14" autocomplete="tel">
      <div id="worker-fields" class="row">
        <div><label for="reg-trade">Trade &middot; काम</label>
          <select id="reg-trade"><c:forEach items="${trades}" var="t"><option value="<c:out value='${t.key}'/>"><c:out value='${t.name}'/> &middot; <c:out value='${t.nameHi}'/></option></c:forEach></select></div>
        <div><label for="reg-language">Language &middot; भाषा</label>
          <select id="reg-language"><option value="hi">हिन्दी</option><option value="en">English</option></select></div>
      </div>
      <p class="muted" id="consent-text-worker">हम आपके बोले हुए जवाबों को टेक्स्ट में बदलकर स्कोर निकालेंगे। आपकी रिकॉर्डिंग सेव नहीं की जाती। आपका फ़ोन नंबर एन्क्रिप्ट करके रखा जाता है। आप कभी भी अपना डेटा मिटाने के लिए कह सकते हैं।<br>
        We transcribe and score your spoken answers. Recordings are not stored. Your phone number is stored encrypted. You can ask us to delete your data at any time.</p>
      <p class="muted" id="consent-text-employer" hidden>We store your phone number (encrypted) and the attestations you give, to verify workers' experience. You can ask us to delete your data at any time.</p>
      <label class="check"><input id="reg-consent" type="checkbox"><span>I have read this and agree &middot; मैंने पढ़ लिया है और मैं सहमत हूं</span></label>
      <div class="btn-row"><button class="btn" id="reg-submit" type="button">Register &middot; रजिस्टर करें</button></div>
      <p class="msg" id="reg-msg" role="status"></p>
    </section>

    <section class="card">
      <h2>Verify a credential</h2>
      <p class="muted">No login needed. Enter the credential reference from a worker's QR code.</p>
      <label for="verify-ref">Credential reference</label>
      <input id="verify-ref" type="text" maxlength="80" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx">
      <div class="btn-row"><button class="btn secondary" id="verify-go" type="button">Check</button></div>
    </section>
  </main>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/common.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/pow.js" defer></script>
  <script src="<c:out value='${pageContext.request.contextPath}'/>/static/js/auth.js" defer></script>
</body>
</html>
