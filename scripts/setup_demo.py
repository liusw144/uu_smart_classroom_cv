"""准备可追溯的本机模型与公开视频，不提交大文件。"""

import argparse
import hashlib
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import urllib.request
import zipfile

from local_dev import ENV_FILE, ROOT, load_values


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def prepare(download=True):
    runtime = ROOT / 'runtime'
    lock = json.loads((ROOT / 'models/demo-assets.lock.json').read_text(encoding='utf-8'))
    for item in lock['downloads']:
        target = (runtime / item['path']).resolve()
        if not target.is_relative_to(runtime.resolve()):
            raise ValueError('清单路径超出runtime')
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_file() and sha(target) == item['sha256']:
            continue
        if not download:
            raise SystemExit('素材缺失或哈希不符：' + item['path'])
        temporary = target.with_suffix('.part')
        request = urllib.request.Request(item['url'], headers={'User-Agent': 'uu-smart-classroom-local-demo'})
        try:
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > item['size']:
                        raise ValueError('下载体积与锁定清单不符')
                    output.write(chunk)
            if temporary.stat().st_size != item['size'] or sha(temporary) != item['sha256']:
                raise ValueError('下载完整性校验失败')
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    pose = runtime / lock['pose']['path']
    if not pose.is_file() or sha(pose) != lock['pose']['sha256']:
        with zipfile.ZipFile(runtime / 'downloads/rtmpose-s.zip') as archive:
            entries = [entry for entry in archive.infolist() if entry.filename.endswith('.onnx')]
            if len(entries) != 1 or entries[0].file_size != lock['pose']['size']:
                raise ValueError('姿态模型压缩包结构无效')
            pose.write_bytes(archive.read(entries[0]))
    if sha(pose) != lock['pose']['sha256']:
        raise ValueError('姿态模型校验失败')
    vendor = (runtime / 'vendor/llama').resolve()
    vendor.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(runtime / 'downloads/llama-b11960-win-cpu.zip') as archive:
        for entry in archive.infolist():
            destination = (vendor / entry.filename).resolve()
            if not destination.is_relative_to(vendor) or entry.file_size > 100 * 1024 * 1024:
                raise ValueError('运行时压缩包路径或体积无效')
        # 正在运行时不覆盖DLL和EXE。
        for entry in archive.infolist():
            destination = vendor / entry.filename
            if entry.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            elif not destination.exists():
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(entry))
    for key in ('detector', 'pose', 'ai'):
        if sha(runtime / lock[key]['path']) != lock[key]['sha256']:
            raise ValueError('模型权重校验失败')
    values = load_values()
    for key in ('EDGE_SERVICE_TOKEN', 'AI_SERVICE_TOKEN'):
        values.setdefault(key, secrets.token_urlsafe(36))
    values.update(REAL_DEMO_ENABLED='true')
    values.setdefault('EDGE_NODE_ID', 'local-edge-01')
    values.setdefault('AI_SERVICE_URL', 'http://127.0.0.1:18188')
    ENV_FILE.write_text('# 仅供本机使用，禁止提交或分享\n' + '\n'.join(f'{key}={value}' for key, value in values.items()) + '\n', encoding='utf-8')
    virtual = runtime / 'demo/virtual-camera.y4m'
    if not virtual.is_file():
        ffmpeg = shutil.which('ffmpeg')
        if not ffmpeg:
            raise SystemExit('请安装FFmpeg并加入PATH')
        subprocess.run([ffmpeg, '-v', 'error', '-y', '-stream_loop', '-1', '-i', str(runtime / 'demo/upstream-demo.mp4'), '-t', '6', '-vf', 'scale=640:360,fps=10', '-pix_fmt', 'yuv420p', str(virtual)], check=True)
    print('模型、运行时与公开视频SHA256已校验；真实演示已启用，凭据未输出。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='下载或校验本机真实模型演示素材')
    parser.add_argument('--verify-only', action='store_true')
    prepare(not parser.parse_args().verify_only)
