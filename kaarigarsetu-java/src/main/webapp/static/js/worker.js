/* Worker dashboard: issue the credential, show its QR code and state. */
(function () {
  'use strict';
  var KS = window.KS, $ = KS.byId;
  var card = $('credential-card');

  function show(ref) {
    currentRef = ref;
    if (liveTimer) stopLive('');
    $('credential-view').hidden = false;
    $('qr-img').src = '/api/credential/' + ref + '/qr';
    $('dl-link').href = '/api/credential/' + ref + '/download';
    $('open-verify').href = '/verify/' + ref;
    KS.api('GET', '/api/credential/mine').then(function (r) {
      var mine = (r.data && r.data.credentials) || [], link = '';
      for (var i = 0; i < mine.length; i++) if (mine[i].ref === ref) link = mine[i].verifyUrl;
      $('verify-url').textContent = link || '-';
      $('verify-warn').hidden = !/^https?:\/\/(localhost|127\.|0\.0\.0\.0|\[::1\])/i.test(link);   // a phone's own 'localhost' is the phone, not this computer
    });
    KS.api('GET', '/api/credential/' + ref + '/verify').then(function (r) {
      var state = (r.data && r.data.state) || 'UNKNOWN';
      $('cred-state').textContent = state;
      $('cred-state').className = 'badge ' + state;
    });
  }

  // Live code: a QR that carries a 5-minute presentation code minted by this signed-in session (a screenshot of it goes stale on its own).
  var liveTimer = null;
  function stopLive(message) {
    if (liveTimer) { clearInterval(liveTimer); liveTimer = null; }
    var ref = card.getAttribute('data-ref') || currentRef;
    if (ref) $('qr-img').src = '/api/credential/' + ref + '/qr';
    $('live-note').textContent = message;
    $('live-btn').disabled = false;
  }
  var currentRef = '';
  $('live-btn').addEventListener('click', function () {
    var ref = currentRef || card.getAttribute('data-ref');
    if (!ref) return;
    $('live-btn').disabled = true;
    $('qr-img').src = '/api/credential/' + ref + '/live-qr?t=' + Date.now();      // a new image each time: never cached
    var until = Date.now() + 5 * 60 * 1000;
    function tick() {
      var left = Math.max(0, Math.round((until - Date.now()) / 1000));
      if (left === 0) return stopLive('The live code expired. The fixed code is shown again; press the button for a new live code.');
      $('live-note').textContent = 'LIVE code. Show this screen now. It stops counting as live in ' + Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + '.';
    }
    tick();
    liveTimer = setInterval(tick, 1000);
  });

  // Confirmations about me: accept, or dispute with a reason. Built with textContent only (strict CSP; the XSS guard test forbids HTML-string APIs).
  var CONF_ERRORS = { conflict: 'This confirmation cannot be answered now (already answered, too old, or too many of your disputes are waiting for a ruling).',
                      invalid_request: 'Please say what is wrong, in 10 to 300 characters.', not_found: 'That confirmation was not found.' };
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function stars(label, n) { return n == null ? null : label + ' ' + n + '/5'; }

  function standing(c) {
    if (c.disputeDecision === 'REMOVED') return ['REMOVED', 'Removed: a moderator ruled it does not count'];
    if (c.response === 'DISPUTED' && !c.disputeDecision) return ['HELD', 'Disputed: held out of your scores until a moderator rules'];
    if (c.disputeDecision === 'UPHELD') return ['UPHELD', 'Disputed, then upheld: it counts'];
    if (c.response === 'ACCEPTED') return ['ACCEPTED', 'Accepted: counts'];
    return ['COUNTED', 'Counts'];
  }

  function renderConfirmations(list) {
    var box = $('conf-list'), banner = $('conf-banner');
    box.textContent = '';
    var waiting = list.filter(function (c) { return c.canRespond; }).length;
    banner.hidden = waiting === 0;
    banner.className = 'msg err';
    banner.textContent = waiting === 0 ? '' : waiting + ' confirmation' + (waiting === 1 ? '' : 's') + ' about you ' + (waiting === 1 ? 'has' : 'have') + ' not been answered. Check that each one is true.';
    if (!list.length) { box.appendChild(el('p', 'muted', 'None yet. Ask a past employer to verify your work.')); return; }
    list.forEach(function (c) {
      var st = standing(c), row = el('div', 'conf'), head = el('div', 'conf-head');
      head.appendChild(el('span', 'conf-title', c.jobRole + ' for ' + c.employerName));
      head.appendChild(el('span', 'badge ' + st[0], st[1].split(':')[0]));
      row.appendChild(head);
      var parts = [c.workDate ? 'Work date ' + c.workDate : null, stars('Quality', c.ratingQuality), stars('Punctuality', c.ratingPunctuality), stars('Conduct', c.ratingConduct),
                   c.wouldHireAgain === true ? 'Would hire again' : (c.wouldHireAgain === false ? 'Would not hire again' : null)].filter(Boolean);
      row.appendChild(el('div', 'conf-sub', parts.join(' \u00b7 ')));
      if (st[1].indexOf(':') > 0) row.appendChild(el('div', 'conf-sub', st[1]));   // the badge already says it for a plain "Counts"
      if (c.disputeReason) row.appendChild(el('div', 'conf-sub', 'Your reason: ' + c.disputeReason));
      if (c.canRespond) {
        var actions = el('div', 'conf-actions'), accept = el('button', 'btn secondary', 'Accept'), dispute = el('button', 'btn danger', 'Dispute...');
        accept.type = dispute.type = 'button';
        actions.appendChild(accept); actions.appendChild(dispute);
        actions.appendChild(el('span', 'conf-sub', c.daysLeftToDispute + ' day' + (c.daysLeftToDispute === 1 ? '' : 's') + ' left to dispute'));
        row.appendChild(actions);
        accept.addEventListener('click', function () { respond(c.id, 'accept', {}, accept); });
        dispute.addEventListener('click', function () {
          dispute.disabled = true;
          var form = el('div'), reason = el('textarea'), send = el('button', 'btn danger', 'Send dispute'), cancel = el('button', 'btn secondary', 'Cancel');
          reason.maxLength = 300; reason.placeholder = 'What is untrue or unfair about this confirmation? (10 to 300 characters)';
          send.type = cancel.type = 'button';
          cancel.addEventListener('click', function () { row.removeChild(form); dispute.disabled = false; });
          send.addEventListener('click', function () { respond(c.id, 'dispute', { reason: reason.value }, send); });
          form.appendChild(reason);
          var bar = el('div', 'conf-actions'); bar.appendChild(send); bar.appendChild(cancel); form.appendChild(bar);
          row.appendChild(form); reason.focus();
        });
      }
      box.appendChild(row);
    });
  }

  function loadConfirmations() {
    KS.api('GET', '/api/worker/me/confirmations').then(function (r) {
      if (!r.ok) { $('conf-list').textContent = ''; return KS.say($('conf-msg'), 'Could not load your confirmations.', 'err'); }
      renderConfirmations(r.data.confirmations || []);
    });
  }

  function respond(id, action, body, button) {
    button.disabled = true;
    KS.say($('conf-msg'), '');
    KS.api('POST', '/api/worker/confirmations/' + id + '/' + action, body).then(function (r) {
      if (!r.ok) {
        button.disabled = false;
        return KS.say($('conf-msg'), CONF_ERRORS[r.data && r.data.error] || KS.errorText(r), 'err');
      }
      KS.say($('conf-msg'), action === 'accept' ? 'Accepted.' : 'Disputed. It is held out of your scores until a moderator rules.', 'ok');
      loadConfirmations();
      if (action === 'dispute') setTimeout(function () { location.reload(); }, 1500);      // scores and credential changed: show the new numbers
    });
  }
  if ($('conf-list')) loadConfirmations();

  var existing = card.getAttribute('data-ref');
  if (existing) show(existing);

  $('issue-btn').addEventListener('click', function () {
    KS.say($('cred-msg'), '');
    KS.api('POST', '/api/credential/issue', {}).then(function (r) {
      if (!r.ok) {
        var text = r.data && r.data.error === 'conflict' ? 'Finish the 10-question assessment first, and make sure none of your answers are under review.' : KS.errorText(r);
        return KS.say($('cred-msg'), text, 'err');
      }
      KS.say($('cred-msg'), 'Credential issued. Earlier ones were superseded.', 'ok');
      show(r.data.ref);
    });
  });
})();
