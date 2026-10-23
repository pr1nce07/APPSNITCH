/* Turns a raw scan response (see docs/DATA-CONTRACT.md) into the shape the UI uses.
   Validates, fills safe defaults, derives band/flags, and NEVER throws on a single bad app:
   bad apps are skipped and reported in result.warnings. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};
  var C = AS.content;

  var SOURCES = { play_store: 'play', sideloaded: 'side', system: 'system', other: 'other' };
  var PROTECTION = { dangerous: 1, normal: 1, special: 1 };
  var ACTIONS = { uninstall: 1, settings: 1, info: 1 };
  var KINDS = { access: 1, context: 1, combo: 1, trust: 1 };
  var PALETTE = ['#2E5E8C', '#6B4C9A', '#8A5A2B', '#2D7D7B', '#6A6F2A', '#B35A7A', '#3F6E4F', '#8C3B5E', '#4A5A6A'];

  function bandOf(score) {
    var sorted = C.bands.slice().sort(function (a, b) { return b.min - a.min; });
    for (var i = 0; i < sorted.length; i++) if (score >= sorted[i].min) return sorted[i].key;
    return sorted[sorted.length - 1].key;
  }
  function colorFor(s) {
    var h = 0; for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return PALETTE[h % PALETTE.length];
  }
  function str(v, d) { return typeof v === 'string' && v ? v : (d || ''); }
  /* iconColor ends up inside a style attribute, so only accept a plain hex colour from the server */
  function hex(v, d) { return typeof v === 'string' && /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v) ? v : d; }

  function app(raw, warnings, idx) {
    if (!raw || typeof raw !== 'object') { warnings.push('apps[' + idx + '] is not an object, skipped'); return null; }
    var pkg = str(raw.packageName), name = str(raw.name, pkg);
    if (!pkg) { warnings.push('apps[' + idx + '] has no packageName, skipped'); return null; }
    var id = str(raw.id, pkg);
    var catKeys = C.categories.map(function (c) { return c.key; });

    var reasons = (Array.isArray(raw.reasons) ? raw.reasons : []).filter(function (r) {
      if (r && typeof r === 'object') return true;
      warnings.push(pkg + ': a reason is not an object, ignored'); return false;
    }).map(function (r) {
      var cat = r.category && catKeys.indexOf(r.category) >= 0 ? r.category : undefined;
      if (r.category && !cat) warnings.push(pkg + ': unknown category "' + r.category + '"');
      return { k: KINDS[r.kind] ? r.kind : 'access', c: cat, latent: !!r.latent, h: str(r.title, 'Reason'), d: str(r.detail), p: Number(r.points) || 0 };
    }).sort(function (a, b) { return b.p - a.p; });

    var access = {};
    Object.keys(raw.access && typeof raw.access === 'object' ? raw.access : {}).forEach(function (k) {
      if (catKeys.indexOf(k) < 0) { warnings.push(pkg + ': unknown access category "' + k + '" ignored'); return; }
      access[k] = (Array.isArray(raw.access[k]) ? raw.access[k] : []).map(function (i) {
        i = i && typeof i === 'object' ? i : {};
        return { l: str(i.label, i.permission || 'Permission'), g: !!i.granted, perm: str(i.permission, 'unknown'),
                 prot: PROTECTION[i.protection] ? i.protection : 'dangerous', screen: !!i.screenReader };
      });
    });

    var sum = reasons.reduce(function (t, r) { return t + r.p; }, 0);
    var score = Number.isFinite(Number(raw.score)) && raw.score !== null ? Number(raw.score) : sum;
    score = Math.max(0, Math.min(100, Math.round(score)));

    var source = SOURCES[raw.installSource] || 'other';
    var system = !!raw.isSystem || source === 'system';
    function granted(k) { return (access[k] || []).some(function (i) { return i.g; }); }

    var a = {
      id: id, name: name, pkg: pkg,
      ch: str(raw.iconLetter, name.charAt(0).toUpperCase() || '?'),
      color: hex(raw.iconColor, colorFor(pkg)),
      ver: str(raw.versionName, '?'),
      installed: raw.installedAt || null,
      source: source, system: system,
      old: !!raw.targetsOldAndroid,
      oldLabel: str(raw.oldAndroidLabel, 'Built for an old Android version'),
      score: score, band: bandOf(score),
      reasons: reasons, access: access,
      fixes: (Array.isArray(raw.fixes) ? raw.fixes : []).map(function (f) {
        f = f && typeof f === 'object' ? f : {};
        return { t: str(f.text), a: ACTIONS[f.action] ? f.action : 'info' };
      })
    };
    a.has = { texts: granted('texts'), camera: granted('camera'), location: granted('location'),
              screen: (access.special || []).some(function (i) { return i.g && i.screen; }) };
    a.hasCtx = reasons.some(function (r) { return r.k === 'context'; });
    var topR = reasons.filter(function (r) { return r.p > 0; })[0] || reasons[0];
    a.top = topR ? topR.h : 'No risk reasons reported';
    return a;
  }

  function scanResult(raw) {
    if (!raw || typeof raw !== 'object' || !Array.isArray(raw.apps)) {
      throw new Error('Invalid scan response: expected an object with an "apps" array.');
    }
    var warnings = [];
    if (raw.schemaVersion !== 1) warnings.push('schemaVersion is ' + raw.schemaVersion + ', this UI expects 1');
    var seen = {};
    var apps = raw.apps.map(function (r, i) { return app(r, warnings, i); }).filter(function (a) {
      if (!a) return false;
      if (seen[a.id]) { warnings.push('duplicate id "' + a.id + '", skipped'); return false; }
      seen[a.id] = 1; return true;
    });
    if (warnings.length && window.console) console.warn('[AppSnitch] scan warnings:', warnings);
    return { apps: apps, scannedAt: raw.scannedAt || null, device: raw.device || null, warnings: warnings };
  }

  AS.normalize = { scanResult: scanResult, app: app, bandOf: bandOf };
})();
