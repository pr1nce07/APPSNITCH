/* Page 3: Prevention (phone). Per-app fixes (from each app's `fixes`) + habits + settings shortcuts. */
(function () {
  var AS = window.AppSnitch, ui = AS.ui, S = AS.state, $ = ui.$, esc = ui.esc, ICON = ui.ICON, BAND = ui.BAND;

  function render() {
    var vis = ui.visible().filter(function (a) { return !a.system; });
    var flaggedAll = ui.sorted(vis.filter(function (a) { return a.score >= 25 && a.fixes.length; }));
    var flagged = flaggedAll.filter(ui.qm);
    var low = vis.filter(function (a) { return flaggedAll.indexOf(a) < 0; });
    var habits = AS.data.getHabits(), shortcuts = AS.data.getShortcuts();
    var demo = AS.data.isDemo();

    var cards = flagged.map(function (a) {
      var B = BAND[a.band];
      var fx = a.fixes.map(function (f, i) {
        var key = a.id + ':' + i, isDone = !!S.fixed[key], btn = '';
        if (isDone) btn = '<span class="ok">' + ICON.check + 'Done</span>';
        else if (f.a === 'uninstall') btn = '<button type="button" class="btn sm danger" data-act="ask" data-id="' + esc(a.id) + '">Uninstall ' + esc(a.name) + '</button>';
        else if (f.a === 'settings') btn = '<button type="button" class="btn sm" data-act="fixset" data-key="' + esc(key) + '" data-id="' + esc(a.id) + '">Open settings</button>';
        return '<div class="fx' + (isDone ? ' done' : '') + '"><div class="tx">' + esc(f.t) + '</div>' + btn + '</div>';
      }).join('');
      var conf = S.confirm === a.id ? '<div class="confirm" role="alertdialog" aria-label="Confirm uninstall"><span>Remove <b>' + esc(a.name) + '</b> from this device? ' + (demo ? 'This demo only removes it from the list.' : 'Android will ask you to confirm.') + '</span><span class="cb"><button type="button" class="btn sm plain" data-act="cancel">Cancel</button><button type="button" class="btn sm danger" data-act="confirm" data-id="' + esc(a.id) + '">Uninstall</button></span></div>' : '';
      return '<section class="fix"><div class="fix-h"><div class="ico" style="background:' + esc(a.color) + '" aria-hidden="true">' + esc(a.ch) + '</div><div class="t"><b>' + esc(a.name) + '</b><span>' + esc(a.top) + '</span></div><span class="band b-' + B.cls + '"><b>' + a.score + '</b><i>' + B.label + '</i></span></div>' + fx + conf + '</section>';
    }).join('');

    var hDone = habits.filter(function (h, i) { return S.habits[i]; }).length;
    var hPct = habits.length ? Math.round(hDone / habits.length * 100) : 0;
    var emptyMsg = S.q && flaggedAll.length ? 'No flagged apps match your search.' : 'Nothing flagged is left to fix. Keep the habits below to stay that way.';
    $('view').innerHTML = '<main class="main"><header class="head"><div class="sub"><span>Fix the flagged apps first, then build the habits that keep new ones from getting this far.</span>' + '</div></header>' +
      '<div class="col"><h2>Fix these apps now</h2><p class="lead">Ordered by risk. Each step opens the right Android screen on your phone.</p>' +
      (flagged.length ? cards : '<div class="lowline">' + emptyMsg + '</div>') +
      (low.length ? '<div class="lowline muted">' + low.length + (low.length === 1 ? ' app is' : ' apps are') + ' low risk or need no action: ' + esc(low.map(function (a) { return a.name; }).join(', ')) + '.</div>' : '') +
      '</div>' +
      '<div class="col"><h2>Habits that prevent this</h2><div class="prog"><div class="pb" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + hPct + '" aria-label="Habits done"><i style="width:' + hPct + '%"></i></div><span>' + hDone + ' of ' + habits.length + ' done</span></div>' +
      '<div class="habits">' + habits.map(function (h, i) { return '<label class="habit" for="h' + i + '"><input type="checkbox" id="h' + i + '" data-habit="' + i + '"' + (S.habits[i] ? ' checked' : '') + '><div><b>' + esc(h.title) + '</b><span>' + esc(h.detail) + '</span></div></label>'; }).join('') + '</div>' +
      '<h2 class="h3">Hard-to-find settings</h2><div class="links">' +
      shortcuts.map(function (s) { return '<button type="button" data-act="shortcut" data-key="' + esc(s.key) + '" data-t="' + esc(s.label) + '">' + esc(s.label) + ICON.chev + '</button>'; }).join('') + '</div></div>' +
      '<div class="cta"><button type="button" class="btn big" data-act="rescan">Scan again after fixing' + ICON.arrow + '</button><button type="button" class="btn plain big" data-act="step" data-s="2">' + ICON.back + 'Back to scoring</button></div></main>';
  }

  ui.prevent = { render: render };
})();
