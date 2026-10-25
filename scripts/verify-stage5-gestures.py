"""Real ADB gestures against the isolated production-screen evidence session.
Start with stage5-device.py start. This is controlled transport, not a live API test.
"""
import json, re, subprocess, time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/validation/stage5-device'
ADB='E:/Dev/Android/sdk/platform-tools/adb.exe'
events=[]
def adb(*args):
    return subprocess.run([ADB,*map(str,args)],capture_output=True,check=True).stdout
def event(kind,value):
    events.append(dict(time=time.time(),action=kind,value=value))
def nodes():
    adb('shell','uiautomator','dump','/sdcard/lianxu-stage5-window.xml')
    return list(ET.fromstring(adb('exec-out','cat','/sdcard/lianxu-stage5-window.xml')).iter('node'))
def bounds(n): return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def find(label):
    return next((n for n in nodes() if label in (n.get('text'),n.get('content-desc'))),None)
def swipe(x,y,xx,yy,ms=400):
    event('swipe',[x,y,xx,yy,ms]);adb('shell','input','swipe',x,y,xx,yy,ms);time.sleep(.35)
def touch(label,hold=False):
    n=find(label);assert n is not None, label
    x,y,xx,yy=bounds(n);x=(x+xx)//2;y=(y+yy)//2
    event('hold' if hold else 'tap',label)
    adb('shell','input',*(['swipe',x,y,x,y,700] if hold else ['tap',x,y]));time.sleep(.4)
def text(value):
    # Let IME opening finish before hardware-key injection, just as a person waits for keys.
    time.sleep(1)
    adb('shell','input','text',value.replace(' ','%s'));time.sleep(.7)
    assert find(value) is not None, 'IME text must match exactly: '+value
    event('verified_text',value)
def shot(name):
    time.sleep(.8)
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
def position(label,max_y=1000):
    for _ in range(8):
        n=find(label)
        if n is not None and bounds(n)[1]<max_y and bounds(n)[1]>370:return
        swipe(1000,1800,1000,1100)
    raise AssertionError('Cannot position '+label)
def wait_reply():
    for _ in range(12):
        if any('隔离交互样本' in n.get('text','') for n in nodes()):return
        time.sleep(.5)
    raise AssertionError('Reply not visible')

position('卧推',900)
adb('shell','settings','put','system','show_touches',1)
record=subprocess.Popen([ADB,'shell','screenrecord','--time-limit','180','--bit-rate','4000000','/sdcard/lianxu-stage5-interaction.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
try:
    touch('卧推',True);shot('long-press-active')
    touch('点按写文字\n上划添加图片 ↑');text('Today bench was easy.');shot('text-in-place')
    touch('收好这张 · 继续补充');touch('本次记录 卧推');touch('填写')
    slider=find('训练组数');assert slider is not None
    x,y,xx,yy=bounds(slider);swipe(x+40,(y+yy)//2,x+(xx-x)//4,(y+yy)//2)
    touch('收起补充');position('饮食补充',1200);touch('饮食补充',True)
    prompt=find('点按写文字\n上划添加图片 ↑');x,y,xx,yy=bounds(prompt)
    swipe((x+xx)//2,yy-10,(x+xx)//2,y-110)
    touch('选择图片')
    photo=next(n for n in nodes() if n.get('content-desc','').startswith('Photo taken'))
    x,y,xx,yy=bounds(photo);adb('shell','input','tap',(x+xx)//2,(y+yy)//2)
    time.sleep(1);assert find('2 张待发送') is not None;shot('mixed-pending')
    touch('统一发送');shot('sending');wait_reply();shot('first-reply')
    touch('本轮建议',True);touch('点按写文字\n上划添加图片 ↑');text('Second round.');touch('收好这张 · 继续补充')
    touch('统一发送');wait_reply();shot('second-reply')
    swipe(950,300,100,300,450);assert find('档案') is not None;shot('archive-after-input')
    touch('习惯与跟进');touch('取消跟进');assert find('已取消') is not None;shot('cancelled-in-archive')
    event('result','passed')
finally:
    pids=subprocess.run([ADB,'shell','pidof','screenrecord'],capture_output=True).stdout.decode().split()
    for pid in pids: adb('shell','kill','-2',pid)
    time.sleep(1)
    adb('pull','/sdcard/lianxu-stage5-interaction.mp4',str(OUT/'controls_text_photo_two_rounds_archive.mp4'))
    adb('shell','settings','put','system','show_touches',0)
    (OUT/'gestures.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: touch gestures, exact text, slider, system picker, two submissions, page swipe, cancellation')
