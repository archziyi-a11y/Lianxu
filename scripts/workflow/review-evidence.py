"""Generate one local review page and event-centered clips; never overwrite a run.

Usage: python scripts/workflow/review-evidence.py RUN_ID [RUN_ID ...]
Requires imageio-ffmpeg in app/build/stage5-media-tools (or LIANXU_FFMPEG).
"""
import hashlib
import html
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT/'docs/validation/workflow-runs'
sys.path.insert(0, str(ROOT/'app/build/stage5-media-tools'))
if os.environ.get('LIANXU_FFMPEG'):
    ffmpeg = os.environ['LIANXU_FFMPEG']
else:
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def lines(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def media(*args):
    result = subprocess.run([ffmpeg, '-v', 'error', '-nostdin', *map(str, args)], capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))


sections, summary = [], []
for run_id in sys.argv[1:]:
    assert '/' not in run_id and '\\' not in run_id and run_id not in ('.', '..')
    run = RUNS/run_id
    manifest = read_json(run/'manifest.json')
    assert manifest['status'] == 'passed', 'Only passed runs enter the review report'
    actions, events = lines(run/'actions.jsonl'), lines(run/'device-events.jsonl')
    anchor = next(a for a in actions if a['event'] == 'clock_anchor')
    record = next(a for a in actions if a['event'] == 'record_start_requested')
    def video_time(event):
        return (event['elapsedMs']-anchor['deviceElapsedMs'])/1000 + anchor['seconds']-record['seconds']
    send = next(e for e in events if e['event'] == 'processing' and e['value'])
    done = next(e for e in events if e['event'] == 'processing' and not e['value'] and e['elapsedMs'] > send['elapsedMs'])
    start, end = max(0, video_time(send)-2), video_time(done)+3
    derived = run/'review'
    if not derived.exists():
        derived.mkdir()
        media('-ss', start, '-i', run/'interaction.mp4', '-t', end-start,
              '-vf', 'fps=30,scale=540:-2', '-an', '-c:v', 'libx264', '-preset', 'fast', '-crf', '23',
              '-movflags', '+faststart', derived/'send-reply.mp4')
        for name, point in [('exit', video_time(send)-.6), ('entry', video_time(done)-.2)]:
            media('-ss', max(0, point), '-i', run/'interaction.mp4', '-vf',
                  'fps=10,scale=180:-2,tile=6x2', '-frames:v', '1', derived/f'{name}.png')
        media('-i', derived/'send-reply.mp4', '-vf', 'fps=30', '-f', 'null', '-')
        meta = dict(runId=run_id, rawVideoSha256=hashlib.sha256((run/'interaction.mp4').read_bytes()).hexdigest(),
            clipStartSeconds=start, clipEndSeconds=end,
            anchorNote='Approximate host/device alignment; screenrecord startup is not frame-synchronized. Review full clip for motion; do not use as latency benchmark.',
            deviceProcessingMs=done['elapsedMs']-send['elapsedMs'],
            sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in derived.iterdir() if p.is_file()})
        (derived/'media.json').write_text(json.dumps(meta, indent=2), encoding='utf-8')
    meta = read_json(derived/'media.json')
    summary.append(dict(runId=run_id, driver=manifest['driver'], scenarioSeconds=manifest['scenarioSeconds'],
        elapsedSeconds=manifest['elapsedSeconds'], hostAdbCalls=manifest['hostAdbCalls'],
        deviceProcessingMs=meta['deviceProcessingMs'], apkSha256=manifest['apkSha256']))
    e = html.escape
    sections.append(f'''<section><h2>{e(manifest['driver'].upper())} · {e(run_id)}</h2>
      <p>场景 {manifest['scenarioSeconds']:.2f}s · 全流程 {manifest['elapsedSeconds']:.2f}s ·
      主机 ADB 调用 {manifest['hostAdbCalls']}（不含 Maestro 内部调用，不能据此直接比较总调用量）</p>
      <video controls preload="metadata" src="{e(run_id)}/review/send-reply.mp4"></video>
      <div class="buttons"><button onclick="step(this,-1/30)">前一帧</button><button onclick="step(this,1/30)">后一帧</button>
      <button onclick="rate(this,0.5)">半速</button><button onclick="rate(this,1)">原速</button></div>
      <p><a href="{e(run_id)}/interaction.mp4">完整操作录屏</a> · <a href="{e(run_id)}/manifest.json">版本与结果</a> ·
      <a href="{e(run_id)}/result.json">两卡原文与完成状态</a></p>
      <details><summary>发送连续帧 · 每帧间隔约 100ms</summary><img src="{e(run_id)}/review/exit.png"></details>
      <details><summary>接收连续帧 · 每帧间隔约 100ms</summary><img src="{e(run_id)}/review/entry.png"></details>
      </section>''')

assert summary, 'Supply at least one run ID'
name = 'review-'+time.strftime('%Y%m%d-%H%M%S')
report = RUNS/(name+'.html')
assert not report.exists()
report.write_text('''<!doctype html><html lang="zh"><meta charset="utf-8"><title>练序 · 工作流验证</title>
<style>body{background:#101714;color:#e4eee4;font:16px system-ui;max-width:1120px;margin:auto;padding:32px}
h1{color:#d7ff63}section{border-top:1px solid #5c6d5d;margin-top:32px;padding-top:16px}
video{max-height:620px;max-width:100%;background:#000}img{max-width:100%}a{color:#d7ff63}
button{padding:10px;margin:8px 8px 8px 0}p{line-height:1.7}summary{cursor:pointer;padding:12px}</style>
<h1>练序 · 一次集中审阅</h1><p>独立安装包、内存数据库、离线固定回复。复用正式界面和真实 Android 动画时钟。
运行通过表示两条批注进入同一已完成轮次且回复可见；不代表真实 AI 推理或主观手感已经通过。</p>
<p>先审阅所有运行，集中列问题；修完一批后生成新运行。旧证据保留。
短片根据业务事件自动截取；时间对齐近似，不能用来判定毫秒级性能。</p>'''+''.join(sections)+'''
<script>function step(b,n){let v=b.closest('section').querySelector('video');v.pause();v.currentTime+=n}
function rate(b,n){b.closest('section').querySelector('video').playbackRate=n}</script></html>''', encoding='utf-8')
(RUNS/(name+'.json')).write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(report)
