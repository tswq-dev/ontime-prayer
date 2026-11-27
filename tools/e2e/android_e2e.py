#!/usr/bin/env python3
"""A whole first use of the app on an Android emulator, with a picture at each step (android-e2e.yml).

Drives the system with adb (permissions, the clock, the lock, the volume key, the notification shade, the home screen) and the
page through the WebView's DevTools socket (debug builds). Times come from umm_al_qura_times.py, so the adhan and the alarm are
met by moving the clock to just before them. Everything is written to out/: NN-step.png, steps.md (what was done and seen).
"""
import json, os, re, subprocess, sys, time, urllib.request
from datetime import date, datetime, timedelta
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))
import umm_al_qura_times as uq

PKG = 'com.tswq.ontimeprayer'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'out'
APK = sys.argv[1] if len(sys.argv) > 1 else 'ontime-prayer.apk'
LAT, LON = 21.426666, 39.831666  # Makkah
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, 'steps.md'), 'w')
n = 0


def sh(cmd, check=False, timeout=60):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        note('- (timed out: %s)' % cmd[:80]); return ''
    if check and r.returncode:
        raise RuntimeError(cmd + '\n' + r.stderr)
    return r.stdout.strip()


def adb(c, **k): return sh('adb ' + c, **k)
def ash(c, **k): return adb('shell ' + json.dumps(c), **k)


def note(t):
    print(t, flush=True); log.write(t + '\n'); log.flush()


def shot(name, say=''):
    global n
    n += 1
    f = '%02d-%s.png' % (n, name)
    sh('adb exec-out screencap -p > ' + os.path.join(OUT, f))
    note('\n### %02d %s\n%s\n![](%s)' % (n, name, say, f))


def dump():
    # the live sky never idles, so the dump may not finish: give it 20 s
    ash('rm -f /sdcard/ui.xml'); ash('uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1', timeout=20)
    x = adb('exec-out cat /sdcard/ui.xml')
    try:
        return ET.fromstring(x[x.find('<'):]) if '<' in x else None
    except ET.ParseError:
        return None


def nodes():
    r = dump()
    out = []
    if r is None: return out
    for e in r.iter('node'):
        b = re.findall(r'\d+', e.get('bounds', ''))
        if len(b) == 4:
            out.append(((e.get('text') or '') + ' ' + (e.get('content-desc') or ''), [int(v) for v in b], e.get('clickable') == 'true', e.get('resource-id') or ''))
    return out


def tap_xy(x, y): ash('input tap %d %d' % (x, y))


def tap_node(pat, wait=6):
    """Taps the first system node whose text matches [pat] (permission dialogs, the shade, the launcher)."""
    end = time.time() + wait
    while time.time() < end:
        for t, b, c, rid in nodes():
            if re.search(pat, t.strip(), re.I):
                tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); time.sleep(1.2); return t.strip()
        time.sleep(1)
    return None


# ---- the page, through DevTools
_ws = None


def page():
    global _ws
    import websocket
    pid = ash('pidof ' + PKG)
    if not pid: return None
    adb('forward --remove-all')
    adb('forward tcp:9222 localabstract:webview_devtools_remote_' + pid.split()[0])
    for _ in range(10):
        try:
            tabs = json.loads(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=5).read())
            t = [t for t in tabs if t.get('type') == 'page' and 'about:blank' not in t.get('url', '')]
            if t:
                _ws = websocket.create_connection(t[0]['webSocketDebuggerUrl'], timeout=20, suppress_origin=True)
                return _ws
        except Exception as e:
            time.sleep(2)
    return None


_id = 0


def js(expr):
    global _id, _ws
    for attempt in range(2):
        try:
            if _ws is None: page()
            _id += 1
            _ws.send(json.dumps({'id': _id, 'method': 'Runtime.evaluate', 'params': {'expression': expr, 'returnByValue': True, 'awaitPromise': True}}))
            while True:
                m = json.loads(_ws.recv())
                if m.get('id') == _id:
                    return m.get('result', {}).get('result', {}).get('value')
        except Exception as e:
            _ws = None; time.sleep(2)
    return None


CLICK = r"""(re => { re = new RegExp(re); const els = [...document.querySelectorAll('button, [role=button], .opt, .srow, label, a')]
  .filter(e => e.offsetParent && e.getBoundingClientRect().height > 0 && re.test((e.textContent || '').trim()));
  els.slice(0, 1).forEach(e => e.scrollIntoView({ block: 'center' }));
  const top = els.filter(e => { const r = e.getBoundingClientRect(), h = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return h && (e === h || e.contains(h)); });
  const e = top[0]; if (!e) return null; e.click(); return (e.textContent || '').trim().slice(0, 40); })(%s)"""


def click(pat, wait=5):
    end = time.time() + wait
    while time.time() < end:
        r = js(CLICK % json.dumps(pat))
        if r: time.sleep(1.2); return r
        time.sleep(1)
    return None


def visible_text():
    return js("(() => [...document.querySelectorAll('.sheet.show, #tourSheet.show, #coach')].filter(e => e.offsetParent && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).opacity > 0.1).map(e => e.innerText).join('\\n---\\n').slice(0, 1500))()") or ''


def set_clock(dt):
    # dt is Riyadh time; the phone is set in UTC to leave no doubt
    ash('date -u %s' % (dt - timedelta(hours=3)).strftime('%m%d%H%M%Y.%S'))
    note('- the clock set to %s' % dt.strftime('%Y-%m-%d %H:%M:%S'))


def wake():
    ash('input keyevent KEYCODE_WAKEUP'); time.sleep(1); ash('wm dismiss-keyguard'); time.sleep(1)


def sleep_screen():
    ash('input keyevent KEYCODE_SLEEP'); time.sleep(2)


def main():
    note('# A first use of the app, on an Android emulator\n')
    adb('root'); time.sleep(3); adb('wait-for-device')
    ash('settings put global auto_time 0'); ash('settings put global auto_time_zone 0')
    note('- the phone\'s time zone: %s' % ash('getprop persist.sys.timezone'))
    ash('settings put system screen_off_timeout 600000')
    # an Arabic phone, as most users have
    ash('setprop persist.sys.locale ar-SA'); ash('stop'); time.sleep(2); ash('start')
    for _ in range(90):
        if ash('getprop sys.boot_completed') == '1' and 'package:' in ash('pm path android'): break
        time.sleep(2)
    # until the launcher is up (the first run opened the app while the phone was still starting)
    for _ in range(90):
        if 'launcher' in ash('dumpsys window | grep -E "mCurrentFocus|mFocusedApp"').lower(): break
        ash('input keyevent KEYCODE_WAKEUP'); ash('wm dismiss-keyguard'); time.sleep(2)
    time.sleep(5)
    adb('emu geo fix %f %f' % (LON, LAT))
    # the morning of tomorrow, a quiet start
    day = date.today() + timedelta(days=1)
    t = uq.prayer_times(day, LAT, LON)
    hm = lambda k: datetime.combine(day, datetime.strptime(t[k], '%H:%M').time())
    note('- the day: %s, Makkah: %s' % (day, t))
    set_clock(hm('dhuhr') - timedelta(minutes=50))
    adb('install -r -g ' + APK, check=True, timeout=180)
    # the system's own permission dialogs are Google's: granted here, as a user who says yes would
    for pm in ['POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION']: ash('pm grant %s android.permission.%s' % (PKG, pm))
    ash('dumpsys deviceidle whitelist +' + PKG)
    for op in ['SCHEDULE_EXACT_ALARM', 'USE_FULL_SCREEN_INTENT']: ash('appops set %s %s allow' % (PKG, op))
    note('- installed, with notifications, location, exact alarms, full-screen and no battery limit allowed')

    # 1. first opening, as a new user: through every screen of the welcome and the setup, a picture of each
    for _ in range(5):
        ash('monkey -p %s -c android.intent.category.LAUNCHER 1' % PKG); time.sleep(8)
        if ash('pidof ' + PKG) and PKG in ash('dumpsys window | grep mCurrentFocus'): break
    shot('first-open', 'The app as it first opens.')
    ok, title = False, ''
    for _ in range(15):
        ok = page() is not None; title = js('document.title') or ''
        if ok and title: break
        time.sleep(3)
    note('- the page reached through DevTools: %s, title: %s' % (ok, title))
    time.sleep(4)
    seen = set(); seen_txt = {}
    for i in range(40):
        # system permission dialogs first
        txt = visible_text()
        if not txt.strip():
            break
        key = txt[:120]
        rep_n = seen_txt.get(key, 0) + 1; seen_txt[key] = rep_n
        if rep_n > 3:
            note('- the same screen 3 times, moving on'); js("document.getElementById('back') && document.getElementById('back').click()"); break
        # in the alarm step choose an alarm at the adhan, so it can be met later
        if 'منبّه للفجر' in txt and 'alarm' not in seen:
            seen.add('alarm'); click(r'^عند الأذان$'); shot('setup-alarm', 'The Fajr alarm step: chose «عند الأذان».\n\n```\n' + txt[:600] + '\n```')
        # a request to allow something: allow it, as most users do
        r = click(r'^(التالي|ابدأ|متابعة|موافق|تم|سماح|اسمح|السماح|نعم|حسناً|ابدأ الآن|Next|Start|OK|Done|Allow)$', wait=2)
        shot('setup', 'On screen:\n```\n' + txt[:700] + '\n```\nTapped: ' + str(r))
        if not r:
            # no button found: tap the first choice on the sheet
            r = js("(() => { const b = document.querySelector('.sheet.show button, #tourSheet.show button'); if (!b) return null; b.click(); return b.textContent.trim().slice(0,40); })()")
            note('- no next button; tapped the first choice: ' + str(r)); time.sleep(1.5)
            if not r: break
    shot('main', 'The main screen after the welcome.')
    # the live sky on the emulator's software GPU takes all the machine (screenshots stopped answering): the plain design from here
    js("document.getElementById('setBtn').click()"); time.sleep(1.5)
    js("document.querySelector('#setSheet .srow[data-p=scr]').click()"); time.sleep(1.5)
    note('- plain design: %s' % click(r'^بسيط$', 4)); time.sleep(1)
    js("document.getElementById('back').click()"); time.sleep(1.5)
    shot('main-plain', 'The main screen in the plain design (for the emulator).')

    # 2. every settings page
    click(r'^$|تخصيص الشاشة', 2); js("document.getElementById('setBtn').click()"); time.sleep(1.5)
    shot('settings', 'The settings.')
    rows = js("[...document.querySelectorAll('#setSheet .srow')].filter(b => b.offsetParent).map(b => b.dataset.p)") or []
    for p in rows:
        js("document.querySelector('#setSheet .srow[data-p=%s]').click()" % p); time.sleep(1.5)
        shot('settings-' + p, 'Settings page «%s».' % p)
        js("window.scrollTo(0,0); const s = document.getElementById('setSheet'); s.scrollTop = s.scrollHeight"); time.sleep(1)
        shot('settings-' + p + '-end', 'The end of that page.')
        js("(document.querySelector('#setSheet .sback, #setSheet [data-back]') || {click(){}}).click()"); time.sleep(1)
        js("history.back && 0")
    js("document.getElementById('back').click()"); time.sleep(1)

    # 3. the alarms icon: a new alarm with what it suggests, saved
    js("document.getElementById('almBtn').click()"); time.sleep(1.5)
    shot('alarms', 'The alarms, from their icon.')
    js("(document.querySelector('[data-amadd]') || {click(){}}).click()"); time.sleep(1.5)
    shot('alarm-new', 'A new alarm, as suggested.')
    al_title = js("(document.getElementById('amBody') || {}).innerText") or ''
    note('```\n' + al_title[:400] + '\n```')
    js("(document.querySelector('[data-amsave]') || {click(){}}).click()"); time.sleep(1.5)
    shot('alarms-saved', 'The alarms after saving it.')
    js("document.getElementById('back').click()"); time.sleep(1)

    # 4. widgets on the home screen: the two with buttons and two others, pinned by the app as its gallery does
    for w in ['actions', 'prayed', 'table', 'window']:
        js("window.Android && Android.pinWidget('%s')" % w); time.sleep(2.5)
        a = tap_node(r'^(Add|إضافة|Add automatically|إضافة تلقائيًا)$', wait=5)
        note('- pin %s: %s' % (w, a))
    ash('input keyevent KEYCODE_HOME'); time.sleep(3)
    shot('home-widgets', 'The home screen with the widgets the app pinned.')
    ash('input swipe 540 1500 540 600 300'); time.sleep(1); ash('input keyevent KEYCODE_HOME'); time.sleep(2)
    # press «صلّيت»-side of the buttons widget: its first button (bottom row) if it is found
    ws = [b for t, b, c, rid in nodes() if 'appwidget' in rid.lower() or 'widget' in rid.lower()]
    note('- widget hosts seen: %s' % ws[:6])
    if ws:
        b = ws[0]; tap_xy(b[2] - (b[2] - b[0]) // 6, b[3] - (b[3] - b[1]) // 5); time.sleep(3)
        shot('home-widget-press', 'After pressing the first button of the first widget.')

    # 5. the adhan of Asr, with the screen locked
    set_clock(hm('asr') - timedelta(seconds=75)); sleep_screen()
    time.sleep(85)
    shot('adhan-locked', 'Asr time came with the screen locked (75 s after locking).')
    time.sleep(10); shot('adhan-10s', '10 s later.')
    ash('input keyevent KEYCODE_VOLUME_DOWN'); time.sleep(3)
    shot('adhan-after-volume', 'After pressing volume down.')
    wake(); ash('cmd statusbar expand-notifications'); time.sleep(2)
    shot('shade-after-adhan', 'The notification shade after the adhan.')
    ash('cmd statusbar collapse'); time.sleep(1)

    # 6. the Fajr alarm of the next day, if one was set in the setup
    nd = day + timedelta(days=1); tf = uq.prayer_times(nd, LAT, LON)
    fajr = datetime.combine(nd, datetime.strptime(tf['fajr'], '%H:%M').time())
    set_clock(fajr - timedelta(minutes=55)); time.sleep(5); wake()
    ash('cmd statusbar expand-notifications'); time.sleep(2)
    shot('shade-before-alarm', 'Half an hour before the Fajr alarm: the shade (the advance notice, if any).')
    ash('cmd statusbar collapse')
    m = re.search(r'(قبل|بعد) أذان الفجر بـ\s*(\d+)', al_title.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')))
    at = fajr + timedelta(minutes=(-1 if m and m.group(1) == 'قبل' else 1) * int(m.group(2))) if m else fajr
    note('- the alarm should go off at %s' % at.strftime('%H:%M'))
    set_clock(at - timedelta(seconds=70)); sleep_screen()
    time.sleep(80)
    shot('alarm-locked', 'The alarm time with the screen locked.')
    ash('input keyevent KEYCODE_VOLUME_UP'); time.sleep(3)
    shot('alarm-after-volume', 'After pressing volume up (snooze, by the app\'s rule).')
    wake(); ash('cmd statusbar expand-notifications'); time.sleep(2)
    shot('shade-after-alarm', 'The shade after snoozing.')
    ash('cmd statusbar collapse')
    # the app again, after all this
    ash('monkey -p %s -c android.intent.category.LAUNCHER 1' % PKG); time.sleep(10)
    shot('app-after', 'The app opened again at the end.')
    note('\n## Log lines from the app\n```\n' + sh("adb logcat -d -t 400 | grep -iE 'ontime|chromium|AndroidRuntime' | tail -80") + '\n```')


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        note('\n**The run stopped:** `%r`' % e)
        try: shot('stopped', 'Where it stopped.')
        except Exception: pass
        raise
