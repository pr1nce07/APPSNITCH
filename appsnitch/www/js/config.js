/* AppSnitch configuration.
   The ONLY file you need to edit to switch where data comes from.

   dataSource:
     'mock'   sample data bundled in js/data/mock-data.js (design review, demos)
     'http'   fetch a scan result from your backend (see docs/DATA-CONTRACT.md)
     'native' call the Android plugin that reads real installed apps (Capacitor)

   Dev shortcuts, no code change needed:
     index.html?source=http&api=https://my-server.example
     index.html?fail=1            mock scan fails, to preview the error screen
   Or define window.APPSNITCH_CONFIG = {...} before this file loads. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};

  var cfg = {
    dataSource: 'mock',

    api: {
      baseUrl: '',                       // e.g. 'https://api.appsnitch.dev'
      method: 'GET',                     // 'GET' or 'POST'
      timeoutMs: 15000,
      headers: {},                       // e.g. { Authorization: 'Bearer ...' }
      endpoints: { scan: '/v1/scan' },
      // Optional hook for POST: return a Promise/value that becomes the JSON body.
      // Typical use: send the raw installed-app list collected by the native plugin.
      getBody: null
    },

    nativePlugin: 'AppScanner',          // Capacitor plugin name, see README

    mock: { stepMs: 320, failScan: false },

    storagePrefix: 'appsnitch:',

    privacy: {
      local: 'Checked on this device. Nothing is uploaded.',
      remote: 'Your app list is sent to the AppSnitch server to be scored.'
    }
  };

  function merge(t, s) {
    Object.keys(s || {}).forEach(function (k) {
      if (s[k] && typeof s[k] === 'object' && !Array.isArray(s[k]) && typeof t[k] === 'object' && t[k]) merge(t[k], s[k]);
      else t[k] = s[k];
    });
  }
  if (window.APPSNITCH_CONFIG) merge(cfg, window.APPSNITCH_CONFIG);
  try {
    var q = new URLSearchParams(location.search);
    if (q.get('source')) cfg.dataSource = q.get('source');
    if (q.get('api')) cfg.api.baseUrl = q.get('api');
    if (q.get('fail') === '1') cfg.mock.failScan = true;
  } catch (e) {}

  AS.config = cfg;
})();
