/* Sign-in (phone + OTP), registration with consent, and public credential lookup. */
(function () {
  'use strict';
  var KS = window.KS, $ = KS.byId;

  // ---- login ----
  // A browser the server does not know yet first solves a small puzzle (static/js/pow.js): the server answers 428 with the puzzle, we solve it and ask again.
  // A browser that has signed in before is recognised by its cookie and is never asked. A few rounds at most, so a misbehaving server cannot loop us.
  function requestCode(pow, round) {
    var body = { role: $('login-role').value, phone: $('login-phone').value };
    if (pow) body.pow = pow;
    return KS.api('POST', '/api/auth/otp/request', body).then(function (r) {
      if (r.status === 428 && r.data && r.data.challenge && round < 3 && KS.solvePow) {
        KS.say($('login-msg'), 'Checking that this is a person and not a script...', '');
        return KS.solvePow(r.data.challenge, r.data.bits).then(function (counter) {
          return requestCode({ challenge: r.data.challenge, counter: counter }, round + 1);
        });
      }
      return r;
    });
  }
  $('login-send').addEventListener('click', function () {
    var button = $('login-send');
    KS.say($('login-msg'), '');
    button.disabled = true;
    requestCode(null, 0).then(function (r) {
      button.disabled = false;
      if (!r.ok) return KS.say($('login-msg'), r.status === 428 ? 'Could not complete the security check. Please reload the page and try again.' : KS.errorText(r), 'err');
      $('login-phone-stage').hidden = true;
      $('login-code-stage').hidden = false;
      KS.say($('login-msg'), 'If that number is registered, a code has been sent.', 'ok');
      $('login-code').focus();
    });
  });
  $('login-back').addEventListener('click', function () {
    $('login-code-stage').hidden = true;
    $('login-phone-stage').hidden = false;
    $('login-code').value = '';
  });
  $('login-verify').addEventListener('click', function () {
    KS.api('POST', '/api/auth/otp/verify', { role: $('login-role').value, phone: $('login-phone').value, code: $('login-code').value }).then(function (r) {
      if (!r.ok) return KS.say($('login-msg'), KS.errorText(r), 'err');
      location.assign(r.data.role === 'EMPLOYER' ? '/employer-dashboard' : '/worker-dashboard');
    });
  });

  // ---- registration ----
  function syncRole() {
    var worker = $('reg-role').value === 'WORKER';
    $('worker-fields').hidden = !worker;
    $('consent-text-worker').hidden = !worker;
    $('consent-text-employer').hidden = worker;
  }
  $('reg-role').addEventListener('change', syncRole);
  syncRole();

  $('reg-submit').addEventListener('click', function () {
    var worker = $('reg-role').value === 'WORKER';
    var body = { name: $('reg-name').value, phone: $('reg-phone').value, consent: $('reg-consent').checked };
    if (worker) { body.trade = $('reg-trade').value; body.language = $('reg-language').value; }
    KS.api('POST', worker ? '/api/worker/register' : '/api/employer/register', body).then(function (r) {
      if (!r.ok) return KS.say($('reg-msg'), r.data && r.data.error === 'conflict' ? 'This number is already registered. Please sign in.' : KS.errorText(r), 'err');
      KS.say($('reg-msg'), 'Registered. You can sign in above with your phone number.', 'ok');
      $('login-role').value = worker ? 'WORKER' : 'EMPLOYER';
      $('login-phone').value = $('reg-phone').value;
    });
  });

  // ---- public lookup ----
  $('verify-go').addEventListener('click', function () {
    var ref = $('verify-ref').value.trim().replace(/^.*\//, '');
    if (/^[0-9a-fA-F-]{36}$/.test(ref)) location.assign('/verify/' + ref);
  });
})();
