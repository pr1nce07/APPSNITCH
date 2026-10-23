/* Data providers. Each one implements the SAME interface, so the UI never knows
   where data comes from:

     name, demo, canSkip, sendsDataOffDevice
     scan(onProgress)  -> Promise<raw ScanResult>   (see docs/DATA-CONTRACT.md)
                          onProgress({done,total,current}) is optional; total may be unknown
     uninstallApp(app)           -> Promise<{ok,message}>
     openAppSettings(app)        -> Promise<{ok,message}>
     openSystemSetting(key,label)-> Promise<{ok,message}>

   To add a new source, add an object here and set config.dataSource to its name. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};
  var cfg = AS.config;
  var P = AS.providers = {};

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function plugin() {
    var C = window.Capacitor;
    return C && C.Plugins && C.Plugins[cfg.nativePlugin] ? C.Plugins[cfg.nativePlugin] : null;
  }
  function needPlugin() {
    var p = plugin();
    if (!p) throw new Error('Native plugin "' + cfg.nativePlugin + '" is not available. Run inside the Android app, or use dataSource "mock" or "http".');
    return p;
  }
  function nativeAction(method, args, okMsg) {
    return Promise.resolve().then(function () { return needPlugin()[method](args); })
      .then(function () { return { ok: true, message: okMsg }; })
      .catch(function (e) { return { ok: false, message: (e && e.message) || 'That did not work.' }; });
  }

  /* ---- mock: sample data, animated like a real scan ---- */
  P.mock = {
    name: 'mock', demo: true, canSkip: true, sendsDataOffDevice: false,
    _finish: null,
    scan: function (onProgress) {
      var self = this, scan = AS.mock.scan, apps = scan.apps, total = apps.length, i = 0, timer, finished = false;
      return new Promise(function (resolve, reject) {
        if (cfg.mock.failScan) { setTimeout(function () { reject(new Error('Mock scan failed on purpose (?fail=1).')); }, 600); return; }
        function finish() {
          if (finished) return; finished = true; clearInterval(timer); self._finish = null;
          var out = clone(scan); out.scannedAt = new Date().toISOString(); resolve(out);
        }
        self._finish = finish;
        if (onProgress) onProgress({ done: 0, total: total, current: apps[0].packageName });
        timer = setInterval(function () {
          i++;
          if (i >= total) { finish(); return; }
          if (onProgress) onProgress({ done: i, total: total, current: apps[i].packageName });
        }, cfg.mock.stepMs);
      });
    },
    skip: function () { if (this._finish) this._finish(); },
    uninstallApp: function (app) { return Promise.resolve({ ok: true, message: app.name + ' removed (demo). Scores and counts are updated.' }); },
    openAppSettings: function (app) { return Promise.resolve({ ok: true, message: 'On your phone this opens Android settings for ' + app.name + '. Marked done.' }); },
    openSystemSetting: function (key, label) { return Promise.resolve({ ok: true, message: 'On your phone this opens: ' + label + '.' }); }
  };

  /* ---- native: Capacitor plugin that reads real installed apps ---- */
  P.native = {
    name: 'native', demo: false, canSkip: false, sendsDataOffDevice: false,
    scan: function (onProgress) {
      return Promise.resolve().then(function () {
        var p = needPlugin(), handle = null;
        var sub = (p.addListener && onProgress) ? Promise.resolve(p.addListener('scanProgress', onProgress)).then(function (h) { handle = h; }) : Promise.resolve();
        function cleanup() { if (handle && handle.remove) handle.remove(); }
        return sub.then(function () { return p.scan(); }).then(function (r) { cleanup(); return r; }, function (e) { cleanup(); throw e; });
      });
    },
    uninstallApp: function (app) { return nativeAction('uninstall', { packageName: app.pkg }, app.name + ' was uninstalled.'); },
    openAppSettings: function (app) { return nativeAction('openAppSettings', { packageName: app.pkg }, 'Opened Android settings for ' + app.name + '.'); },
    openSystemSetting: function (key, label) { return nativeAction('openSystemSetting', { setting: key }, 'Opened: ' + label + '.'); }
  };

  /* ---- http: scan result comes from your backend ---- */
  P.http = {
    name: 'http', demo: false, canSkip: false, sendsDataOffDevice: true,
    scan: function () {
      var a = cfg.api;
      if (!a.baseUrl && a.baseUrl !== '') return Promise.reject(new Error('config.api.baseUrl is not set.'));
      var url = a.baseUrl + a.endpoints.scan;
      var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
      var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, a.timeoutMs) : null;
      var body = a.method === 'POST' && typeof a.getBody === 'function' ? Promise.resolve(a.getBody()) : Promise.resolve(null);
      return body.then(function (b) {
        var opts = { method: a.method, headers: Object.assign({ Accept: 'application/json' }, a.headers), signal: ctrl ? ctrl.signal : undefined };
        if (b !== null && a.method === 'POST') { opts.body = JSON.stringify(b); opts.headers['Content-Type'] = 'application/json'; }
        return fetch(url, opts);
      }).then(function (r) {
        if (!r.ok) throw new Error('The server answered ' + r.status + ' ' + (r.statusText || '') + '.');
        return r.json();
      }).catch(function (e) {
        if (e && e.name === 'AbortError') throw new Error('The server took longer than ' + Math.round(a.timeoutMs / 1000) + ' seconds to answer.');
        if (e instanceof TypeError) throw new Error('Could not reach the server at ' + url + '. Check the connection, the address in config.js, and that the server allows requests from this app (CORS).');
        throw e;
      }).then(function (r) { clearTimeout(timer); return r; }, function (e) { clearTimeout(timer); throw e; });
    },
    /* Device actions only exist on the phone, so reuse the native plugin when present. */
    uninstallApp: function (app) { return plugin() ? P.native.uninstallApp(app) : Promise.resolve({ ok: false, message: 'Uninstalling only works in the installed Android app.' }); },
    openAppSettings: function (app) { return plugin() ? P.native.openAppSettings(app) : Promise.resolve({ ok: false, message: 'Opening settings only works in the installed Android app.' }); },
    openSystemSetting: function (k, l) { return plugin() ? P.native.openSystemSetting(k, l) : Promise.resolve({ ok: false, message: 'Opening settings only works in the installed Android app.' }); }
  };
})();
