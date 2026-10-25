"""Manual emulator evidence controls. Requires the isolated StageFiveUiTest session.
No production database is seeded, cleared, or inspected. No credentials are read.
"""
import argparse, json, re, subprocess, time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/validation/stage5-device'
ADB = 'E:/Dev/Android/sdk/platform-tools/adb.exe'
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, check=True).stdout

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/lianxu-stage5-window.xml')
    return list(ET.fromstring(adb('exec-out', 'cat', '/sdcard/lianxu-stage5-window.xml')).iter('node'))

def center(n):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',n.get('bounds')))
    return str((x1+x2)//2), str((y1+y2)//2)

p=argparse.ArgumentParser()
p.add_argument('action',choices=['start','inspect','shot','tap','hold','swipe','text','back','record','stop_record','stop'])
p.add_argument('values',nargs='*')
a=p.parse_args()
with (OUT/'actions.jsonl').open('a',encoding='utf-8') as f:
    f.write(json.dumps({'time':time.time(),'action':a.action,'values':a.values},ensure_ascii=False)+'\n')
if a.action=='start':
    for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        adb('install','-r',str(ROOT/apk))
    output=(OUT/'manual-instrumentation.txt').open('wb')
    subprocess.Popen([ADB,'shell','am','instrument','-w','-r','-e','evidenceSession','true','-e','class',
        'com.example.lianxu.StageFiveUiTest#manualEvidenceSession','com.example.lianxu.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=output,creationflags=subprocess.CREATE_NO_WINDOW)
elif a.action=='inspect':
    for n in nodes():
        if n.get('text') or n.get('content-desc'):
            print(json.dumps({k:n.get(k) for k in ['text','content-desc','bounds','clickable']},ensure_ascii=False))
elif a.action=='shot':
    time.sleep(.8)
    (OUT/(a.values[0]+'.png')).write_bytes(adb('exec-out','screencap','-p'))
elif a.action in ['tap','hold']:
    if len(a.values)==2 and all(v.isdigit() for v in a.values):
        x,y=a.values
    else:
        found=[n for n in nodes() if n.get('text')==a.values[0] or n.get('content-desc')==a.values[0]]
        assert found, 'No matching visible node'
        x,y=center(found[int(a.values[1]) if len(a.values)>1 else 0])
    adb('shell','input',*(['tap',x,y] if a.action=='tap' else ['swipe',x,y,x,y,'700']))
elif a.action=='swipe': adb('shell','input','swipe',*a.values)
elif a.action=='text': adb('shell','input','text',a.values[0])
elif a.action=='back': adb('shell','input','keyevent','4')
elif a.action=='record':
    adb('shell','settings','put','system','show_touches','1')
    subprocess.Popen([ADB,'shell','screenrecord','--time-limit','180','--bit-rate','4000000','/sdcard/lianxu-stage5-interaction.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
elif a.action=='stop_record':
    for pid in subprocess.run([ADB,'shell','pidof','screenrecord'],capture_output=True).stdout.decode().split():
        adb('shell','kill','-2',pid)
    time.sleep(1)
    adb('pull','/sdcard/lianxu-stage5-interaction.mp4',str(OUT/(a.values[0]+'.mp4')))
    adb('shell','settings','put','system','show_touches','0')
elif a.action=='stop':
    adb('shell','run-as','com.example.lianxu','touch','cache/stage5-evidence-stop');time.sleep(2)
    (OUT/'session-result.json').write_bytes(adb('exec-out','run-as','com.example.lianxu','cat','files/stage5-evidence-session.json'))
