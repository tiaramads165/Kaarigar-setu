/* Employer dashboard: attest a worker (OTP step-up) and verify credentials. */
(function () {
  'use strict';
  var KS = window.KS, $ = KS.byId;
  var attestationId = null;

  var today = new Date().toISOString().slice(0, 10);
  $('att-date').max = today;
  $('att-date').value = today;

  $('att-request').addEventListener('click', function () {
    var days = $('att-days').value;
    var hire = $('att-hire').value;
    var q = $('att-quality').value, p = $('att-punctuality').value, c = $('att-conduct').value;
    if (!q || !p || !c) return KS.say($('att-msg'), 'Please rate the quality, punctuality and conduct (1 to 5).', 'err');
    var body = {
      workerPhone: $('att-phone').value, jobRole: $('att-role').value, workDate: $('att-date').value,
      durationDays: days === '' ? null : parseInt(days, 10), wouldHireAgain: hire === '' ? null : hire === 'true',
      qualityRating: parseInt(q, 10), punctualityRating: parseInt(p, 10), conductRating: parseInt(c, 10)
    };
    KS.say($('att-msg'), '');
    KS.api('POST', '/api/attestation/request-otp', body).then(function (r) {
      if (!r.ok) return KS.say($('att-msg'), KS.errorText(r), 'err');
      attestationId = r.data.attestationId;
      $('att-form').hidden = true;
      $('att-code-stage').hidden = false;
      KS.say($('att-msg'), 'Code sent to your phone.', 'ok');
    });
  });

  $('att-verify').addEventListener('click', function () {
    KS.api('POST', '/api/attestation/verify-otp', { attestationId: attestationId, code: $('att-code').value }).then(function (r) {
      if (!r.ok) return KS.say($('att-msg'), KS.errorText(r), 'err');
      var text = r.data.flagged ? 'Recorded. It has been sent for a quick manual check before it counts.' : 'Confirmed. Thank you: this worker\'s trust score has been updated.';
      KS.say($('att-msg'), text, 'ok');
      setTimeout(function () { location.reload(); }, 1800);
    });
  });

  function showVerification(result) {
    var d = result.data || {};
    var band = d.credential && d.credential.skillBand ? ' \u00b7 skill band: ' + d.credential.skillBand.replace('_', ' ').toLowerCase() : '';
    KS.say($('vr-msg'), 'Result: ' + (d.state || 'UNKNOWN') + (d.valid ? ' (authentic and current)' : '') + band, d.valid ? 'ok' : 'err');
  }
  $('vr-go').addEventListener('click', function () {
    var ref = $('vr-ref').value.trim().replace(/^.*\//, '');
    if (!/^[0-9a-fA-F-]{36}$/.test(ref)) return KS.say($('vr-msg'), 'Enter a valid credential reference.', 'err');
    KS.api('GET', '/api/credential/' + ref + '/verify').then(showVerification);
  });
  $('vr-check').addEventListener('click', function () {
    KS.api('POST', '/api/credential/verify', { payload: $('vr-payload').value, signature: $('vr-sig').value.trim() }).then(showVerification);
  });
})();
