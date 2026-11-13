import os,sys,time,json,subprocess,re,threading,xml.etree.ElementTree as ET,urllib.request,argparse,shutil,shlex
from pathlib import Path
parser=argparse.ArgumentParser(description="Initial native Compose smoke journey, not full parity")
parser.add_argument('--adb', default=shutil.which('adb'))
parser.add_argument('--adb-host')
parser.add_argument('--apk',type=Path,help='Immutable debug APK to install; defaults to the latest local build')
parser.add_argument('--adb-port',type=int,default=5037)
parser.add_argument('--serial',default=os.getenv('ANDROID_SERIAL'))
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--skip-install',action='store_true')
parser.add_argument('--reset-debug-install',action='store_true',help='Uninstall the disposable debug app before installing; clears its local data')
args=parser.parse_args()
if not args.adb:parser.error('Set --adb to the Android SDK adb executable')
ROOT=Path(__file__).resolve().parents[2]
OUT=args.output.resolve(); OUT.mkdir(parents=True,exist_ok=True)
ADB=[args.adb]
if args.adb_host:ADB+=['-H',args.adb_host]
ADB+=['-P',str(args.adb_port)]
if args.serial:ADB+=['-s',args.serial]
PKG='io.github.wwpol.brapmony.debug'; ACT='lol.mony.harmony.MainActivity'
stop=threading.Event()
def heartbeat():
    while not stop.wait(15): print('UI journey active',time.strftime('%H:%M:%S'),flush=True)
threading.Thread(target=heartbeat,daemon=True).start()
def adb(*args,timeout=150):
    proc=subprocess.run(ADB+list(args),capture_output=True,timeout=timeout)
    if proc.returncode: raise RuntimeError('adb operation failed: '+proc.stderr.decode(errors='replace')[:300])
    return proc.stdout

def dump(label='ui'):
    adb('shell','uiautomator','dump','/sdcard/native-journey.xml',timeout=90)
    body=adb('exec-out','cat','/sdcard/native-journey.xml',timeout=30)
    start=body.find(b'<?xml')
    if start<0:
        print('Hierarchy not ready; waiting for a complete UI dump',flush=True)
        return ET.Element('hierarchy')
    try:root=ET.fromstring(body[start:])
    except ET.ParseError:
        print('Incomplete UI dump; retrying',flush=True)
        return ET.Element('hierarchy')
    # Don't persist any raw password text from device accessibility.
    for node in root.iter('node'):
        if node.get('password')=='true':node.set('text','[password field]')
    ET.ElementTree(root).write(OUT/(label+'.xml'),encoding='utf-8')
    return root

def nodes(root):return list(root.iter('node'))
def find(root,text=None,tag=None,desc=None,cls=None):
    for node in nodes(root):
        if text is not None and node.get('text')!=text:continue
        if tag is not None and node.get('resource-id')!=tag:continue
        if desc is not None and node.get('content-desc')!=desc:continue
        if cls is not None and node.get('class')!=cls:continue
        return node
    return None

def wait(text=None,tag=None,desc=None,seconds=120,label='ui'):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        tree=dump(label)
        n=find(tree,text,tag,desc)
        if n is not None:return n
        # Dialogs caused by TCG scheduling must not silently close the app.
        n=find(tree,text='Wait')
        if n is not None:tap(n)
        time.sleep(3)
    raise AssertionError('UI node not found: '+str(text or tag or desc))

def tap(node):
    values=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
    if len(values)!=4:raise AssertionError('No UI bounds')
    x1,y1,x2,y2=values
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def click(text=None,tag=None,desc=None,seconds=120):tap(wait(text,tag,desc,seconds))
def fill(tag,value):
    tap(wait(tag=tag));adb('shell','input text '+shlex.quote(value.replace(' ','%s')));time.sleep(2)
def screenshot(name):
    data=adb('exec-out','screencap','-p',timeout=90)
    (OUT/(name+'.png')).write_bytes(data)
    dump(name)
    print('SCREENSHOT',name,len(data),'bytes',flush=True)

def readback(marker):
    fixture=json.loads((ROOT/'e2e/fixture.json').read_text());u=fixture['users']['alice']
    request=urllib.request.Request('http://localhost:54480/auth/v1/token?grant_type=password',method='POST',
      data=json.dumps({'email':u['email'],'password':u['password']}).encode(),headers={'Content-Type':'application/json','apikey':'local-e2e'})
    token=json.loads(urllib.request.urlopen(request).read())['access_token']
    channel=fixture.get('generalChannelId') or fixture.get('channelId')
    request=urllib.request.Request('http://localhost:54480/rest/v1/messages?select=id,content,user_id&order=created_at.desc&limit=20',headers={'Authorization':'Bearer '+token,'apikey':'local-e2e'})
    rows=json.loads(urllib.request.urlopen(request).read())
    hits=[row for row in rows if any(p.get('text')==marker for p in row.get('content',[]))]
    assert hits,'Sent message was not stored by real Harmony'
    (OUT/'message-readback.json').write_text(json.dumps(hits,indent=2))
    print('PASS native send stored by real PostgREST, id',hits[0]['id'],flush=True)

try:
    print('Installing native app',flush=True)
    if args.reset_debug_install:adb('uninstall',PKG)
    if not args.skip_install:print(adb('install','-r',str(args.apk or ROOT/'android-native/app/build/outputs/apk/debug/app-debug.apk')).decode(),flush=True)
    adb('shell','settings','put','system','screen_off_timeout','2147483647')
    adb('shell','settings','put','global','stay_on_while_plugged_in','7')
    print(adb('shell','am','start','-W','-n',PKG+'/'+ACT,timeout=180).decode(),flush=True)
    # Wait for actual rendered first frame as well as the accessibility hierarchy under TCG.
    wait(tag='signin-email',seconds=220,label='signin-ready')
    time.sleep(75)
    screenshot('01-signin-dark')
    fixture=json.loads((ROOT/'e2e/fixture.json').read_text());alice=fixture['users']['alice']
    fill('signin-email',alice['email']);fill('signin-password',alice['password'])
    adb('shell','input','keyevent','4');click(tag='signin-submit')
    wait(text='Messages',seconds=180,label='authenticated')
    time.sleep(15);screenshot('02-conversations-dark')
    click(tag='rail-server-E2E Server')
    click(tag='channel-general')
    wait(tag='message-composer',seconds=150)
    time.sleep(15);screenshot('03-channel-dark')
    marker='Native emulator message '+str(int(time.time()))
    fill('message-composer',marker);click(tag='message-send')
    wait(text=marker,seconds=150,label='sent-message')
    readback(marker);time.sleep(10);screenshot('04-native-message-roundtrip')
    click(desc='Back');click(text='Social');time.sleep(25);screenshot('05-social-dark')
    click(text='Local');time.sleep(15);screenshot('06-social-local-dark')
    click(text='Alerts');time.sleep(20);screenshot('07-alerts-dark')
    click(text='You');time.sleep(25);screenshot('08-account-profile-dark')
    click(text='Appearance');time.sleep(15);screenshot('09-appearance-dark')
    print('PASS initial native emulator journey; no full-parity claim',flush=True)
except Exception as error:
    print('JOURNEY FAILED',type(error).__name__,str(error),flush=True)
    try:screenshot('failure')
    except Exception as capture:print('Capture failed',str(capture),flush=True)
    sys.exit(1)
finally:
    stop.set()
