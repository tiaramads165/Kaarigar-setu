/* Dev-only demo sign-in: one click per seeded account (see PageServlets.DemoPage and AuthService.demoLogin). */
(function () {
  'use strict';
  var KS = window.KS;
  document.querySelectorAll('button[data-role]').forEach(function (button) {
    button.addEventListener('click', function () {
      KS.say(KS.byId('demo-msg'), '');
      KS.api('POST', '/api/auth/demo-login', { role: button.getAttribute('data-role'), id: parseInt(button.getAttribute('data-id'), 10) }).then(function (r) {
        if (!r.ok) return KS.say(KS.byId('demo-msg'), 'Demo sign-in is not available: ' + KS.errorText(r), 'err');
        location.assign(r.data.role === 'EMPLOYER' ? '/employer-dashboard' : '/worker-dashboard');
      });
    });
  });
})();
