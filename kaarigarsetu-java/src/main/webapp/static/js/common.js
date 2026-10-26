/* Shared helpers. Vanilla JS, strict CSP (no inline script): all text goes through textContent, never innerHTML. */
(function () {
  'use strict';
  var meta = document.querySelector('meta[name="csrf-token"]');

  function api(method, path, body) {
    var headers = { 'X-CSRF-Token': meta ? meta.getAttribute('content') : '' };
    var options = { method: method, credentials: 'same-origin', headers: headers };
    if (body instanceof Blob) { headers['Content-Type'] = 'audio/wav'; options.body = body; }
    else if (body !== undefined) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
    return fetch(path, options).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        return { ok: res.ok, status: res.status, data: data };
      });
    }, function () { return { ok: false, status: 0, data: { error: 'network' } }; });
  }

  var MESSAGES = {
    invalid_request: 'Please check the details and try again.',
    invalid_otp: 'That code is wrong, expired or locked. Request a new one.',
    invalid_credentials: 'Invalid credentials.',
    consent_required: 'Consent is required to continue.',
    rate_limited: 'Too many attempts. Please wait a little and try again.',
    proof_of_work_required: 'Could not complete the security check. Please reload the page and try again.',
    unauthenticated: 'Your session has ended. Please sign in again.',
    forbidden: 'You are not allowed to do that.',
    csrf: 'Security check failed. Please reload the page.',
    not_found: 'Not found.',
    conflict: 'That cannot be done right now.',
    duplicate: 'This worker has already been attested by you.',
    low_trust: 'Not enough trust yet for that action.',
    unintelligible: 'We could not understand that recording. Please speak clearly and try again.',
    code_not_heard: 'We did not hear the number. Please say it clearly at the end of your answer and try again. · नंबर सुनाई नहीं दिया, जवाब के अंत में साफ़ बोलकर फिर कोशिश करें।',
    challenge_required: 'The number for this question has changed. Please say the new number at the end of your answer.',
    upstream_unavailable: 'A service is temporarily unavailable. Please try again shortly.',
    network: 'Network problem. Please try again.',
    internal: 'Something went wrong. Please try again.'
  };

  function errorText(result) {
    return MESSAGES[result && result.data && result.data.error] || MESSAGES.internal;
  }

  function say(el, text, kind) {
    el.textContent = text;
    el.className = 'msg' + (kind ? ' ' + kind : '');
  }

  function byId(id) { return document.getElementById(id); }

  function logout() {
    api('POST', '/api/auth/logout', {}).then(function () { location.assign('/'); });
  }
  var logoutButton = byId('logout');
  if (logoutButton) logoutButton.addEventListener('click', logout);

  // DPDP erasure button (worker and employer dashboards): needs the typed phrase, then the account is gone.
  var eraseButton = byId('erase-btn');
  if (eraseButton) eraseButton.addEventListener('click', function () {
    var phrase = byId('erase-confirm').value.trim();
    if (phrase !== 'ERASE MY DATA') return say(byId('erase-msg'), 'Type ERASE MY DATA exactly to confirm.', 'err');
    api('POST', eraseButton.getAttribute('data-endpoint'), { confirm: phrase }).then(function (r) {
      if (!r.ok) return say(byId('erase-msg'), errorText(r), 'err');
      location.assign('/');
    });
  });

  window.KS = { api: api, errorText: errorText, say: say, byId: byId };
})();
