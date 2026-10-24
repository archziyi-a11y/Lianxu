"""Verify the actual app timer survives display-off on the connected emulator.
No body profile or training fixtures are written to the user's database.
"""
import json, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
ADB='E:/Dev/Android/sdk/platform-tools/adb.exe'
ROOT=Path(__file__).resolve().parents[1]
def adb(*args):
    return subprocess.run([ADB,*args],check=True,capture_output=True).stdout.decode('utf-8',errors='replace')
def nodes():
    adb('shell','uiautomator','dump','/sdcard/lianxu-timer.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/lianxu-timer.xml')).iter('node'))
def tap(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def notification():
    raw=adb('shell','dumpsys','notification','--noredact')
    # Keep only this application's notification record, never other apps' messages.
    blocks=re.split(r'(?=NotificationRecord\()',raw)
    return '\n'.join(b for b in blocks if b.startswith('NotificationRecord(') and re.search(r'pkg=com.example.lianxu[\s,)]',b.split('\n')[0]))
adb('shell','pm','grant','com.example.lianxu','android.permission.POST_NOTIFICATIONS')
adb('shell','input','keyevent','224');adb('shell','input','keyevent','82')
adb('shell','am','start','-W','-n','com.example.lianxu/.MainActivity')
time.sleep(2)
items=nodes()
field=next(n for n in items if n.attrib.get('class')=='android.widget.EditText' and n.attrib.get('enabled')=='true')
tap(field);adb('shell','input','keyevent','123')
for _ in range(5):adb('shell','input','keyevent','67')
adb('shell','input','text','20');adb('shell','input','keyevent','4')
items=nodes();tap(next(n for n in items if n.attrib.get('text') in ('开始计时','再计一组')))
time.sleep(1)
active=notification()
assert '组间休息倒计时' in active, 'Running notification not present'
assert re.search(r'android\.chronometerCountDown=(?:Boolean \()?true',active), 'Native countdown not enabled'
adb('shell','input','keyevent','223');time.sleep(1)
power=adb('shell','dumpsys','power')
off='mWakefulness=Asleep' in power or 'mWakefulness=Dozing' in power
assert off, 'Display did not enter sleep'
time.sleep(23)
done=notification()
assert '组间休息结束' in done, 'Timer did not finish while display was off'
adb('shell','input','keyevent','224');adb('shell','input','keyevent','82');time.sleep(1)
items=nodes()
assert any(n.attrib.get('text')=='再计一组' for n in items), 'Completed state not restored in UI'
report={'device':'emulator-5554','seconds':20,'startedThroughUI':True,'nativeCountdownNotification':True,'displayAsleepDuringCountdown':off,'completedWhileDisplayOff':True,'completedStateAfterWake':True,'bodyFixturesWritten':False}
(ROOT/'docs/validation/stage4-timer-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
adb('shell','rm','-f','/sdcard/lianxu-timer.xml')
