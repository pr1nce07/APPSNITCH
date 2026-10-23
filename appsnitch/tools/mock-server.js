#!/usr/bin/env node
/* AppSnitch mock backend. No dependencies. Run:  node tools/mock-server.js [port]
   Serves docs/sample-scan.json at GET or POST /v1/scan, with CORS, so the UI can be tested against
   `dataSource: 'http'` before the real backend exists:

     www/index.html?source=http&api=http://localhost:8787

   Test switches (append to the endpoint, e.g. endpoints.scan = '/v1/scan?mode=slow'):
     mode=ok (default)  the sample scan
     mode=500           HTTP 500
     mode=slow          answers after 3 seconds (use to try the timeout message)
     mode=empty         { schemaVersion: 1, apps: [] }
     mode=bad           JSON that is not a scan result
     mode=text          not JSON at all
   POST bodies (for example the raw app list) are echoed to the console. */
var http = require('http'), fs = require('fs'), path = require('path'), url = require('url');
var port = Number(process.argv[2]) || 8787;
var sample = path.join(__dirname, '..', 'docs', 'sample-scan.json');

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, Accept');
}
function send(res, code, type, body) { res.writeHead(code, { 'Content-Type': type }); res.end(body); }

http.createServer(function (req, res) {
  cors(res);
  var u = url.parse(req.url, true);
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  if (u.pathname !== '/v1/scan') return send(res, 404, 'application/json', '{"error":"not found"}');
  var chunks = [];
  req.on('data', function (c) { chunks.push(c); });
  req.on('end', function () {
    if (chunks.length) console.log('[mock] ' + req.method + ' body:', Buffer.concat(chunks).toString().slice(0, 500));
    var mode = u.query.mode || 'ok';
    function ok() {
      var scan = JSON.parse(fs.readFileSync(sample, 'utf8'));
      scan.scannedAt = new Date().toISOString();
      send(res, 200, 'application/json', JSON.stringify(scan));
    }
    if (mode === '500') return send(res, 500, 'application/json', '{"error":"boom"}');
    if (mode === 'empty') return send(res, 200, 'application/json', '{"schemaVersion":1,"apps":[]}');
    if (mode === 'bad') return send(res, 200, 'application/json', '{"hello":"world"}');
    if (mode === 'text') return send(res, 200, 'text/plain', 'not json');
    if (mode === 'slow') return setTimeout(ok, 3000);
    ok();
  });
}).listen(port, function () { console.log('AppSnitch mock backend on http://localhost:' + port + '/v1/scan'); });
