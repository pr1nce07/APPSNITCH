/* The ONLY thing the UI talks to for data. Picks a provider from config.dataSource,
   validates what comes back, and exposes a small, stable API. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};
  var cfg = AS.config;

  function provider() { return AS.providers[cfg.dataSource] || AS.providers.mock; }

  AS.data = {
    sourceName: function () { return provider().name; },
    isDemo: function () { return !!provider().demo; },
    canSkip: function () { return !!provider().canSkip; },
    skipAnimation: function () { if (provider().skip) provider().skip(); },
    privacyNote: function () { return provider().sendsDataOffDevice ? cfg.privacy.remote : cfg.privacy.local; },

    /* Promise<{apps, scannedAt, device, warnings}>, rejects with a readable Error */
    scan: function (onProgress) {
      return Promise.resolve().then(function () { return provider().scan(onProgress); }).then(AS.normalize.scanResult);
    },

    uninstallApp: function (app) { return provider().uninstallApp(app); },
    openAppSettings: function (app) { return provider().openAppSettings(app); },
    openSystemSetting: function (key, label) { return provider().openSystemSetting(key, label); },

    /* Static content today. Swap these for backend calls later without touching the views. */
    getRubric: function () { return AS.content.rubric; },
    getHabits: function () { return AS.content.habits; },
    getShortcuts: function () { return AS.content.shortcuts; },
    getCategories: function () { return AS.content.categories; },
    getBands: function () { return AS.content.bands; }
  };
})();
