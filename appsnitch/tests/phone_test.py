"""AppSnitch phone UI tests (127 checks). Needs: python3, `pip install playwright`, Node 14+, a Chromium for Playwright.
Run from the project root:   python3 tests/phone_test.py
Optional args: <www_dir> <app_root_dir> <screenshots_dir>
Starts a static server for www/ and tools/mock-server.js, then drives Chromium at phone sizes:
layout (no sideways scroll at 320-412 px), scan/risk/prevention flows, Android back button, keyboard,
mock + http + native (stubbed Capacitor plugin) data sources, error states, hostile data (XSS, bad JSON), 300 apps."""
import json, os, subprocess, sys, time, traceback, urllib.request
from playwright.sync_api import sync_playwright

_HERE = os.path.dirname(os.path.abspath(__file__))
WWW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, '..', 'www')
ROOT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(_HERE, '..')
SHOTS = sys.argv[3] if len(sys.argv) > 3 else os.path.join(_HERE, 'out')
os.makedirs(SHOTS, exist_ok=True)
UI, API = 'http://127.0.0.1:8801', 'http://127.0.0.1:8787'
results = []

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ((' :: ' + str(detail)) if (detail and not cond) else ''))

def wait_up(u):
    for _ in range(50):
        try:
            urllib.request.urlopen(u, timeout=1); return
        except Exception:
            time.sleep(0.1)
    try: urllib.request.urlopen(u, timeout=1)
    except urllib.error.HTTPError: return

srv = subprocess.Popen(['python3', '-I', '-m', 'http.server', '8801', '--bind', '127.0.0.1', '--directory', WWW], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
api = subprocess.Popen(['node', os.path.join(ROOT, 'tools', 'mock-server.js'), '8787'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
wait_up(UI + '/index.html'); wait_up(API + '/v1/scan')

def http_cfg(extra_scan='', timeout=15000, method='GET'):
    return "window.APPSNITCH_CONFIG={dataSource:'http',api:{baseUrl:'%s',endpoints:{scan:'/v1/scan%s'},timeoutMs:%d,method:'%s'}};" % (API, extra_scan, timeout, method)

def new_page(browser, w=390, h=844, init=None, mobile=True):
    ctx = browser.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2, is_mobile=mobile, has_touch=mobile)
    page = ctx.new_page()
    errs, reqs = [], []
    page.on('pageerror', lambda e: errs.append('pageerror: %s' % e))
    page.on('console', lambda m: errs.append('console.%s: %s' % (m.type, m.text)) if m.type == 'error' else None)
    page.on('request', lambda r: reqs.append(r.url))
    if init: page.add_init_script(init)
    page._errs, page._reqs, page._ctx = errs, reqs, ctx
    return page

def overflow(page):
    return page.evaluate("()=>({sw:document.documentElement.scrollWidth,iw:window.innerWidth,bw:document.body.scrollWidth})")

def scan_done(page, timeout=15000):
    page.wait_for_selector('#rows .row, #rows .empty, .scan-err', timeout=timeout)

def tab(page, n):
    page.click('.tab:nth-child(%d)' % n)

try:
  with sync_playwright() as p:
    _exe = os.environ.get('CHROMIUM_PATH') or ('/opt/pw-browsers/chromium' if os.path.exists('/opt/pw-browsers/chromium') else None)
    browser = p.chromium.launch(executable_path=_exe, args=['--no-sandbox']) if _exe else p.chromium.launch(args=['--no-sandbox'])

    # ============ 1. MOCK MODE, several phone sizes ============
    for (w, h, label) in [(360, 780, 'android-small'), (390, 844, 'iphone'), (412, 915, 'android-large'), (320, 640, 'tiny')]:
        pg = new_page(browser, w, h)
        pg.goto(UI + '/index.html')
        pg.wait_for_selector('.scan')
        check('[%s] first paint shows scan card + tab bar' % label, pg.locator('.tabs .tab').count() == 3 and pg.locator('.scan').count() == 1)
        if label == 'iphone':
            pg.screenshot(path=SHOTS + '/00-scanning.png')
        pg.click('[data-act=skip]')
        scan_done(pg)
        pg.wait_for_selector('#hero .hero-main')
        check('[%s] scan finished, 8 user apps listed' % label, pg.locator('#rows .row').count() == 8, pg.locator('#rows .row').count())
        o = overflow(pg); check('[%s] scan page: no horizontal overflow' % label, o['sw'] <= o['iw'] and o['bw'] <= o['iw'], o)
        pg.check('#sys'); time.sleep(0.1)
        check('[%s] system apps toggle -> 11 rows' % label, pg.locator('#rows .row').count() == 11, pg.locator('#rows .row').count())
        o = overflow(pg); check('[%s] scan page with system apps: no overflow' % label, o['sw'] <= o['iw'], o)
        pg.uncheck('#sys')
        if label == 'iphone':
            pg.evaluate('window.scrollTo(0,0)'); pg.screenshot(path=SHOTS + '/01-scan-results-top.png')
            pg.evaluate("document.getElementById('rowsh').scrollIntoView()"); pg.screenshot(path=SHOTS + '/02-scan-results-list.png')
        # risk page, every app
        tab(pg, 2); pg.wait_for_selector('.dhead')
        n = pg.locator('.pick').count()
        bad = []
        for i in range(n):
            pg.locator('.pick').nth(i).click(); time.sleep(0.03)
            o = overflow(pg)
            if o['sw'] > o['iw'] or o['bw'] > o['iw']: bad.append((i, o))
        check('[%s] risk page: no overflow for any of %d apps' % (label, n), not bad, bad)
        if label == 'iphone':
            pg.locator('.pick').nth(0).click(); pg.evaluate('window.scrollTo(0,0)'); pg.screenshot(path=SHOTS + '/03-risk-top.png')
            pg.evaluate("document.querySelector('.reasons').scrollIntoView()"); pg.screenshot(path=SHOTS + '/04-risk-reasons.png')
            pg.evaluate("document.querySelector('.cats').scrollIntoView()"); pg.screenshot(path=SHOTS + '/05-risk-access.png')
            pg.click('#rubric summary'); pg.evaluate("document.getElementById('rubric').scrollIntoView()"); pg.screenshot(path=SHOTS + '/06-risk-rubric.png')
        tab(pg, 3); pg.wait_for_selector('.fix')
        o = overflow(pg); check('[%s] prevention page: no overflow' % label, o['sw'] <= o['iw'] and o['bw'] <= o['iw'], o)
        if label == 'iphone':
            pg.evaluate('window.scrollTo(0,0)'); pg.screenshot(path=SHOTS + '/07-prevent-top.png')
            pg.evaluate("document.querySelector('.habits').scrollIntoView()"); pg.screenshot(path=SHOTS + '/08-prevent-habits.png')
        # last element not hidden by tab bar
        pg.evaluate('window.scrollTo(0,document.body.scrollHeight)'); time.sleep(0.1)
        last = pg.evaluate("()=>{const b=[...document.querySelectorAll('.cta .btn')].pop().getBoundingClientRect();const t=document.getElementById('nav').getBoundingClientRect();return {btm:b.bottom,top:t.top}}")
        check('[%s] last button is clear of the tab bar' % label, last['btm'] <= last['top'] + 0.5, last)
        check('[%s] no JS errors' % label, not pg._errs, pg._errs)
        ext = [u for u in pg._reqs if not u.startswith(UI) and not u.startswith('data:')]
        check('[%s] no requests leave the app (offline-safe)' % label, not ext, ext)
        pg._ctx.close()

    # ============ 2. mock mode: behaviour on the iphone size ============
    pg = new_page(browser)
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan'); pg.click('[data-act=skip]'); scan_done(pg)
    check('privacy note says nothing is uploaded', 'Nothing is uploaded' in pg.inner_text('#privnote'), pg.inner_text('#privnote'))
    check('Sample data chip visible in mock mode', pg.locator('#sampletag').is_visible())
    check('rows sorted highest risk first', pg.evaluate("()=>{const s=[...document.querySelectorAll('#rows .row .band b')].map(e=>+e.textContent);return s.every((v,i)=>i==0||s[i-1]>=v)}"))
    top = pg.inner_text('#rows .row:first-child')
    check('top row is Torch Lite 94', 'Torch Lite' in top and '94' in top, top)
    # filters
    chip = lambda f: pg.locator('.fchip[data-f=%s]' % f)
    for f in ['texts', 'camera', 'location', 'screen']:
        cnt = int(chip(f).inner_text().split()[-1]); chip(f).click(); time.sleep(0.05)
        check('filter %s: chip count == rows (%d)' % (f, cnt), pg.locator('#rows .row').count() == cnt, pg.locator('#rows .row').count())
    chip('all').click()
    # search
    pg.fill('#q', 'torch'); time.sleep(0.1)
    check('search "torch" -> 1 row', pg.locator('#rows .row').count() == 1)
    pg.fill('#q', 'zzzz'); time.sleep(0.1)
    check('search no match -> empty message', 'No apps match' in pg.inner_text('#rows'))
    pg.fill('#q', '')
    # keyboard class
    disp = lambda: pg.evaluate("getComputedStyle(document.getElementById('nav')).display")
    pg.focus('#q'); time.sleep(0.05)
    check('focus alone (no keyboard resize) keeps the tab bar', disp() == 'flex')
    pg.set_viewport_size({'width': 390, 'height': 480}); time.sleep(0.15)   # simulate the on-screen keyboard
    check('tab bar hides while the keyboard is open', disp() == 'none')
    pg.set_viewport_size({'width': 390, 'height': 844}); time.sleep(0.15)   # Back button hides keyboard, input keeps focus
    check('tab bar returns when keyboard hides even if input keeps focus', disp() == 'flex' and pg.evaluate("document.activeElement.id") == 'q')
    pg.set_viewport_size({'width': 390, 'height': 480}); time.sleep(0.1); pg.keyboard.press('Enter'); time.sleep(0.1)
    check('Enter blurs the search box', pg.evaluate("document.activeElement.id") != 'q')
    pg.set_viewport_size({'width': 390, 'height': 844}); time.sleep(0.1)
    # row -> risk page
    pg.locator('#rows .row', has_text='QuickPay').click(); pg.wait_for_selector('.dhead')
    check('tapping a row opens its risk page', 'QuickPay Wallet' in pg.inner_text('.dhead h2') and pg.locator('.tab[aria-current=step]').inner_text().strip() == 'Risk score')
    check('QuickPay score ring shows 68', pg.locator('.ring .c b').inner_text() == '68')
    pg.locator('.pick', has_text='Pixel Notes').click()
    check('picker switches app', 'Pixel Notes' in pg.inner_text('.dhead h2'))
    pg.click('#rubric summary'); pg.locator('.pick', has_text='ChatWave').click()
    check('rubric stays open when switching app', pg.evaluate("document.getElementById('rubric').open"))
    # search on risk page
    pg.fill('#q', 'wallp'); time.sleep(0.1)
    check('risk page search narrows the picker', pg.locator('.pick').count() == 1 and 'Wallpaper' in pg.inner_text('.dhead h2'))
    pg.fill('#q', '')
    # Android back button behaviour
    tab(pg, 3); pg.wait_for_selector('.fix')
    pg.go_back(); time.sleep(0.15)
    check('back from Prevention -> Risk score', pg.locator('.tab[aria-current=step]').inner_text().strip() == 'Risk score')
    pg.go_back(); time.sleep(0.15)
    check('back again -> Scan (results kept, no rescan)', pg.locator('.tab[aria-current=step]').inner_text().strip() == 'Scan' and pg.locator('#rows .row').count() == 8)
    # prevention flows
    tab(pg, 3); pg.wait_for_selector('.fix')
    nfix = pg.locator('.fix').count()
    pg.locator('.fix').first.locator('button', has_text='Open settings').first.click(); time.sleep(0.1)
    check('Open settings marks the step Done + toast', pg.locator('.fx.done').count() == 1 and pg.locator('.toast').count() == 1)
    pg.locator('.fix', has_text='Torch Lite').locator('button.danger').click()
    check('Uninstall asks for confirmation', pg.locator('.confirm').count() == 1)
    pg.click('.confirm [data-act=cancel]'); check('Cancel dismisses confirmation', pg.locator('.confirm').count() == 0 and pg.locator('.fix').count() == nfix)
    pg.locator('.fix', has_text='Torch Lite').locator('button.danger').click(); pg.click('.confirm [data-act=confirm]'); time.sleep(0.1)
    check('confirmed uninstall removes the app card', pg.locator('.fix', has_text='Torch Lite').count() == 0)
    check('toast offers Restore', pg.locator('.toast [data-act=restore]').count() == 1)
    pg.screenshot(path=SHOTS + '/09-prevent-toast.png')
    pg.click('.toast [data-act=restore]'); time.sleep(0.1)
    check('Restore brings the app back', pg.locator('.fix', has_text='Torch Lite').count() == 1)
    pg.locator('.links button').first.click(); time.sleep(0.1)
    check('settings shortcut gives feedback', pg.locator('.toast').count() == 1)
    # habits persist
    pg.locator('.habit').nth(0).click(); pg.locator('.habit').nth(2).click()
    check('habit progress 2 of 8', '2 of 8' in pg.inner_text('.prog'))
    pg.reload(); pg.wait_for_selector('.scan'); pg.click('[data-act=skip]'); scan_done(pg); tab(pg, 3)
    check('habit ticks survive a reload', '2 of 8' in pg.inner_text('.prog'))
    # rescan
    pg.click('[data-act=rescan]'); pg.wait_for_selector('.scan .sring')
    check('Scan again returns to Scan and rescans', pg.locator('.tab[aria-current=step]').inner_text().strip() == 'Scan')
    pg.click('[data-act=skip]'); scan_done(pg)
    # direct jump to page 2 before scan
    pg2 = new_page(browser); pg2.goto(UI + '/index.html'); pg2.wait_for_selector('.scan'); tab(pg2, 2)
    check('opening Risk before the scan finishes shows a friendly prompt', 'Scan your phone first' in pg2.inner_text('#view'))
    pg2._ctx.close()
    # tap targets
    small = pg.evaluate("""()=>[...document.querySelectorAll('button,label.sw,label.habit,summary')].filter(e=>e.offsetParent!==null).map(e=>{const r=e.getBoundingClientRect();return {t:(e.className||e.tagName)+':'+(e.textContent||'').trim().slice(0,24),h:Math.round(r.height),w:Math.round(r.width)}}).filter(x=>x.h<44||x.w<44)""")
    check('scan page: tap targets >= 44px high', not small, small)
    for n_, name in [(2, 'risk'), (3, 'prevention')]:
        tab(pg, n_); time.sleep(0.1)
        small = pg.evaluate("""()=>[...document.querySelectorAll('main button,main summary,main label')].filter(e=>e.offsetParent!==null).map(e=>{const r=e.getBoundingClientRect();return {t:(e.className||e.tagName)+':'+(e.textContent||'').trim().slice(0,24),h:Math.round(r.height)}}).filter(x=>x.h<44)""")
        check('%s page: tap targets >= 44px high' % name, not small, small)
    check('mock mode: no JS errors', not pg._errs, pg._errs)
    pg._ctx.close()

    # ============ 3. HTTP MODE (mock backend) ============
    pg = new_page(browser, init=http_cfg())
    pg.goto(UI + '/index.html'); scan_done(pg)
    check('http: scan loaded from backend', pg.locator('#rows .row').count() == 8, pg.locator('#rows .row').count())
    check('http: privacy note says the app list is sent to the server', 'sent to the AppSnitch server' in pg.inner_text('#privnote'), pg.inner_text('#privnote'))
    check('http: no Sample data chip', not pg.locator('#sampletag').is_visible())
    check('http: requested the scan endpoint', any(u.startswith(API + '/v1/scan') for u in pg._reqs))
    tab(pg, 3); pg.wait_for_selector('.fix')
    pg.locator('.fix').first.locator('button', has_text='Open settings').first.click(); time.sleep(0.1)
    check('http (no native plugin): device action explains it needs the Android app', 'Android app' in pg.inner_text('.toast'), pg.inner_text('.toast'))
    check('http: failed device action does not mark step done', pg.locator('.fx.done').count() == 0)
    check('http: no JS errors', not pg._errs, pg._errs); pg._ctx.close()

    for mode, expect in [('500', 'answered 500'), ('bad', 'Invalid scan response'), ('text', None)]:
        pg = new_page(browser, init=http_cfg('?mode=' + mode))
        pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan-err', timeout=8000)
        msg = pg.inner_text('.scan-err')
        check('http mode=%s: readable error card' % mode, 'did not finish' in msg and (expect is None or expect in msg), msg)
        check('http mode=%s: Try again button present' % mode, pg.locator('.scan-err [data-act=rescan]').count() == 1)
        if mode == '500':
            pg.screenshot(path=SHOTS + '/10-error.png')
            tab(pg, 2); check('http error: other tabs show the error, not a blank page', 'did not finish' in pg.inner_text('#view'))
        pg._ctx.close()
    pg = new_page(browser, init=http_cfg('?mode=empty'))
    pg.goto(UI + '/index.html'); scan_done(pg)
    check('http empty list: friendly empty state', 'No apps were found' in pg.inner_text('#rows'), pg.inner_text('#rows'))
    tab(pg, 2); check('empty list: risk page prompts instead of crashing', 'No apps to show' in pg.inner_text('#view'))
    pg._ctx.close()
    pg = new_page(browser, init=http_cfg('?mode=slow', timeout=800))
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan-err', timeout=8000)
    check('http slow server: timeout message', 'longer than' in pg.inner_text('.scan-err'), pg.inner_text('.scan-err'))
    pg._ctx.close()
    # retry recovers: first call 500 then ok
    pg = new_page(browser, init=http_cfg('?mode=500'))
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan-err')
    pg.evaluate("window.AppSnitch.config.api.endpoints.scan='/v1/scan'")
    pg.click('.scan-err [data-act=rescan]'); scan_done(pg, 8000)
    check('Try again recovers after a failed scan', pg.locator('#rows .row').count() == 8)
    pg._ctx.close()
    pg = new_page(browser, init="window.APPSNITCH_CONFIG={dataSource:'http',api:{baseUrl:'http://127.0.0.1:9',timeoutMs:3000}};")
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan-err', timeout=8000)
    check('http server unreachable: readable message (CORS/address hint)', 'Could not reach the server' in pg.inner_text('.scan-err'), pg.inner_text('.scan-err'))
    pg._ctx.close()
    # POST with body
    pg = new_page(browser, init=http_cfg(method='POST').replace("timeoutMs", "getBody:function(){return Promise.resolve({apps:[{packageName:'a.b'}]})},timeoutMs"))
    pg.goto(UI + '/index.html'); scan_done(pg)
    check('http POST with getBody works', pg.locator('#rows .row').count() == 8)
    pg._ctx.close()

    # ============ 4. HOSTILE / ODD DATA through the real normalizer ============
    def inject(payload):
        js = "window.APPSNITCH_CONFIG={dataSource:'http',api:{baseUrl:'%s'}};" % API
        js += "window.fetch=function(){return Promise.resolve({ok:true,json:function(){return Promise.resolve(%s)}})};" % json.dumps(payload)
        return js
    big = {'schemaVersion': 1, 'apps': [
        {'packageName': 'x.y<img src=x onerror=window.__pwn=1>', 'name': '<script>window.__pwn=1</script>"><b>BOLD</b>', 'versionName': '<i>1</i>', 'iconColor': 'red;background:url(http://evil.test/x)', 'iconLetter': '<u>',
         'installSource': 'sideloaded', 'score': 140,
         'reasons': [{'kind': 'access', 'category': 'texts', 'title': '<img src=x onerror=window.__pwn=1>', 'detail': '"><svg onload=window.__pwn=1>', 'points': 15}, None, 5, {'kind': 'weird', 'category': 'nope', 'points': 'abc'}],
         'access': {'texts': [{'label': '<b>x</b>', 'permission': 'READ_SMS', 'granted': True}, None], 'bogus': [1], 'camera': 'notalist'},
         'fixes': [{'text': '<script>window.__pwn=1</script>', 'action': 'uninstall'}, None, {'action': 'rm -rf'}]},
        {'packageName': 'dup', 'id': 'same'}, {'packageName': 'dup2', 'id': 'same'},
        None, 'str', {'name': 'no package'}, {'packageName': 'neg', 'score': -50}, {'packageName': 'nan', 'score': 'high'},
        {'packageName': 'com.a.very.long.package.name.that.keeps.going.and.going.forever.and.ever.example', 'name': 'A really really really long application name that should wrap and never push the layout wider than the phone screen', 'score': 80,
         'reasons': [{'kind': 'access', 'category': 'texts', 'title': 'Averyveryveryveryveryveryveryveryveryveryverylongunbrokenwordthatcouldoverflowthecard', 'detail': 'x', 'points': 15}]},
    ]}
    pg = new_page(browser, init=inject(big))
    pg.goto(UI + '/index.html'); scan_done(pg)
    check('hostile data: page renders, no crash', pg.locator('#rows .row').count() >= 4 and not [e for e in pg._errs if 'pageerror' in e], pg._errs)
    pg.check('#sys')
    check('hostile data: no script ran (XSS)', pg.evaluate('window.__pwn') is None)
    check('hostile data: markup shown as text', pg.locator('#rows b', has_text='BOLD').count() == 0)
    check('hostile data: iconColor cannot inject CSS', 'evil.test' not in pg.content())
    check('hostile data: duplicate id / missing package skipped', pg.locator('#rows .row').count() == 5, pg.locator('#rows .row').count())
    check('hostile data: warnings counted on screen', pg.locator('.warn-note').count() == 1, pg.locator('.warn-note').count())
    sc = pg.evaluate("[...document.querySelectorAll('#rows .row .band b')].map(e=>+e.textContent)")
    check('scores clamped to 0-100 (140->100, -50->0, "high"->sum)', 100 in sc and 0 in sc and all(0 <= s <= 100 for s in sc), sc)
    o = overflow(pg); check('long names/words: no horizontal overflow (scan)', o['sw'] <= o['iw'], o)
    pg.locator('#rows .row', has_text='really really').click(); pg.wait_for_selector('.dhead')
    o = overflow(pg); check('long names/words: no horizontal overflow (risk)', o['sw'] <= o['iw'], o)
    check('long names: picker pills stay <= 260px wide', pg.evaluate("Math.max(...[...document.querySelectorAll('.pick')].map(e=>e.getBoundingClientRect().width))") <= 261)
    pg.evaluate('window.scrollTo(0,0)'); pg.screenshot(path=SHOTS + '/11-long-names.png')
    tab(pg, 3); pg.wait_for_selector('.fix'); o = overflow(pg); check('long names: no overflow (prevention)', o['sw'] <= o['iw'], o)
    check('hostile data: still no script ran', pg.evaluate('window.__pwn') is None)
    pg._ctx.close()

    # 300 apps
    many = {'schemaVersion': 1, 'apps': [{'packageName': 'com.test.app%d' % i, 'name': 'App %d' % i, 'score': i % 100, 'reasons': [{'kind': 'access', 'category': 'network', 'title': 'Net', 'detail': 'd', 'points': 2}]} for i in range(300)]}
    pg = new_page(browser, init=inject(many))
    t0 = time.time(); pg.goto(UI + '/index.html'); scan_done(pg); dt = time.time() - t0
    check('300 apps render in < 3s (%.2fs)' % dt, dt < 3 and pg.locator('#rows .row').count() == 300)
    tab(pg, 2); pg.wait_for_selector('.dhead'); check('300 apps: risk picker usable', pg.locator('.pick').count() == 300)
    pg._ctx.close()
    # minimal app (only packageName)
    pg = new_page(browser, init=inject({'schemaVersion': 1, 'apps': [{'packageName': 'only.pkg'}]}))
    pg.goto(UI + '/index.html'); scan_done(pg); tab(pg, 2); pg.wait_for_selector('.dhead'); tab(pg, 3); pg.wait_for_selector('.lowline')
    check('app with only packageName renders on all 3 pages', not [e for e in pg._errs if 'pageerror' in e], pg._errs)
    pg._ctx.close()

    # ============ 5. NATIVE MODE (stubbed Capacitor plugin) ============
    native = """
    window.__calls=[];window.__listeners={};
    window.Capacitor={Plugins:{AppScanner:{
      addListener:function(n,cb){window.__listeners[n]=cb;return Promise.resolve({remove:function(){window.__removed=true}})},
      scan:function(){return new Promise(function(res){var total=5;var i=0;var t=setInterval(function(){i++;window.__listeners.scanProgress&&window.__listeners.scanProgress({done:i,total:total,current:'com.pkg.'+i});
        if(i>=total){clearInterval(t);fetch('%s/v1/scan').then(function(r){return r.json()}).then(res)}},150)})},
      uninstall:function(a){window.__calls.push(['uninstall',a]);return window.__rejectUninstall?Promise.reject(new Error('User cancelled')):Promise.resolve()},
      openAppSettings:function(a){window.__calls.push(['openAppSettings',a]);return Promise.resolve()},
      openSystemSetting:function(a){window.__calls.push(['openSystemSetting',a]);return Promise.resolve()}
    }}};
    window.APPSNITCH_CONFIG={dataSource:'native'};
    """ % API
    pg = new_page(browser, init=native)
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan .sring')
    time.sleep(0.5)
    check('native: live progress shown (x of 5 apps)', 'of 5' in pg.inner_text('.sring') or 'of 5' in pg.inner_text('.scan'), pg.inner_text('.scan')[:80])
    check('native: current package shown while scanning', 'com.pkg.' in pg.inner_text('.cur'), pg.inner_text('.cur'))
    check('native: no Skip button (real scan)', pg.locator('[data-act=skip]').count() == 0)
    scan_done(pg)
    check('native: progress listener removed after scan', pg.evaluate('window.__removed') is True)
    check('native: privacy note says nothing uploaded', 'Nothing is uploaded' in pg.inner_text('#privnote'))
    tab(pg, 3); pg.wait_for_selector('.fix')
    pg.locator('.fix', has_text='QuickPay').locator('button', has_text='Open settings').first.click(); time.sleep(0.15)
    calls = pg.evaluate('window.__calls')
    check('native: openAppSettings called with packageName', calls and calls[0][0] == 'openAppSettings' and calls[0][1] == {'packageName': 'com.quickpay.wallet'}, calls)
    check('native: step marked Done after success', pg.locator('.fx.done').count() == 1)
    pg.locator('.links button').nth(1).click(); time.sleep(0.15)
    calls = pg.evaluate('window.__calls'); check('native: openSystemSetting called with key', calls[-1] == ['openSystemSetting', {'setting': 'overlay'}], calls)
    # return from settings -> hint
    pg.evaluate("Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true});document.dispatchEvent(new Event('visibilitychange'))"); time.sleep(0.1)
    check('native: coming back from Settings hints to rescan', 'Scan again' in pg.inner_text('.toast'), pg.inner_text('.toast'))
    # uninstall cancelled by user
    pg.evaluate('window.__rejectUninstall=true')
    pg.locator('.fix', has_text='QuickPay').locator('button.danger').click(); pg.click('.confirm [data-act=confirm]'); time.sleep(0.2)
    check('native: cancelled uninstall keeps the app', pg.locator('.fix', has_text='QuickPay').count() == 1 and 'cancelled' in pg.inner_text('.toast').lower(), pg.inner_text('.toast'))
    pg.evaluate('window.__rejectUninstall=false')
    pg.locator('.fix', has_text='QuickPay').locator('button.danger').click(); pg.click('.confirm [data-act=confirm]'); time.sleep(0.2)
    check('native: real uninstall removes the app, no Restore button', pg.locator('.fix', has_text='QuickPay').count() == 0 and pg.locator('.toast [data-act=restore]').count() == 0)
    calls = pg.evaluate('window.__calls'); check('native: uninstall called with packageName', calls[-1] == ['uninstall', {'packageName': 'com.quickpay.wallet'}], calls[-1])
    check('native: no JS errors', not [e for e in pg._errs if 'pageerror' in e], pg._errs)
    pg._ctx.close()
    pg = new_page(browser, init="window.APPSNITCH_CONFIG={dataSource:'native'};")
    pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan-err')
    check('native source without plugin: clear message', 'AppScanner' in pg.inner_text('.scan-err'), pg.inner_text('.scan-err'))
    pg._ctx.close()

    # ============ 6. misc ============
    pg = new_page(browser); pg.goto(UI + '/index.html?fail=1'); pg.wait_for_selector('.scan-err'); check('?fail=1 preview shows error card', 'did not finish' in pg.inner_text('.scan-err')); pg._ctx.close()
    pg = new_page(browser, 1280, 800, mobile=False); pg.goto(UI + '/index.html'); pg.wait_for_selector('.scan'); pg.click('[data-act=skip]'); scan_done(pg)
    w = pg.evaluate("()=>({app:document.getElementById('app').getBoundingClientRect().width,tabs:document.getElementById('nav').getBoundingClientRect().width})")
    check('desktop browser still shows ONE phone-width column (<=480px)', w['app'] <= 480 and w['tabs'] <= 480, w)
    pg.screenshot(path=SHOTS + '/12-on-desktop-browser.png'); pg._ctx.close()
    browser.close()
except Exception:
    traceback.print_exc(); check('test run completed without harness error', False)
finally:
    srv.terminate(); api.terminate()

failed = [r for r in results if not r[1]]
print('\n==== %d checks, %d passed, %d failed ====' % (len(results), len(results) - len(failed), len(failed)))
for n, _, d in failed: print('FAILED:', n, '::', d)
json.dump([{'name': n, 'ok': ok} for n, ok, _ in results], open(os.path.join(SHOTS, 'results.json'), 'w'))
sys.exit(1 if failed else 0)
