/* Static content: wording, rubric, categories. No logic here.
   This is what the design team edits to change copy. Everything the UI shows that is
   NOT per-app data lives here, and can later be served by a backend instead. */
(function () {
  var AS = window.AppSnitch = window.AppSnitch || {};

  /* Risk areas an app's permissions are grouped into. `key` is part of the data contract. */
  var categories = [
    { key: 'special',    label: 'Special access (screen, overlay, admin)' },
    { key: 'texts',      label: 'Text messages' },
    { key: 'contacts',   label: 'Contacts and calls' },
    { key: 'camera',     label: 'Camera and microphone' },
    { key: 'location',   label: 'Location' },
    { key: 'phone',      label: 'Phone and identity' },
    { key: 'files',      label: 'Photos and files' },
    { key: 'background', label: 'Background and auto-start' },
    { key: 'network',    label: 'Internet' }
  ];

  /* Risk bands. `min` is the lowest score in the band. Order does not matter. */
  var bands = [
    { key: 'low',  label: 'LOW',      min: 0,  colour: 'Green' },
    { key: 'med',  label: 'MEDIUM',   min: 30, colour: 'Amber' },
    { key: 'high', label: 'HIGH',     min: 60, colour: 'Orange' },
    { key: 'crit', label: 'CRITICAL', min: 80, colour: 'Red' }
  ];
  var asc = bands.slice().sort(function (a, b) { return a.min - b.min; });
  var bandRows = asc.map(function (b, i) {
    var max = i < asc.length - 1 ? asc[i + 1].min - 1 : 100;
    return [b.min + ' to ' + max + ' ' + b.label.charAt(0) + b.label.slice(1).toLowerCase(), b.colour];
  });

  /* Scoring rubric shown on the Risk score page. Display only: the real scoring happens
     in the backend or native module, and should follow these same rules. */
  var rubric = {
    title: 'Scoring rubric',
    lead: 'One set of rules for every app, so apps the checker has never seen are scored the same way. A score is the sum of the reasons shown on the right.',
    sections: [
      { title: 'What an app can access', rows: [
        ['Special access: screen reading, device admin, notification access', '25 each'],
        ['Text messages', '15, max 20'],
        ['Contacts and call logs', '10'],
        ['Camera and microphone', '10'],
        ['Location (+5 in the background)', '8'],
        ['Phone and identity', '6'],
        ['Photos and files (all files: 12)', '6'],
        ['Background and auto-start', '4'],
        ['Internet', '2']
      ], note: 'Asked for but not allowed counts at 25%. Each extra permission in the same area counts at 50%.' },
      { title: 'Risky combinations', rows: [
        ['Screen reading + drawing over apps', '+25'],
        ['Read texts + internet + auto-start + outside store', '+20'],
        ['Device admin, not a management app', '+15'],
        ['Notification access + internet', '+15'],
        ['Screen reading + texts', '+15'],
        ['Drawing over apps + installing apps', '+10'],
        ['Any special access + outside store', '+10']
      ] },
      { title: 'Context and trust', rows: [
        ['Simple tool (torch, calculator, wallpaper) with texts, contacts, camera or special access', '+20, at least High'],
        ['Installed outside the Play Store', '+10'],
        ['Built for Android before 6 (2015)', '+10'],
        ['Installed from the Play Store', '\u221210'],
        ['Built into the phone', '\u221215']
      ] },
      { title: 'Bands', rows: bandRows }
    ]
  };

  /* Prevention habits (checklist on the Prevention page). */
  var habits = [
      {
          "title": "Install apps only from the Play Store",
          "detail": "Turn off \"install unknown apps\" for your browser and file manager. Apps from outside a store are the main way harmful ones arrive."
      },
      {
          "title": "Ask whether the app needs what it asks for",
          "detail": "A flashlight does not need texts. A scanner does not need your contacts. If the request does not fit the job, choose Don’t allow."
      },
      {
          "title": "Check the three hard-to-find lists each month",
          "detail": "Screen-reading (accessibility), notification access and draw-over-apps. Anything you do not recognise should be switched off."
      },
      {
          "title": "Set location to \"only while using the app\"",
          "detail": "This stops apps from tracking you while they are closed."
      },
      {
          "title": "Turn on Play Protect scanning",
          "detail": "It checks apps on your phone, including ones from outside the Play Store."
      },
      {
          "title": "Remove apps you have not used in 3 months",
          "detail": "Fewer apps means fewer things that can see your data."
      },
      {
          "title": "Keep Android and your apps updated",
          "detail": "Updates fix the weaknesses that harmful apps rely on."
      },
      {
          "title": "Never share one-time codes",
          "detail": "No real app or bank asks you to read a code out or type it into another app."
      }
  ];

  /* Shortcuts to Android settings screens that are hard to find. `key` is passed to the
     native plugin's openSystemSetting(). */
  var shortcuts = [
    { key: 'accessibility',        label: 'Screen-reading apps' },
    { key: 'overlay',              label: 'Apps that draw over other apps' },
    { key: 'notification_access',  label: 'Apps that read notifications' },
    { key: 'install_unknown_apps', label: 'Apps allowed to install other apps' }
  ];

  AS.content = { categories: categories, bands: bands, rubric: rubric, habits: habits, shortcuts: shortcuts };
})();
