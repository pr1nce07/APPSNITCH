const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = process.argv[2] || path.join(__dirname, '..');
const ctx = { window: {}, console };
vm.createContext(ctx);
for (const f of ['js/data/content.js', 'js/data/normalize.js']) {
  vm.runInContext(fs.readFileSync(dir + '/www/' + f, 'utf8'), ctx, { filename: f });
}
const AS = ctx.window.AppSnitch;
const bandOf = AS.normalize.bandOf;
const cases = [[0,'low'],[29,'low'],[30,'med'],[59,'med'],[60,'high'],[79,'high'],[80,'crit'],[100,'crit']];
let ok = true;
for (const [s, want] of cases) {
  const got = bandOf(s);
  if (got !== want) { ok = false; console.log('FAIL score', s, 'got', got, 'want', want); }
}
console.log(ok ? 'band thresholds OK (matches rubric.json 30/60/80)' : 'band thresholds FAILED');
// Floor check: a score of 45 with engine band HIGH is what the mapper avoids by raising to 60.
console.log('score 60 ->', bandOf(60), '| score 59 ->', bandOf(59));
// Sample scan must still normalize cleanly with no warnings
const sample = JSON.parse(fs.readFileSync(dir + '/docs/sample-scan.json', 'utf8'));
const out = AS.normalize.scanResult(sample);
console.log('sample apps after normalize:', out.apps ? out.apps.length : JSON.stringify(out).slice(0,200));
console.log('sample band counts:', out.apps.map(a => a.name + '=' + a.score + '/' + a.band).join(', '));
process.exit(ok ? 0 : 1);
