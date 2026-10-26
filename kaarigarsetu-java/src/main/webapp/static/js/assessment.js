/* Voice assessment: ask (spoken) -> record -> upload WAV -> transcript, score, spoken feedback -> next. */
(function () {
  'use strict';
  var KS = window.KS, $ = KS.byId;
  var attempt = null, language = 'hi', maxSeconds = 60;
  var BANDS = { PASS: 'Pass', BORDERLINE: 'Borderline', NEEDS_REVIEW: 'Needs review' };
  var AREAS = { tools: 'Tools', materials: 'Materials', experience: 'Experience', knowledge: 'Knowledge', troubleshooting: 'Troubleshooting', planning: 'Planning and estimating', customer: 'Customer handling' };

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function add(cls, text) { var b = el('div', 'bubble ' + cls, text); $('log').appendChild(b); b.scrollIntoView({ block: 'nearest' }); return b; }
  function dock(nodes) { var d = $('dock'); d.textContent = ''; nodes.forEach(function (n) { d.appendChild(n); }); }

  function speak(text) {
    return new Promise(function (resolve) {
      if (!('speechSynthesis' in window) || !text) return resolve();
      var u = new SpeechSynthesisUtterance(text);
      u.lang = language === 'hi' ? 'hi-IN' : 'en-IN';
      u.onend = resolve; u.onerror = resolve;
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(u);
      setTimeout(resolve, 25000);
    });
  }

  function record() {
    return new Promise(function (resolve, reject) {
      // Browsers expose the microphone only on a secure page: https://, or http://localhost on the computer itself.
      // Over plain http on a network address (for example http://192.168.x.x) navigator.mediaDevices does not exist at all.
      if (window.isSecureContext === false) return reject('insecure');
      if (!navigator.mediaDevices || !window.MediaRecorder) return reject('unsupported');
      navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
        var rec = new MediaRecorder(stream), chunks = [], started = Date.now();
        var btn = el('button', 'mic rec', '⏹'); btn.type = 'button';
        var hint = el('p', 'muted', 'Recording…');
        var tick = setInterval(function () { hint.textContent = 'Recording… ' + Math.max(0, maxSeconds - Math.round((Date.now() - started) / 1000)) + 's'; }, 500);
        var auto = setTimeout(finish, maxSeconds * 1000);
        function finish() { clearInterval(tick); clearTimeout(auto); if (rec.state !== 'inactive') rec.stop(); }
        rec.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
        rec.onstop = function () { stream.getTracks().forEach(function (t) { t.stop(); }); resolve(new Blob(chunks, { type: rec.mimeType || 'audio/webm' })); };
        btn.addEventListener('click', finish);
        dock([btn, hint]);
        rec.start();
      }).catch(function () { reject('mic'); });
    });
  }

  function waitMic() {
    return new Promise(function (resolve) {
      var mic = el('button', 'mic', '🎤'); mic.type = 'button';
      mic.addEventListener('click', resolve);
      dock([mic, el('p', 'muted', 'Tap to answer · बोलने के लिए दबाएं')]);
    });
  }

  // The spoken code (docs/REPLAY_DETECTION.md): shown with the question, made by the server when the question is asked, to be said at the end of the answer.
  // A recording made earlier cannot contain it. The token that goes back with the answer is what proves the code is the one issued for THIS question, just now.
  function challengeFor(question) {
    return KS.api('GET', '/api/assessment/challenge?attempt=' + encodeURIComponent(attempt) + '&question=' + question.id).then(function (r) {
      return r.ok && r.data && r.data.required ? r.data : null;
    });
  }

  function spokenDigits(code) { return code.split('').join(' '); }

  function showCode(bubble, challenge) {
    if (!challenge) return;
    var line = el('div', 'challenge');
    line.appendChild(el('strong', null, 'At the end of your answer, say the number: ' + spokenDigits(challenge.code)));
    line.appendChild(document.createElement('br'));
    line.appendChild(document.createTextNode('अपना जवाब देने के बाद यह नंबर बोलें: ' + spokenDigits(challenge.code)));
    bubble.appendChild(line);
  }

  function ask(question, total) {
    var b = add('q', '');
    b.appendChild(el('strong', null, 'Question ' + question.id + ' / ' + total));
    b.appendChild(document.createElement('br'));
    b.appendChild(document.createTextNode(question.text));
    return challengeFor(question).then(function (challenge) {
      showCode(b, challenge);
      var spoken = question.text + (challenge ? (language === 'hi' ? ' जवाब के अंत में यह नंबर बोलें: ' : ' At the end of your answer, say the number: ') + spokenDigits(challenge.code) : '');
      return speak(spoken).then(function () { return answer(question, challenge); });
    });
  }

  function answer(question, challenge) {
    return waitMic().then(record).then(function (blob) {
      dock([el('p', 'muted', 'Checking your answer…')]);
      return window.Wav.fromBlob(blob, maxSeconds).catch(function () { return Promise.reject('convert'); });
    }).then(function (wav) {
      return KS.api('POST', '/api/assessment/submit-answer?attempt=' + encodeURIComponent(attempt) + '&question=' + question.id
        + (challenge ? '&challenge=' + encodeURIComponent(challenge.token) : ''), wav);
    }).then(function (r) {
      if (r.ok) return r.data;
      var code = r.data && r.data.error;
      add('', KS.errorText(r));
      if (code === 'challenge_required') {        // the code went stale (or the page was left open): a fresh one, then record again
        return challengeFor(question).then(function (fresh) {
          showCode(add('q', ''), fresh);
          return answer(question, fresh);
        });
      }
      if (code === 'unintelligible' || code === 'invalid_request' || code === 'upstream_unavailable' || code === 'code_not_heard') return answer(question, challenge);
      throw new Error('stop');
    }, function (why) {
      var retry = why === 'mic' || why === 'convert';
      add('', {
        mic: 'Microphone permission was denied. Please allow it in the browser and try again. · माइक्रोफ़ोन की अनुमति दें और फिर कोशिश करें।',
        insecure: 'The microphone only works on a secure page. On the computer running this app open http://localhost:' + (location.port || '80') +
          ' ; on a phone the app must be served over https://. · माइक्रोफ़ोन केवल सुरक्षित (https) पते पर चलता है।',
        unsupported: 'This browser cannot record audio. Please use a recent Chrome, Edge, Firefox or Safari. · यह ब्राउज़र रिकॉर्डिंग नहीं कर सकता।',
        convert: 'We could not read the recording. Please try again. · रिकॉर्डिंग पढ़ी नहीं जा सकी, कृपया फिर कोशिश करें।'
      }[why] || 'We could not read the recording. Please try again.');
      if (!retry) { dock([]); throw new Error('stop'); }
      return new Promise(function (resolve) {
        var again = el('button', 'btn', 'Try again · फिर कोशिश करें'); again.type = 'button';
        again.addEventListener('click', resolve);
        dock([again]);
      }).then(function () { return answer(question, challenge); });
    });
  }

  function run(started) {
    var total = started.questions.length, i = 0;
    function next() {
      if (i >= total) return Promise.resolve();
      var q = started.questions[i++];
      return ask(q, total).then(function (out) {
        add('me', out.transcript);
        var line = out.needsReview ? 'Your answer was recorded and will be checked.' : 'Score: ' + out.score + ' / 100. ' + (out.feedback || '');
        add('', line);
        if (out.provider === 'keyword') add('', 'Practice scoring: this only checks which words were said, it does not judge whether the answer is correct.');
        var done = speak(out.needsReview ? '' : out.feedback);
        if (out.finished) {
          dock([]);
          add('', 'Assessment complete. Skill score: ' + out.skillScore + ' / 100 (' + (BANDS[out.skillBand] || '') + '). ' + (out.skillFeedback || ''));
          if (out.skillAreas) Object.keys(out.skillAreas).forEach(function (area) { add('', (AREAS[area] || area) + ': ' + out.skillAreas[area] + ' / 100'); });
          if (out.nearBoundary) add('', 'Your score is close to the edge of this band, so another attempt could change it.');
          var link = el('a', 'btn', 'Go to my passport');
          link.href = '/worker-dashboard';
          dock([link]);
          return done;
        }
        return done.then(next);
      });
    }
    return next();
  }

  $('start').addEventListener('click', function () {
    KS.say($('msg'), '');
    KS.api('POST', '/api/assessment/start', {}).then(function (r) {
      if (!r.ok) return KS.say($('msg'), KS.errorText(r), 'err');
      attempt = r.data.attemptToken;
      language = r.data.language;
      $('intro').hidden = true;
      $('stage').hidden = false;
      if (r.data.testOnly) add('', 'TEST ONLY questions: placeholders, not the real assessment.');
      run(r.data).catch(function () { /* already reported to the user */ });
    });
  });
})();
