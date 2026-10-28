"""Read-only Maestro MCP handshake/tool discovery; no global Codex configuration changes."""
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'app/build/workflow-tools/mcp-probe'
OUT.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env.update(JAVA_HOME='E:/Program Files/Android/Android Studio/jbr', ANDROID_HOME='E:/Dev/Android/sdk',
           MAESTRO_CLI_NO_ANALYTICS='1', MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED='true')
with (OUT/'stderr.log').open('w', encoding='utf-8') as err:
    p = subprocess.Popen([str(ROOT/'app/build/workflow-tools/maestro/bin/maestro.bat'), 'mcp', '--no-viewer',
                          '--working-dir', str(ROOT)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=err, text=True, encoding='utf-8', env=env)
    lines = queue.Queue()
    def read():
        for line in p.stdout:
            try:
                lines.put(json.loads(line))
            except ValueError:
                pass
    threading.Thread(target=read, daemon=True).start()
    def send(data):
        p.stdin.write(json.dumps(dict(jsonrpc='2.0', **data))+'\n')
        p.stdin.flush()
    def receive(request_id):
        while True:
            item = lines.get(timeout=40)
            if item.get('id') == request_id:
                assert 'error' not in item, item
                return item['result']
    try:
        send(dict(id=1, method='initialize', params=dict(protocolVersion='2024-11-05', capabilities={},
                    clientInfo=dict(name='lianxu-workflow-probe', version='1'))))
        info = receive(1)
        send(dict(method='notifications/initialized'))
        send(dict(id=2, method='tools/list', params={}))
        catalog = receive(2)
        (OUT/'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
        send(dict(id=3, method='tools/call', params=dict(name='list_devices', arguments={})))
        devices = receive(3)
        result = dict(server=info, tools=[t['name'] for t in catalog['tools']], devices=devices)
        if '--assert-reply' in sys.argv:
            connected = json.loads(devices['content'][0]['text'])['devices']
            device = next(d['device_id'] for d in connected if d.get('connected') and d['platform'] == 'android')
            send(dict(id=4, method='tools/call', params=dict(name='inspect_screen', arguments=dict(device_id=device))))
            result['screen'] = receive(4)
            assert not result['screen'].get('isError'), result['screen']
            flow = 'appId: com.example.lianxu.evidence\n---\n- assertVisible: "本轮建议"\n'
            send(dict(id=5, method='tools/call', params=dict(name='run', arguments=dict(device_id=device, yaml=flow))))
            result['assertReply'] = receive(5)
            assert not result['assertReply'].get('isError'), result['assertReply']
        (OUT/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(dict(tools=result['tools'], devices=devices, assertion=result.get('assertReply')), ensure_ascii=False))
    finally:
        p.stdin.close()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
