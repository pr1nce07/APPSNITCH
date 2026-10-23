/* Page 2: Risk score (phone). Pick an app, see its score, why it was flagged, what it can access.
   The scoring rubric (from content.js) is an accordion at the bottom of the page. */
(function () {
  var AS = window.AppSnitch, ui = AS.ui, S = AS.state, $ = ui.$, esc = ui.esc, ICON = ui.ICON, BAND = ui.BAND, fmt = ui.fmt;

  function rubricHtml() {
    var R = AS.data.getRubric();
    function tbl(rows) { return '<table><tbody>' + rows.map(function (r) { return '<tr><td>' + esc(r[0]) + '</td><td class="n">' + esc(r[1]) + '</td></tr>'; }).join('') + '</tbody></table>'; }
    return '<details class="rubric" id="rubric"><summary>' + esc(R.title) + '</summary><div class="inner"><p class="lead">' + esc(R.lead) + '</p>' +
      R.sections.map(function (s) { return '<h3>' + esc(s.title) + '</h3>' + tbl(s.rows) + (s.note ? '<p class="note">' + esc(s.note) + '</p>' : ''); }).join('') + '</div></details>';
  }

  /* keep the horizontal app picker where the user left it, and make sure the chosen app is in view */
  function fixPicker(prevLeft) {
    var p = $('picker'); if (!p) return;
    p.scrollLeft = prevLeft || 0;
    var sel = p.querySelector('[aria-pressed="true"]'); if (!sel) return;
    var l = sel.offsetLeft - p.offsetLeft, r = l + sel.offsetWidth;
    if (l < p.scrollLeft + 16 || r > p.scrollLeft + p.clientWidth - 16) p.scrollLeft = Math.max(0, l - (p.clientWidth - sel.offsetWidth) / 2);
  }

  function render() {
    var prevLeft = $('picker') ? $('picker').scrollLeft : 0;
    var vis = ui.visible(), list = ui.sorted(vis);
    if (!list.length) { $('view').innerHTML = '<main class="main"><div class="needscan"><h2>No apps to show</h2><p>There are no apps to score. Try showing system apps or scanning again.</p><button type="button" class="btn big" data-act="rescan">Scan again</button></div></main>'; return; }
    var rubricOpen = !!($('rubric') && $('rubric').open);
    var a = ui.byId(S.sel);
    if (!a || S.removed[a.id] || list.indexOf(a) < 0) { a = list[0]; S.sel = a.id; }
    var B = BAND[a.band];
    var lower = vis.filter(function (x) { return x.score < a.score; }).length;
    var C = 251.327, off = (C * (1 - a.score / 100)).toFixed(1);
    var ringCol = 'var(--' + B.cls + ')';
    var scoreInk = a.band === 'med' ? 'var(--med-ink)' : ringCol;
    function sum(k) { return a.reasons.filter(function (r) { return r.k === k; }).reduce(function (t, r) { return t + r.p; }, 0); }
    var rowsSum = [['access', 'What it can access'], ['context', 'Doesn’t fit what it does'], ['combo', 'Risky combination'], ['trust', 'Where it came from']].map(function (x) {
      var v = sum(x[0]); return v === 0 ? '' : '<div class="r"><span>' + x[1] + '</span><span class="mono">' + fmt(v) + '</span></div>';
    }).join('');
    var tags = '';
    if (a.source === 'side') tags += '<span class="pillx p-bad">Installed outside Play Store</span>';
    if (a.source === 'play') tags += '<span class="pillx p-good">Installed from Play Store</span>';
    if (a.source === 'system') tags += '<span class="pillx p-info">Built into your phone</span>';
    if (a.source === 'other') tags += '<span class="pillx p-info">Installed from another app store</span>';
    if (a.old) tags += '<span class="pillx p-info">' + esc(a.oldLabel) + '</span>';
    if (a.hasCtx) tags += '<span class="pillx p-bad">Doesn’t fit its purpose</span>';

    var catsHtml = '', none = [];
    AS.data.getCategories().forEach(function (c) {
      var items = a.access[c.key] || [];
      if (!items.length) { none.push(c.label); return; }
      var pts = a.reasons.filter(function (r) { return r.k === 'access' && r.c === c.key; }).reduce(function (t, r) { return t + r.p; }, 0);
      var latentOnly = items.every(function (i) { return !i.g; });
      var col = pts >= 10 ? 'var(--high)' : pts >= 4 ? 'var(--med-bar)' : 'var(--low)';
      var tc = pts >= 10 ? 'var(--high-ink)' : pts >= 4 ? 'var(--med-ink)' : 'var(--low-ink)';
      catsHtml += '<div class="cat"><div class="ct"><b>' + esc(c.label) + '</b><span style="color:' + tc + '">' + fmt(pts) + '</span></div>' +
        '<div class="meter"><i style="width:' + Math.max(0, Math.min(100, Math.round(pts / 25 * 100))) + '%;background:' + col + '"></i></div>' +
        '<div class="perms">' + items.map(function (i) { return '<span class="pm' + (i.g ? '' : ' off') + '"><span class="' + (i.g ? 'd1' : 'd0') + '"></span>' + esc(i.l) + '</span>'; }).join('') + '</div>' +
        (latentOnly ? '<div class="latenote">It could use this later, but you haven’t allowed it yet.</div>' : '') + '</div>';
    });
    if (none.length) catsHtml += '<div class="cat none"><b style="font-size:14.5px">Not requested</b><span class="l">' + esc(none.join(', ')) + '.</span></div>';
    var tech = []; AS.data.getCategories().forEach(function (c) { (a.access[c.key] || []).forEach(function (i) { tech.push('<div>' + esc(i.perm) + ' <span>· ' + i.prot + ' · ' + (i.g ? 'granted' : 'declared only') + '</span></div>'); }); });

    var shown = list.filter(ui.qm);
    $('view').innerHTML = '<main class="main"><header class="head"><div class="sub"><span>Pick an app to see which rules fired and why it was flagged.</span>' + '</div></header>' +
      '<div class="picker" id="picker" role="group" aria-label="Choose an app">' +
      (shown.length ? '' : '<span class="latenote" style="padding:12px 0">No apps match your search.</span>') +
      shown.map(function (x) { var b = BAND[x.band]; return '<button type="button" class="pick" data-act="pick" data-id="' + esc(x.id) + '" aria-pressed="' + (x.id === a.id) + '"><span class="ico sm" style="background:' + esc(x.color) + '" aria-hidden="true">' + esc(x.ch) + '</span>' + '<span class="pn">' + esc(x.name) + '</span><span class="mini b-' + b.cls + '">' + x.score + '</span></button>'; }).join('') + '</div>' +
      '<div class="col">' +
      '<div class="dhead"><div class="ico lg" style="background:' + esc(a.color) + '" aria-hidden="true">' + esc(a.ch) + '</div><div style="min-width:0"><h2>' + esc(a.name) + '</h2><div class="pkg" style="font-size:12px;margin-top:4px">' + esc(a.pkg) + '</div><div class="meta">v' + esc(a.ver) + ' · ' + (a.system ? 'built in' : 'installed ' + ui.fmtDate(a.installed)) + '</div></div></div>' +
      (tags ? '<div class="tags">' + tags + '</div>' : '') +
      '<div class="card score"><div class="ring" role="img" aria-label="Risk score ' + a.score + ' out of 100, ' + B.label.toLowerCase() + '"><svg width="100" height="100" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="40" fill="none" stroke="var(--track)" stroke-width="10"/><circle cx="50" cy="50" r="40" fill="none" stroke="' + ringCol + '" stroke-width="10" stroke-linecap="round" stroke-dasharray="' + C + '" stroke-dashoffset="' + off + '" transform="rotate(-90 50 50)"/></svg><div class="c"><b style="color:' + scoreInk + '">' + a.score + '</b><span>of 100</span></div></div>' +
      '<div style="display:flex;flex-direction:column;gap:6px;min-width:0"><span class="bandlbl b-' + B.cls + '">' + B.label + '</span><div class="big">' + (lower > 0 ? 'Riskier than ' + lower + ' of the ' + vis.length + ' apps on this phone' : 'The lowest risk of the ' + vis.length + ' apps on this phone') + '</div></div></div>' +
      '<div class="card sum"><h3>How ' + a.score + ' adds up</h3>' + rowsSum + '<div class="tot"><span>Total</span><span class="mono" style="color:' + scoreInk + '">' + a.score + ' · ' + B.label.charAt(0) + B.label.slice(1).toLowerCase() + '</span></div></div>' +
      '<section class="reasons"><div class="rh sec-h"><h2>Why this is flagged</h2><span class="cnt">' + a.reasons.length + (a.reasons.length === 1 ? ' reason' : ' reasons') + '</span></div>' +
      (a.reasons.length ? a.reasons.map(function (r) { return '<div class="reason"><div class="rt"><b>' + esc(r.h) + '</b><span>' + esc(r.d) + '</span></div><span class="pt ' + ui.ptCls(r.p) + '">' + fmt(r.p) + '</span></div>'; }).join('') : '<div class="reason"><div class="rt"><span>No risk reasons were reported for this app.</span></div></div>') + '</section>' +
      '<section><div class="sec-h"><h2>What it can access</h2></div><div class="key"><span><i class="dotf"></i>Allowed now</span><span><i class="dote"></i>Asked for, not allowed yet</span></div><div class="cats">' + catsHtml + '</div></section>' +
      rubricHtml() +
      '<details class="tech"><summary>Technical details</summary><div class="tl">' + (tech.length ? tech.join('') : '<div>No permissions reported.</div>') + '</div></details>' +
      '<div class="cta"><button type="button" class="btn big" data-act="step" data-s="3">See what to do about it' + ICON.arrow + '</button><button type="button" class="btn plain big" data-act="step" data-s="1">' + ICON.back + 'Back to scan</button></div>' +
      '</div></main>';
    if (rubricOpen && $('rubric')) $('rubric').open = true;
    fixPicker(prevLeft);
  }

  ui.risk = { render: render };
})();
