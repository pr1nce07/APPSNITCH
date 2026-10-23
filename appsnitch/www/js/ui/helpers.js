/* Shared UI state, selectors and small helpers. No data fetching here. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};
  var C = AS.content, ui = AS.ui = AS.ui || {};

  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function fmt(p) { return (p < 0 ? '\u2212' : '+') + Math.abs(p); }
  function ptCls(p) { return p < 0 ? 'pt-low' : p >= 20 ? 'pt-crit' : p >= 10 ? 'pt-high' : p >= 4 ? 'pt-med' : 'pt-low'; }
  function fmtDate(iso) {
    if (!iso) return 'unknown date';
    var d = new Date(iso); if (isNaN(d)) return 'unknown date';
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
  }

  var BAND = {}; C.bands.forEach(function (b) { BAND[b.key] = { label: b.label, cls: b.key }; });

  var storage = {
    get: function (k, d) { try { var v = localStorage.getItem(AS.config.storagePrefix + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(AS.config.storagePrefix + k, JSON.stringify(v)); } catch (e) {} }
  };

  var ICON = {
    chev: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>',
    arrow: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14"/><path d="m13 6 6 6-6 6"/></svg>',
    back: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 12H5"/><path d="m11 6-6 6 6 6"/></svg>',
    check: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></svg>',
    spin: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9" stroke-opacity="0.25"/><path d="M12 3a9 9 0 0 1 9 9"/></svg>',
    tabScan: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 8V6a2 2 0 0 1 2-2h2"/><path d="M16 4h2a2 2 0 0 1 2 2v2"/><path d="M20 16v2a2 2 0 0 1-2 2h-2"/><path d="M8 20H6a2 2 0 0 1-2-2v-2"/><path d="M4 12h16"/></svg>',
    tabRisk: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 4 6v6c0 4.5 3.2 7.8 8 9 4.8-1.2 8-4.5 8-9V6z"/><path d="M12 8v5"/><path d="M12 16.2v.1"/></svg>',
    tabFix: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m4 12 5 5L20 6"/></svg>',
    wait: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="9"/></svg>'
  };

  /* ---- app state: one object, views read it, app.js changes it ---- */
  var S = AS.state = {
    step: 1, sel: null, filter: 'all', q: '', sys: false,
    removed: {}, confirm: null, fixed: {}, habits: storage.get('habits', {}),
    apps: [], warnings: [], scannedAt: null,
    scanning: false, scanDone: false, scanError: null,
    prog: { done: 0, total: null, current: '' }
  };

  function visible() { return S.apps.filter(function (a) { return !S.removed[a.id] && (S.sys || !a.system); }); }
  function sorted(list) {
    return list.slice().sort(function (a, b) { return b.score - a.score || (a.system - b.system) || a.name.localeCompare(b.name); });
  }
  function byId(id) { return S.apps.filter(function (a) { return a.id === id; })[0]; }
  function qm(a) { return !S.q || (a.name + ' ' + a.pkg).toLowerCase().indexOf(S.q.toLowerCase()) >= 0; }
  function sampleChip() { return AS.data.isDemo() ? '<span class="sample">Sample data</span>' : ''; }
  function sourceCell(a) {
    return a.source === 'side' ? '<span class="src-bad">Outside Play Store</span>' : a.source === 'play' ? 'Play Store' : a.source === 'system' ? 'Built in' : 'Other app store';
  }

  function toast(msg, undo) {
    var h = $('toast-host');
    h.innerHTML = '<div class="toast" role="status"><span>' + esc(msg) + '</span>' + (undo ? '<button type="button" data-act="restore">Restore apps</button>' : '') + '</div>';
    clearTimeout(toast.t);
    toast.t = setTimeout(function () { h.innerHTML = ''; }, undo ? 6000 : 3500);
  }

  ui.$ = $; ui.esc = esc; ui.fmt = fmt; ui.ptCls = ptCls; ui.fmtDate = fmtDate; ui.BAND = BAND; ui.ICON = ICON;
  ui.storage = storage; ui.toast = toast; ui.visible = visible; ui.sorted = sorted; ui.byId = byId; ui.qm = qm;
  ui.sampleChip = sampleChip; ui.sourceCell = sourceCell;
})();
