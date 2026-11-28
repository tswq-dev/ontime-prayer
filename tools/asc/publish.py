#!/usr/bin/env python3
"""Fills the App Store page of «الفجر الصادق» through the App Store Connect API (run by .github/workflows/asc.yml).

Texts come from texts.py, the iPhone 6.9" pictures from store/ios/iphone69-*.png. Each step stands alone: a failed step is
reported and the rest still run. With SUBMIT=1 it also sends the version for review (App Privacy and the price are set
on the website, since the API has no way to set App Privacy).
"""
import glob, hashlib, json, os, sys, time, textwrap, re, urllib.request, urllib.error
import jwt

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from texts import AR, EN, REVIEW_NOTES

APP = os.environ.get('ASC_APP_ID', '6837875909')
API = 'https://api.appstoreconnect.apple.com'
SITE = 'https://ontime-prayer.pages.dev'
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
KID, ISS = os.environ['ASC_KEY_ID'], os.environ['ASC_ISSUER_ID']
_b = ''.join(re.findall(r'[A-Za-z0-9+/=]+', re.sub(r'-----[^-]+-----', '', os.environ['ASC_KEY_P8'])))
KEY = '-----BEGIN PRIVATE KEY-----\n' + '\n'.join(textwrap.wrap(_b, 64)) + '\n-----END PRIVATE KEY-----\n'

_tok = [None, 0]
def tok():
    if time.time() > _tok[1] - 60:
        now = int(time.time())
        _tok[0] = jwt.encode({'iss': ISS, 'iat': now, 'exp': now + 1100, 'aud': 'appstoreconnect-v1'}, KEY,
                             algorithm='ES256', headers={'kid': KID, 'typ': 'JWT'})
        _tok[1] = now + 1100
    return _tok[0]

class ApiError(Exception):
    def __init__(s, code, body):
        s.code, s.body = code, body
        try: s.errs = json.loads(body).get('errors', [])
        except Exception: s.errs = []
        super().__init__(f'{code}: ' + ('; '.join(f"{e.get('code')} {e.get('detail', '')}" for e in s.errs) or body[:400]))

def call(method, path, body=None):
    url = path if path.startswith('http') else API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + tok(), 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            t = r.read()
            return json.loads(t) if t else {}
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode('utf-8', 'replace'))

def rel(typ, id_): return {'data': {'type': typ, 'id': id_}}

REPORT, ERRORS = [], []
def step(name):
    def wrap(fn):
        def run(*a, **k):
            try:
                out = fn(*a, **k)
                REPORT.append(f'✓ {name}' + (f': {out}' if out else ''))
                print(REPORT[-1], flush=True)
                return out
            except Exception as e:
                ERRORS.append(f'✗ {name}: {e}')
                print(ERRORS[-1], flush=True)
        return run
    return wrap

def state(a): return a.get('appVersionState') or a.get('appStoreState') or a.get('state')

# ---------- the version, the app info ----------
def version():
    vs = call('GET', f'/v1/apps/{APP}/appStoreVersions?filter[platform]=IOS&limit=20')['data']
    edit = [v for v in vs if state(v['attributes']) in
            ('PREPARE_FOR_SUBMISSION', 'DEVELOPER_REJECTED', 'REJECTED', 'METADATA_REJECTED', 'INVALID_BINARY')]
    v = (edit or vs)[0]
    print('version', v['attributes']['versionString'], state(v['attributes']), v['id'])
    return v

def app_info():
    infos = call('GET', f'/v1/apps/{APP}/appInfos')['data']
    live = ('READY_FOR_DISTRIBUTION', 'READY_FOR_SALE', 'REPLACED_WITH_NEW_INFO')
    i = next((x for x in infos if state(x['attributes']) not in live), infos[0])
    print('appInfo', state(i['attributes']), i['id'])
    return i

@step('the version: copyright, release after approval')
def set_version(vid):
    call('PATCH', f'/v1/appStoreVersions/{vid}', {'data': {'type': 'appStoreVersions', 'id': vid, 'attributes': {
        'copyright': '2026 Ibrahim Alzahrani', 'releaseType': 'AFTER_APPROVAL'}}})

def put_loc(kind, parent_rel, parent_id, existing, locale, attrs):
    """PATCH the locale's localization, or create it."""
    cur = next((x for x in existing if x['attributes']['locale'] == locale), None)
    if cur:
        call('PATCH', f'/v1/{kind}/{cur["id"]}', {'data': {'type': kind, 'id': cur['id'], 'attributes': attrs}})
        return cur['id'], 'updated'
    parent_type = {'appInfo': 'appInfos', 'appStoreVersion': 'appStoreVersions'}[parent_rel]
    r = call('POST', f'/v1/{kind}', {'data': {'type': kind, 'attributes': dict(attrs, locale=locale),
                                               'relationships': {parent_rel: rel(parent_type, parent_id)}}})
    return r['data']['id'], 'created'

@step('name, subtitle, privacy link (Arabic)')
def info_ar(iid, locs):
    return put_loc('appInfoLocalizations', 'appInfo', iid, locs, 'ar-SA',
                   {'name': AR['name'], 'subtitle': AR['subtitle'], 'privacyPolicyUrl': SITE + '/privacy'})[1]

@step('name, subtitle, privacy link (English)')
def info_en(iid, locs):
    attrs = {'name': EN['name'], 'subtitle': EN['subtitle'], 'privacyPolicyUrl': SITE + '/privacy'}
    try:
        return put_loc('appInfoLocalizations', 'appInfo', iid, locs, 'en-US', attrs)[1]
    except ApiError as e:
        if 'name' not in str(e).lower(): raise
        attrs['name'] = EN['name'] + ': Prayer Times'          # the short name is taken
        return put_loc('appInfoLocalizations', 'appInfo', iid, locs, 'en-US', attrs)[1] + ' as ' + attrs['name']

def ver_attrs(L):
    return {'description': L['description'], 'keywords': L['keywords'], 'promotionalText': L['promo'],
            'supportUrl': SITE, 'marketingUrl': SITE}

@step('description, keywords, promotional text, links (Arabic)')
def ver_ar(vid, locs):
    r = put_loc('appStoreVersionLocalizations', 'appStoreVersion', vid, locs, 'ar-SA', ver_attrs(AR))
    LOC['ar'] = r[0]
    return r[1]

@step('description, keywords, promotional text, links (English)')
def ver_en(vid, locs):
    r = put_loc('appStoreVersionLocalizations', 'appStoreVersion', vid, locs, 'en-US', ver_attrs(EN))
    LOC['en'] = r[0]
    return r[1]
LOC = {}

@step('categories: Lifestyle, Reference')
def categories(iid):
    call('PATCH', f'/v1/appInfos/{iid}', {'data': {'type': 'appInfos', 'id': iid, 'relationships': {
        'primaryCategory': rel('appCategories', 'LIFESTYLE'), 'secondaryCategory': rel('appCategories', 'REFERENCE')}}})

AGE = dict(
    alcoholTobaccoOrDrugUseOrReferences='NONE', contests='NONE', gamblingSimulated='NONE', gunsOrOtherWeapons='NONE',
    horrorOrFearThemes='NONE', matureOrSuggestiveThemes='NONE', medicalOrTreatmentInformation='NONE',
    profanityOrCrudeHumor='NONE', sexualContentGraphicAndNudity='NONE', sexualContentOrNudity='NONE',
    violenceCartoonOrFantasy='NONE', violenceRealistic='NONE', violenceRealisticProlongedGraphicOrSadistic='NONE',
    advertising=False, ageAssurance=False, gambling=False, healthOrWellnessTopics=False, lootBox=False,
    messagingAndChat=False, parentalControls=False, socialMedia=False, unrestrictedWebAccess=False, userGeneratedContent=False,
)

@step('age rating: none of the topics (4+)')
def age(iid):
    d = call('GET', f'/v1/appInfos/{iid}/ageRatingDeclaration')['data']
    attrs = dict(AGE)
    for _ in range(12):                                         # drop any field this API version doesn't know
        try:
            call('PATCH', f'/v1/ageRatingDeclarations/{d["id"]}',
                 {'data': {'type': 'ageRatingDeclarations', 'id': d['id'], 'attributes': attrs}})
            return f'{len(attrs)} answers'
        except ApiError as e:
            need = [m.group(1) for x in e.errs for m in [re.search(r"attribute '(\w+)'", x.get('detail', ''))]
                    if m and x.get('code', '').endswith('REQUIRED') and m.group(1) not in attrs]
            if need:                                            # a new question: answer no
                for n in need: attrs[n] = False
                continue
            bad = [x.get('source', {}).get('pointer', '').rsplit('/', 1)[-1] for x in e.errs]
            bad = [b for b in bad if b in attrs]
            if not bad: raise
            for b in bad: attrs.pop(b)
    raise RuntimeError('age rating: too many retries')

@step('review contact and notes')
def review(vid):
    attrs = {'contactFirstName': 'Ibrahim', 'contactLastName': 'Alzahrani', 'contactPhone': '+966549855011',
             'contactEmail': 'itqan1.dev@gmail.com', 'demoAccountRequired': False, 'notes': REVIEW_NOTES}
    try:
        cur = call('GET', f'/v1/appStoreVersions/{vid}/appStoreReviewDetail').get('data')
    except ApiError as e:
        if e.code != 404: raise
        cur = None
    if cur:
        call('PATCH', f'/v1/appStoreReviewDetails/{cur["id"]}',
             {'data': {'type': 'appStoreReviewDetails', 'id': cur['id'], 'attributes': attrs}})
        return 'updated'
    call('POST', '/v1/appStoreReviewDetails', {'data': {'type': 'appStoreReviewDetails', 'attributes': attrs,
                                                         'relationships': {'appStoreVersion': rel('appStoreVersions', vid)}}})
    return 'created'

@step('build')
def build(vid, vstr):
    for _ in range(40):
        bs = call('GET', f'/v1/builds?filter[app]={APP}&filter[preReleaseVersion.version]={vstr}'
                         f'&sort=-uploadedDate&limit=10')['data']
        ok = [b for b in bs if b['attributes']['processingState'] == 'VALID' and not b['attributes'].get('expired')]
        if ok:
            b = ok[0]
            call('PATCH', f'/v1/appStoreVersions/{vid}/relationships/build', rel('builds', b['id']))
            return f"{vstr} ({b['attributes']['version']})"
        if not any(b['attributes']['processingState'] == 'PROCESSING' for b in bs):
            raise RuntimeError('no processed build of ' + vstr + ': ' +
                               ', '.join(f"{b['attributes']['version']} {b['attributes']['processingState']}" for b in bs))
        time.sleep(30)
    raise RuntimeError('the build is still processing')

@step('iPhone 6.9" screenshots (Arabic)')
def screenshots(lid):
    files = sorted(glob.glob(os.path.join(ROOT, 'store/ios/iphone69-*.png')))
    if not files: raise RuntimeError('no store/ios/iphone69-*.png')
    sets = call('GET', f'/v1/appStoreVersionLocalizations/{lid}/appScreenshotSets')['data']
    s = next((x for x in sets if x['attributes']['screenshotDisplayType'] == 'APP_IPHONE_67'), None)
    if s:
        for old in call('GET', f'/v1/appScreenshotSets/{s["id"]}/appScreenshots')['data']:
            call('DELETE', f'/v1/appScreenshots/{old["id"]}')
    else:
        s = call('POST', '/v1/appScreenshotSets', {'data': {'type': 'appScreenshotSets',
              'attributes': {'screenshotDisplayType': 'APP_IPHONE_67'},
              'relationships': {'appStoreVersionLocalization': rel('appStoreVersionLocalizations', lid)}}})['data']
    ids = []
    for f in files:
        data = open(f, 'rb').read()
        sh = call('POST', '/v1/appScreenshots', {'data': {'type': 'appScreenshots',
              'attributes': {'fileName': os.path.basename(f), 'fileSize': len(data)},
              'relationships': {'appScreenshotSet': rel('appScreenshotSets', s['id'])}}})['data']
        for op in sh['attributes']['uploadOperations']:
            part = data[op['offset']:op['offset'] + op['length']]
            req = urllib.request.Request(op['url'], data=part, method=op['method'],
                                         headers={h['name']: h['value'] for h in op.get('requestHeaders', [])})
            urllib.request.urlopen(req, timeout=300).read()
        call('PATCH', f'/v1/appScreenshots/{sh["id"]}', {'data': {'type': 'appScreenshots', 'id': sh['id'],
              'attributes': {'uploaded': True, 'sourceFileChecksum': hashlib.md5(data).hexdigest()}}})
        ids.append(sh['id'])
    for _ in range(40):
        st = [call('GET', f'/v1/appScreenshots/{i}')['data']['attributes'].get('assetDeliveryState') or {} for i in ids]
        bad = [x for x in st if x.get('state') == 'FAILED']
        if bad: raise RuntimeError('a picture failed: ' + json.dumps(bad[0].get('errors'))[:300])
        if all(x.get('state') == 'COMPLETE' for x in st): return f'{len(ids)} pictures'
        time.sleep(10)
    return f'{len(ids)} pictures uploaded, still processing'

@step('submitted for review')
def submit(vid):
    subs = call('GET', f'/v1/reviewSubmissions?filter[app]={APP}&filter[platform]=IOS'
                       '&filter[state]=READY_FOR_REVIEW,UNRESOLVED_ISSUES')['data']
    sub = subs[0] if subs else call('POST', '/v1/reviewSubmissions', {'data': {'type': 'reviewSubmissions',
          'attributes': {'platform': 'IOS'}, 'relationships': {'app': rel('apps', APP)}}})['data']
    try:
        call('POST', '/v1/reviewSubmissionItems', {'data': {'type': 'reviewSubmissionItems', 'relationships': {
            'reviewSubmission': rel('reviewSubmissions', sub['id']), 'appStoreVersion': rel('appStoreVersions', vid)}}})
    except ApiError as e:
        if e.code != 409: raise
        print('item already there:', e)
    call('PATCH', f'/v1/reviewSubmissions/{sub["id"]}',
         {'data': {'type': 'reviewSubmissions', 'id': sub['id'], 'attributes': {'submitted': True}}})

def main():
    v = version(); vid, vstr = v['id'], v['attributes']['versionString']
    i = app_info(); iid = i['id']
    set_version(vid)
    ilocs = call('GET', f'/v1/appInfos/{iid}/appInfoLocalizations')['data']
    info_ar(iid, ilocs); info_en(iid, ilocs)
    vlocs = call('GET', f'/v1/appStoreVersions/{vid}/appStoreVersionLocalizations')['data']
    LOC['ar'] = next((x['id'] for x in vlocs if x['attributes']['locale'] == 'ar-SA'), None)
    ver_ar(vid, vlocs); ver_en(vid, vlocs)
    categories(iid); age(iid); review(vid)
    if LOC.get('ar'): screenshots(LOC['ar'])
    build(vid, vstr)
    if os.environ.get('SUBMIT') == '1' and not ERRORS: submit(vid)
    print('\n==== summary ====\n' + '\n'.join(REPORT + ERRORS))
    sys.exit(1 if ERRORS else 0)

if __name__ == '__main__':
    main()
