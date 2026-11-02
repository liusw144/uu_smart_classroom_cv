"""真实管理端停机、边缘计算、进程重启和重连验收；只管理本脚本启动的Java进程。"""

import hashlib
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import urllib.parse
from uuid import uuid4

from local_dev import ROOT, environment, load_values


def request(method, path, token=None, value=None, raw=None, content_type=None, headers=None):
    data = raw if raw is not None else (json.dumps(value).encode('utf-8') if value is not None else None)
    headers = dict(headers or {})
    if token: headers['Authorization'] = 'Bearer ' + token
    if data is not None: headers['Content-Type'] = content_type or 'application/json'
    query = urllib.request.Request('http://127.0.0.1:8089' + urllib.parse.quote(path, safe='/?=&'), data=data, headers=headers, method=method)
    with urllib.request.urlopen(query, timeout=20) as response:
        return json.load(response)['data']


def multipart(fields):
    boundary = 'demo-' + uuid4().hex
    data = bytearray()
    for name, filename, content_type, value in fields:
        data.extend(('--' + boundary + '\r\nContent-Disposition: form-data; name="' + name + '"' + (f'; filename="{filename}"' if filename else '') + '\r\nContent-Type: ' + content_type + '\r\n\r\n').encode())
        data.extend(value)
        data.extend(b'\r\n')
    data.extend(('--' + boundary + '--\r\n').encode())
    return bytes(data), 'multipart/form-data; boundary=' + boundary


def listening():
    with socket.socket() as connection:
        return connection.connect_ex(('127.0.0.1', 8089)) == 0


def state(job):
    with sqlite3.connect(ROOT / 'runtime/edge/outbox.db') as database:
        database.row_factory = sqlite3.Row
        return dict(database.execute('SELECT * FROM outbox WHERE job_id=?', (job,)).fetchone())


def main():
    if listening():
        raise SystemExit('8089已占用，请先停止本机项目后端；脚本不会停止现有进程。')
    values = load_values()
    env = environment(values)
    output = ROOT / 'runtime/real-demo-evidence'
    output.mkdir(parents=True, exist_ok=True)
    log = (ROOT / '.harness/offline-managed-backend.log').open('ab')
    process = None
    evidence = {'started_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'checks': []}

    def start():
        nonlocal process
        process = subprocess.Popen([str(Path(env['JAVA_HOME']) / 'bin/java.exe'), '-jar', 'api/target/classroom-api-0.1.0-SNAPSHOT.jar'], cwd=ROOT / 'backend', env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(90):
            if process.poll() is not None: raise RuntimeError('本脚本后端进程启动失败')
            try:
                with urllib.request.urlopen('http://127.0.0.1:8089/health/live', timeout=1) as response:
                    if response.status == 200: return
            except (OSError, urllib.error.URLError): pass
            time.sleep(0.5)
        raise RuntimeError('本脚本后端启动超时')

    def stop():
        nonlocal process
        if process is not None:
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            process = None

    def edge(phase):
        subprocess.run([sys.executable, 'scripts/local_dev.py', 'edge', '--phase', phase, '--once'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)

    try:
        start()
        token = request('POST', '/api/v1/auth/login', value={'school_code': 'local-school', 'username': values['TEACHER_USERNAME'], 'password': values['TEACHER_PASSWORD']})['access_token']
        task = request('GET', '/api/v1/tasks?q=开源模型验证&size=100', token)['items'][0]
        model = request('GET', '/api/v1/models?q=yolox-tiny-rtmpose-s-v1&size=100', token)['items'][0]
        video = (ROOT / 'runtime/demo/upstream-demo.mp4').read_bytes()
        payload, content_type = multipart([('file', 'offline-public-video.mp4', 'video/mp4', video)])
        asset = request('POST', '/api/v1/assets', token, raw=payload, content_type=content_type)
        job = request('POST', '/api/v1/detections', token, {'task_id': task['id'], 'asset_id': asset['id'], 'model_id': model['id']})['id']
        evidence['job_id'] = job
        edge('cache')
        assert state(job)['state'] == 'CACHED'
        evidence['checks'].append('已领取租约并持久缓存输入')
        stop()
        assert not listening()
        edge('infer')
        pending = state(job)
        assert pending['state'] == 'RESULT_READY'
        result = json.loads(pending['result'])
        assert result['sampled_frames'] == 4 and result['person_instances'] > 0
        evidence['checks'].append('管理端真实停机期间执行真实模型并持久化结果')
        # infer进程已退出，另一个新进程读取同一SQLite文件，模拟断网重启。
        edge('deliver')
        assert state(job)['state'] == 'RESULT_READY'
        evidence['checks'].append('边缘进程重启后断网回传失败，结果仍保留')
        start()
        edge('deliver')
        assert state(job)['state'] == 'DELIVERED'
        delivered = request('GET', '/api/v1/detections/' + job + '/result', token)
        assert delivered['artifact_sha256'] == result['artifact_sha256']
        evidence['checks'].append('管理端恢复后原租约回传成功')
        lease = json.loads(state(job)['lease'])
        artifact = (ROOT / 'runtime/edge/outbox-artifacts' / job).read_bytes()
        payload, content_type = multipart([('result', None, 'text/plain; charset=utf-8', pending['result'].encode('utf-8')), ('artifact', 'artifact.mp4', 'video/mp4', artifact)])
        assert request('POST', '/internal/v1/edge/jobs/' + job + '/result', values['EDGE_SERVICE_TOKEN'], raw=payload, content_type=content_type, headers={'X-Node-ID': values['EDGE_NODE_ID'], 'X-Job-Lease': lease['lease_id']}) is True
        assert request('GET', '/api/v1/detections/' + job + '/result', token) == delivered
        evidence['checks'].append('重复回传确认幂等，原结果不变')
        evidence.update(passed=True, sampled_frames=result['sampled_frames'], person_instances=result['person_instances'], artifact_sha256=hashlib.sha256(artifact).hexdigest(), lease_attempt=lease['attempt'])
    finally:
        stop(); log.close()
        evidence['finished_at'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
        (output / 'offline-evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
