"""Run identical two-card scenarios in a separate APK; keep every run immutable.

Build :app:assembleEvidence first. No production package, database or credential access.
ADB controls the shared setup/recording; --driver selects the actual scenario driver.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.example.lianxu.evidence'
ADB = os.environ.get('LIANXU_ADB', 'E:/Dev/Android/sdk/platform-tools/adb.exe')
TEXTS = ['Today bench was easy.', 'Recovery feels good.']
REPLY = '隔离交互样本：两卡补充已收到。这是离线固定回复，不代表真实 AI 分析。'


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


class Run:
    def __init__(self, driver, serial):
        self.driver, self.serial = driver, serial
        self.id = time.strftime('%Y%m%d-%H%M%S') + '-' + driver + '-' + uuid.uuid4().hex[:6]
        self.out = ROOT / 'docs/validation/workflow-runs' / self.id
        self.out.mkdir(parents=True, exist_ok=False)
        self.start = time.monotonic()
        self.calls = 0
        self.events = []
        self.record = None
        self.record_pid = None
        self.manifest = dict(runId=self.id, driver=driver, status='preparing')
        self.remote = f'/sdcard/{self.id}.mp4'

    def event(self, kind, **data):
        entry = dict(event=kind, seconds=round(time.monotonic()-self.start, 3), **data)
        self.events.append(entry)
        with (self.out/'actions.jsonl').open('a', encoding='utf-8') as f:
            f.write(json.dumps(entry, ensure_ascii=False)+'\n')

    def adb(self, *args, check=True):
        self.calls += 1
        return subprocess.run([ADB, '-s', self.serial, *map(str, args)], capture_output=True,
                              timeout=40, check=check).stdout

    def nodes(self):
        remote = f'/sdcard/{self.id}.xml'
        self.adb('shell', 'uiautomator', 'dump', remote)
        return list(ET.fromstring(self.adb('exec-out', 'cat', remote)).iter('node'))

    @staticmethod
    def find(nodes, label):
        return next((n for n in nodes if label in (n.get('text'), n.get('content-desc'))), None)

    def assert_visible(self, label):
        assert self.find(self.nodes(), label) is not None, f'Not visible: {label}'
        self.event('assert_visible', label=label)

    def touch(self, label, hold=False):
        node = self.find(self.nodes(), label)
        assert node is not None, f'Cannot touch: {label}'
        x, y, xx, yy = map(int, re.findall(r'\d+', node.get('bounds')))
        x, y = (x+xx)//2, (y+yy)//2
        self.event('hold' if hold else 'tap', label=label)
        self.adb('shell', 'input', *(['swipe', x, y, x, y, 700] if hold else ['tap', x, y]))
        time.sleep(.4)

    def position(self, label):
        for _ in range(12):
            node = self.find(self.nodes(), label)
            if node is not None:
                y = int(re.findall(r'\d+', node.get('bounds'))[1])
                if 370 < y < 1200:
                    return
            self.adb('shell', 'input', 'swipe', 1000, 1800, 1000, 1100, 400)
            self.event('scroll', target=label)
        raise AssertionError(f'Cannot position {label}')

    def shot(self, name):
        png = self.adb('exec-out', 'screencap', '-p')
        assert struct.unpack('>II', png[16:24]) == (1080, 2400), 'Wrong screenshot dimensions'
        (self.out/f'{name}.png').write_bytes(png)
        self.event('screenshot', name=name)

    def private_json(self, name):
        return json.loads(self.adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/evidence/{self.id}/{name}'))

    def prepare(self):
        apk = ROOT/'app/build/outputs/apk/evidence/app-evidence.apk'
        def git(*args):
            return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8').strip()
        self.manifest = dict(runId=self.id, driver=self.driver, serial=self.serial,
            transport='offline-fixture', frameClock='android-real-time', package=PACKAGE,
            sourceCommit=git('rev-parse', 'HEAD'), sourceStatus=git('status', '--short'),
            apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
            flowSha256=hashlib.sha256((ROOT/'scripts/workflow/two-cards.yaml').read_bytes()).hexdigest())
        sources = sorted((ROOT/'app/src').rglob('*.kt')) + sorted((ROOT/'app/src').rglob('*.xml'))
        sources += [ROOT/'app/build.gradle.kts', Path(__file__), ROOT/'scripts/workflow/two-cards.yaml']
        self.manifest['sourceFilesSha256'] = {str(p.relative_to(ROOT)).replace('\\', '/'):
            hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
        (self.out/'source.patch').write_bytes(subprocess.check_output(['git', 'diff', 'HEAD'], cwd=ROOT))
        write_json(self.out/'manifest.json', self.manifest)
        assert self.adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1', 'Device not ready'
        self.manifest['device'] = dict(api=self.adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip(),
            size=self.adb('shell', 'wm', 'size').decode().strip(),
            density=self.adb('shell', 'wm', 'density').decode().strip(),
            animatorScale=self.adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').decode().strip())
        self.adb('install', '-r', apk)
        self.adb('shell', 'am', 'force-stop', PACKAGE)
        started = self.adb('shell', 'am', 'start', '-S', '-W', '-f', '0x10008000', '-n', f'{PACKAGE}/com.example.lianxu.EvidenceActivity', '--es', 'runId', self.id)
        (self.out/'launch.txt').write_bytes(started)
        assert b'Status: ok' in started, 'Activity launch failed; see launch.txt'
        deadline = time.monotonic()+40
        while time.monotonic() < deadline:
            try:
                ready = self.private_json('ready.json')
                assert ready['runId'] == self.id and ready['isolated'] is True
                break
            except (subprocess.CalledProcessError, ValueError):
                time.sleep(.5)
        else:
            raise AssertionError('No matching fixture ready marker')
        self.assert_visible('今日')
        self.shot('ready')
        self.event('ready', **ready)

    def start_recording(self):
        assert not self.adb('shell', 'pidof', 'screenrecord', check=False).strip(), 'Another recording is active'
        self.touch_setting = self.adb('shell', 'settings', 'get', 'system', 'show_touches').decode().strip()
        self.adb('shell', 'settings', 'put', 'system', 'show_touches', 1)
        # Device events and host actions share an approximate anchor. Full video is retained.
        t0 = time.monotonic()
        uptime = float(self.adb('shell', 'cat', '/proc/uptime').split()[0])
        self.event('clock_anchor', deviceElapsedMs=uptime*1000, roundTripMs=(time.monotonic()-t0)*1000)
        self.event('record_start_requested')
        self.record = subprocess.Popen([ADB, '-s', self.serial, 'shell', 'screenrecord', '--time-limit', '180',
            '--bit-rate', '4000000', self.remote], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(.6)
        self.record_pid = self.adb('shell', 'pidof', 'screenrecord').decode().strip()
        assert self.record_pid.isdigit(), 'Expected one owned recording process'

    def adb_scenario(self):
        for title, text in zip(['卧推', '接续上次'], TEXTS):
            self.position(title)
            self.touch(title, hold=True)
            self.touch('点按写文字\n上划添加图片 ↑')
            time.sleep(1)
            self.adb('shell', 'input', 'text', text.replace(' ', '%s'))
            self.assert_visible(text)
            self.touch('收好这张 · 继续补充')
            if title == '卧推':
                self.touch('收起补充')
        self.assert_visible('2 张待发送')
        self.shot('pending')
        self.touch('统一发送')
        deadline = time.monotonic()+20
        while time.monotonic() < deadline:
            if self.find(self.nodes(), REPLY) is not None:
                break
        else:
            raise AssertionError('Reply not visible after submit')
        self.assert_visible('本轮建议')
        self.shot('reply')

    def maestro_scenario(self):
        env = os.environ.copy()
        env.update(JAVA_HOME='E:/Program Files/Android/Android Studio/jbr', ANDROID_HOME='E:/Dev/Android/sdk',
                   MAESTRO_CLI_NO_ANALYTICS='1', MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED='true')
        maestro = ROOT/'app/build/workflow-tools/maestro/bin/maestro.bat'
        with (self.out/'maestro.log').open('wb') as log:
            subprocess.run([str(maestro), 'test', '--udid', self.serial, '--no-ansi',
                '--test-output-dir', str(self.out/'maestro'), '--format', 'JUNIT',
                '--output', str(self.out/'junit.xml'), str(ROOT/'scripts/workflow/two-cards.yaml')],
                stdout=log, stderr=subprocess.STDOUT, env=env, timeout=170, check=True)

    def verify_result(self):
        state = self.private_json('state.json')
        assert state['runId'] == self.id
        assert state['requests'] == 1 and len(state['turns']) == 1, state
        assert state['turns'][0]['status'] == 'completed', state
        annotations = state['turns'][0]['annotations']
        assert len(annotations) == 2, annotations
        serialized = json.dumps(annotations)
        assert all(text in serialized for text in TEXTS), annotations
        assert state['trainingCount'] == 6, 'Annotations must not fabricate training facts'
        self.assert_visible(REPLY)
        self.shot('verified-reply')
        write_json(self.out/'result.json', state)
        self.event('result_verified')

    def finish(self):
        if self.record:
            if self.record_pid:
                self.adb('shell', 'kill', '-2', self.record_pid, check=False)
            self.record.wait(timeout=10)
            self.adb('pull', self.remote, self.out/'interaction.mp4', check=False)
            self.adb('shell', 'settings', 'put', 'system', 'show_touches', self.touch_setting)
        data = self.adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/evidence/{self.id}/events.jsonl', check=False)
        (self.out/'device-events.jsonl').write_bytes(data)
        self.manifest['artifactsSha256'] = {str(p.relative_to(self.out)).replace('\\', '/'):
            hashlib.sha256(p.read_bytes()).hexdigest() for p in self.out.rglob('*')
            if p.is_file() and p.name != 'manifest.json'}
        self.manifest.update(elapsedSeconds=round(time.monotonic()-self.start, 3), hostAdbCalls=self.calls)
        write_json(self.out/'manifest.json', self.manifest)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--driver', choices=['adb', 'maestro'], required=True)
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    run = Run(args.driver, args.serial)
    print(run.out, flush=True)
    try:
        run.prepare()
        run.start_recording()
        start = time.monotonic()
        getattr(run, args.driver+'_scenario')()
        run.manifest['scenarioSeconds'] = round(time.monotonic()-start, 3)
        run.verify_result()
        run.manifest['status'] = 'passed'
    except Exception as error:
        run.manifest['status'] = 'failed'
        run.manifest['error'] = str(error)
        run.event('failure', error=str(error))
        run.shot('failure')
        raise
    finally:
        run.finish()
    print('PASS', run.id, flush=True)


if __name__ == '__main__':
    main()
