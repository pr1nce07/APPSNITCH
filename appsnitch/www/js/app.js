/* App controller: navigation, scan lifecycle, and event handling.
   Views live in js/ui/*, data lives behind AppSnitch.data. */
(function () {
  var AS = window.AppSnitch, ui = AS.ui, S = AS.state, $ = ui.$;
  var PAGES = [
    { label: 'Scan', icon: 'tabScan' },
    { label: 'Risk score', icon: 'tabRisk' },
    { label: 'Prevention', icon: 'tabFix' }
  ];

  function renderNav() {
    $('nav').innerHTML = PAGES.map(function (p, i) {
      var k = i + 1;
      return '<button type="button" class="tab" data-act="step" data-s="' + k + '"' + (S.step === k ? ' aria-current="step"' : '') + '>' + ui.ICON[p.icon] + '<span class="l">' + p.label + '</span></button>';
    }).join('');
  }

  /* ---- scan lifecycle ---- */
  var scanToken = 0;
  function startScan() {
    var token = ++scanToken;
    S.scanning = true; S.scanDone = false; S.scanError = null; S.apps = []; S.warnings = [];
    S.prog = { done: 0, total: null, current: '' };
    AS.data.scan(function (p) {
      if (token !== scanToken) return;
      S.prog = { done: p.done || 0, total: p.total == null ? null : p.total, current: p.current || '' };
      if (S.step === 1) ui.scan.refreshCard();
    }).then(function (res) {
      if (token !== scanToken) return;
      S.apps = res.apps; S.warnings = res.warnings; S.scannedAt = res.scannedAt;
      S.scanning = false; S.scanDone = true; S.prog = { done: res.apps.length, total: res.apps.length, current: '' };
      if (S.step === 1) ui.scan.refresh();
      else render();
    }).catch(function (e) {
      if (token !== scanToken) return;
      S.scanning = false; S.scanError = (e && e.message) || 'Something went wrong while scanning.';
      if (S.step === 1) ui.scan.refreshCard(); else render();
    });
  }

  function needScanView() {
    var failed = !!S.scanError;
    $('view').innerHTML = '<main class="main"><div class="needscan"><h2>' + (failed ? 'The scan did not finish' : 'Scan your phone first') + '</h2><p>' +
      (failed ? ui.esc(S.scanError) : 'This page needs scan results. Run the scan and it will fill in here.') + '</p><button type="button" class="btn big" data-act="step" data-s="1">Go to Scan</button></div></main>';
  }

  function render() {
    $('ptitle').textContent = PAGES[S.step - 1].label;
    $('privnote').textContent = AS.data.privacyNote();
    $('sampletag').hidden = !AS.data.isDemo();
    renderNav();
    if (S.step === 1) {
      if (!S.scanning && !S.scanDone && !S.scanError) startScan();
      ui.scan.render();
    } else if (!S.scanDone) needScanView();
    else if (S.step === 2) ui.risk.render();
    else ui.prevent.render();
    window.scrollTo(0, 0);
  }
  function rerender() {   /* redraw the current page without jumping to the top */
    var y = window.scrollY;
    if (S.step === 2 && S.scanDone) ui.risk.render(); else if (S.step === 3 && S.scanDone) ui.prevent.render(); else return;
    window.scrollTo(0, y);
  }
  function onSearch() {
    if (S.step === 1) { if (S.scanDone) ui.scan.update(); }
    else if (S.step === 2 && S.scanDone) {
      var m = ui.sorted(ui.visible()).filter(ui.qm);
      if (m.length && m.indexOf(ui.byId(S.sel)) < 0) S.sel = m[0].id;
      ui.risk.render();
    } else if (S.step === 3 && S.scanDone) ui.prevent.render();
  }

  /* ---- navigation + Android back button ----
     Every page change is pushed to the browser history, so the Android back button
     (Capacitor goes back in the WebView history, and closes the app at the first page)
     walks back through the pages the user visited. */
  function go(step) {
    var changed = step !== S.step;
    S.step = step; S.confirm = null;
    if (changed) { try { history.pushState({ step: step }, ''); } catch (e) {} }
    render();
  }
  window.addEventListener('popstate', function (e) {
    var st = e.state && e.state.step;
    S.step = (st === 1 || st === 2 || st === 3) ? st : 1; S.confirm = null;
    render();
  });
  try { history.replaceState({ step: 1 }, ''); } catch (e) {}

  /* ---- native actions: ignore taps while one is still running ---- */
  var busy = false;
  function guarded(promise, done) {
    busy = true;
    return promise.then(function (r) { busy = false; done(r); }, function (e) { busy = false; done({ ok: false, message: (e && e.message) || 'That did not work.' }); });
  }

  /* ---- events ---- */
  document.addEventListener('click', function (e) {
    var t = e.target.closest('[data-act]'); if (!t) return;
    var act = t.getAttribute('data-act'), id = t.getAttribute('data-id');
    if (act === 'step') go(+t.getAttribute('data-s'));
    else if (act === 'skip') AS.data.skipAnimation();
    else if (act === 'why') { S.sel = id; go(2); }
    else if (act === 'pick') { S.sel = id; rerender(); }
    else if (act === 'filter') { S.filter = t.getAttribute('data-f'); ui.scan.update(); }
    else if (act === 'rescan') {
      S.scanDone = false; S.scanning = false; S.scanError = null;
      if (!AS.data.isDemo()) { S.removed = {}; S.fixed = {}; }   /* real data is the source of truth */
      go(1);
    }
    else if (act === 'fixset') {
      if (busy) return;
      var key = t.getAttribute('data-key'), app = ui.byId(id); if (!app) return;
      guarded(AS.data.openAppSettings(app), function (r) { if (r.ok) S.fixed[key] = 1; ui.toast(r.message); rerender(); });
    }
    else if (act === 'shortcut') {
      if (busy) return;
      guarded(AS.data.openSystemSetting(t.getAttribute('data-key'), t.getAttribute('data-t')), function (r) { ui.toast(r.message); });
    }
    else if (act === 'ask') { S.confirm = id; rerender(); var c = document.querySelector('.confirm .btn.danger'); if (c) { c.focus(); c.scrollIntoView({ block: 'center' }); } }
    else if (act === 'cancel') { S.confirm = null; rerender(); }
    else if (act === 'confirm') {
      if (busy) return;
      var ap = ui.byId(id); if (!ap) return;
      guarded(AS.data.uninstallApp(ap), function (r) {
        S.confirm = null;
        if (r.ok) S.removed[id] = 1;
        rerender(); ui.toast(r.message, r.ok && AS.data.isDemo());
      });
    }
    else if (act === 'restore') { S.removed = {}; S.fixed = {}; if (S.step === 3) rerender(); else render(); $('toast-host').innerHTML = ''; }
  });
  document.addEventListener('input', function (e) { if (e.target.id === 'q') { S.q = e.target.value; onSearch(); } });
  document.addEventListener('change', function (e) {
    if (e.target.id === 'sys') { S.sys = e.target.checked; ui.scan.update(); }
    if (e.target.hasAttribute && e.target.hasAttribute('data-habit')) {
      S.habits[e.target.getAttribute('data-habit')] = e.target.checked;
      ui.storage.set('habits', S.habits);
      var elId = e.target.id; rerender(); var el = $(elId); if (el) el.focus();
    }
  });

  /* ---- phone keyboard: while the on-screen keyboard is open (search box focused AND the window got shorter),
     hide the tab bar so it does not ride on top of the keyboard. Enter closes the keyboard.
     Uses the real window height, so the bar comes back even if Android's Back button hides the keyboard
     while the search box keeps focus. ---- */
  var maxH = window.innerHeight;
  function updateKb() {
    maxH = Math.max(maxH, window.innerHeight);
    var typing = document.activeElement && document.activeElement.id === 'q';
    document.body.classList.toggle('kb', !!(typing && window.innerHeight < maxH - 120));
  }
  window.addEventListener('resize', updateKb);
  if (window.visualViewport) window.visualViewport.addEventListener('resize', updateKb);
  document.addEventListener('focusin', updateKb);
  document.addEventListener('focusout', function () { setTimeout(updateKb, 0); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Enter' && e.target.id === 'q') e.target.blur(); });

  /* ---- coming back from Android settings: the scores on screen may now be stale ---- */
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible' && S.scanDone && !AS.data.isDemo() && Object.keys(S.fixed).length) {
      ui.toast('Back from Settings. Tap “Scan again” to refresh the scores.');
    }
  });

  AS.app = { render: render };
  render();
})();
