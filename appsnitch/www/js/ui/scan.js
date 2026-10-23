/* Page 1: Scan. Shows scan progress, then the summary and ranked app table. */
(function () {
  var AS = window.AppSnitch, ui = AS.ui, S = AS.state, $ = ui.$, esc = ui.esc, ICON = ui.ICON, BAND = ui.BAND;
  var STEPTXT = ['Found your apps', 'Read what each app can do', 'Checked special access', 'Scored and wrote explanations'];

  function cardHtml() {
    var done = S.scanDone, total = done ? S.apps.length : S.prog.total, known = total != null && total > 0;
    var p = done ? total : S.prog.done;
    var C = 2 * Math.PI * 58, off = (known ? C * (1 - p / total) : C * 0.75).toFixed(1);
    var frac = known ? p / total : 0;
    var stage = done ? 4 : (frac < 0.05 ? 0 : frac < 0.45 ? 1 : frac < 0.8 ? 2 : 3);
    var list = STEPTXT.map(function (t, i) {
      var cls = done || i < stage ? 'ok' : i === stage ? 'on' : '';
      var ic = done || i < stage ? ICON.check : i === stage ? ICON.spin : ICON.wait;
      return '<div class="' + cls + '" style="' + (cls === 'ok' ? 'color:#8FD9B6' : '') + '">' + ic + t + '</div>';
    }).join('');
    var cur = done ? 'All ' + total + ' apps checked.' : (S.prog.current || 'Starting scan\u2026');
    return '<div class="sring' + (known ? '' : ' indet') + '" role="img" aria-label="' + (known ? p + ' of ' + total + ' apps checked' : 'Scanning') + '"><svg width="132" height="132" viewBox="0 0 132 132" aria-hidden="true"><circle cx="66" cy="66" r="58" fill="none" stroke="rgba(255,255,255,0.14)" stroke-width="10"/><circle class="p" cx="66" cy="66" r="58" fill="none" stroke="#3CBF84" stroke-width="10" stroke-linecap="round" stroke-dasharray="' + C.toFixed(1) + '" stroke-dashoffset="' + off + '" transform="rotate(-90 66 66)"/></svg><div class="c">' +
      (known ? '<b>' + p + '</b><span>of ' + total + ' apps</span>' : '<b>\u2026</b><span>apps</span>') + '</div></div>' +
      '<div class="scan-text"><h2>' + (done ? 'Scan complete' : 'Checking what your apps can do') + '</h2><div class="cur mono">' + esc(cur) + '</div><div class="chk">' + list + '</div></div>';
  }
  function errorHtml() {
    return '<h2>The scan did not finish</h2><p>' + esc(S.scanError) + '</p><button type="button" class="btn" data-act="rescan">Try again</button>';
  }
  function refreshCard() {
    var c = $('scancard'); if (!c) return;
    if (S.scanError) { c.className = 'scan scan-err'; c.innerHTML = errorHtml(); }
    else { c.className = 'scan'; c.innerHTML = cardHtml(); }
    var b = $('scanbtn'); if (!b) return;
    if (S.scanError || S.scanDone) b.innerHTML = '';
    else b.innerHTML = AS.data.canSkip() ? '<button type="button" class="btn plain big" data-act="skip">Skip animation</button>' : '';
  }
  function refresh() {
    refreshCard();
    var r = $('results'); if (!r) return;
    if (S.scanDone) renderResults(); else r.innerHTML = '';
  }
  function render() {
    $('view').innerHTML = '<main class="main"><header class="head"><div class="sub"><span>Every installed app is read, grouped into risk areas, then scored.</span>' + '</div></header>' +
      '<section class="scan" id="scancard" aria-live="polite"></section><div id="scanbtn" class="skip"></div><div id="results"></div></main>';
    refresh();
  }

  function matches(a) {
    if (S.filter === 'texts' && !a.has.texts) return false;
    if (S.filter === 'camera' && !a.has.camera) return false;
    if (S.filter === 'location' && !a.has.location) return false;
    if (S.filter === 'screen' && !a.has.screen) return false;
    return ui.qm(a);
  }
  function renderResults() {
    var r = $('results'); if (!r) return;
    r.innerHTML = '<div class="col"><section class="hero" id="hero" aria-label="Device summary"></section>' +
      '<section class="panel"><div class="panel-top"><div class="chips" id="chips" role="group" aria-label="Filter by access"></div>' +
      '<div class="opts"><label class="sw" for="sys"><span>Show system apps</span><input type="checkbox" id="sys"></label></div></div>' +
      '<div class="rows-h" id="rowsh">APPS, HIGHEST RISK FIRST</div><div id="rows" role="list" aria-label="Apps by risk"></div></section>' +
      (S.warnings.length ? '<p class="warn-note">' + S.warnings.length + ' item(s) in the scan response were fixed or skipped. See the browser console for details.</p>' : '') +
      '<div class="cta"><button type="button" class="btn big" data-act="step" data-s="2">See scoring and why flagged' + ICON.arrow + '</button></div></div>';
    $('sys').checked = S.sys;
    update();
  }
  function update() {
    if (!$('rows')) return;
    var vis = ui.visible();
    var n = { crit: 0, high: 0, med: 0, low: 0 }; vis.forEach(function (a) { n[a.band]++; });
    var att = n.crit + n.high;
    var tx = vis.filter(function (a) { return a.has.texts; }).length, cm = vis.filter(function (a) { return a.has.camera; }).length,
        sc = vis.filter(function (a) { return a.has.screen; }).length, lc = vis.filter(function (a) { return a.has.location; }).length;
    var colors = { crit: '#F0475A', high: '#F58A3B', med: '#F2B31B', low: '#3CBF85' };
    var bar = '', leg = '';
    ['crit', 'high', 'med', 'low'].forEach(function (k) {
      if (n[k]) bar += '<i style="flex:' + n[k] + ' 1 0;background:' + colors[k] + '"></i>';
      leg += '<div><span class="dot" style="background:' + colors[k] + '"></span><b>' + n[k] + '</b>' + ({ crit: 'Critical', high: 'High', med: 'Medium', low: 'Low' })[k] + '</div>';
    });
    $('hero').innerHTML =
      '<div class="hero-main"><div class="eyebrow">DEVICE RISK \u00b7 ' + vis.length + ' APPS</div><h2>' + (att === 0 ? 'No apps need your attention' : att + (att === 1 ? ' app needs' : ' apps need') + ' your attention') + '</h2>' +
      '<div class="bar" role="img" aria-label="' + n.crit + ' critical, ' + n.high + ' high, ' + n.med + ' medium, ' + n.low + ' low risk apps">' + bar + '</div><div class="legend">' + leg + '</div></div>' +
      '<div class="stats"><div class="stat"><b>' + tx + '</b><span>' + (tx === 1 ? 'app can' : 'apps can') + ' read your texts</span></div>' +
      '<div class="stat"><b>' + cm + '</b><span>' + (cm === 1 ? 'app can' : 'apps can') + ' use camera or mic</span></div>' +
      '<div class="stat"><b>' + sc + '</b><span>' + (sc === 1 ? 'app can' : 'apps can') + ' read your screen</span></div></div>';
    var f = [['all', 'All', vis.length], ['texts', 'Texts', tx], ['camera', 'Camera & mic', cm], ['location', 'Location', lc], ['screen', 'Screen access', sc]];
    $('chips').innerHTML = f.map(function (c) { return '<button type="button" class="fchip" data-act="filter" data-f="' + c[0] + '" aria-pressed="' + (S.filter === c[0]) + '">' + esc(c[1]) + ' ' + c[2] + '</button>'; }).join('');
    var rows = ui.sorted(vis.filter(matches));
    $('rows').innerHTML = rows.length ? rows.map(function (a) {
      var B = BAND[a.band];
      return '<button type="button" class="row" role="listitem" data-act="why" data-id="' + esc(a.id) + '" aria-label="' + esc(a.name + ', risk ' + a.score + ' ' + B.label.toLowerCase() + '. ' + a.top + '. Tap to see why.') + '">' +
        '<div class="ico" style="background:' + esc(a.color) + '" aria-hidden="true">' + esc(a.ch) + '</div>' +
        '<div class="rbody"><div><div class="nm">' + esc(a.name) + '</div><div class="pkg">' + esc(a.pkg) + '</div></div>' +
        '<div class="why">' + esc(a.top) + '</div>' +
        '<div class="meta-row"><span class="band b-' + B.cls + '"><b>' + a.score + '</b><i>' + B.label + '</i></span><span>' + ui.sourceCell(a) + '</span>' + (a.hasCtx ? '<span class="tag">Doesn\u2019t fit its purpose</span>' : '') + '</div></div>' +
        '<span class="chev" aria-hidden="true">' + ICON.chev + '</span></button>';
    }).join('') : '<div class="empty">' + (S.apps.length ? 'No apps match. Try a different filter or search.' : 'No apps were found on this device.') + '</div>';
  }

  ui.scan = { render: render, refresh: refresh, refreshCard: refreshCard, update: update };
})();
