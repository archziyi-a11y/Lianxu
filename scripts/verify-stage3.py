"""Install prebuilt APKs and run Android tests; --live opts into the existing DeepSeek key.
Never pass credentials as command arguments or print subprocess stdin.
"""
import argparse, json, subprocess
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--live', action='store_true')
parser.add_argument('--stage', type=int, default=3)
parser.add_argument('--class-name')
parser.add_argument('--label')
a = parser.parse_args()
label = a.label or f'stage{a.stage}'
root = Path(__file__).resolve().parents[1]
adb = Path('E:/Dev/Android/sdk/platform-tools/adb.exe')
report = root / 'docs/validation'
report.mkdir(parents=True, exist_ok=True)
def run(args, **kw):
    return subprocess.run([str(adb), *args], check=True, capture_output=True, **kw)
for apk in ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    run(['install', '-r', str(root / apk)])
if a.live:
    values = {}
    for line in (root / '.env').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip().strip(chr(34)).strip(chr(39))
    key = values['DEEPSEEK_API_KEY']
    subprocess.run([str(adb),'shell','run-as','com.example.lianxu','tee',f'cache/{label}-live-key'],
        input=key.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
args = ['shell','am','instrument','-w','-r','-e','validationStage',str(a.stage),'-e','validationLabel',label]
if a.live or a.class_name:
    args += ['-e','class',a.class_name or 'com.example.lianxu.LiveAgentTest']
args += ['com.example.lianxu.test/androidx.test.runner.AndroidJUnitRunner']
try:
    output = run(args).stdout.decode('utf-8',errors='replace').replace('\r','')
    (report / (f'{label}-live-instrumentation.txt' if a.live else f'{label}-focused-instrumentation.txt' if a.class_name else f'{label}-instrumentation.txt')).write_text(output,encoding='utf-8')
    print(output)
    if a.live:
        data = run(['exec-out','run-as','com.example.lianxu','cat',f'files/{label}-live-result.json']).stdout
        (report / f'{label}-live-result.json').write_bytes(data)
        result = json.loads(data)
        print(json.dumps({k: result.get(k) for k in ['status','model','requests','tools','trainingCount','intakeCount','error']},ensure_ascii=False))
    if 'FAILURES!!!' in output or 'OK (' not in output:
        raise SystemExit(1)
finally:
    if a.live:
        run(['shell','run-as','com.example.lianxu','rm','-f',f'cache/{label}-live-key'])
