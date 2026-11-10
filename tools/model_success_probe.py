# -*- coding: utf-8 -*-
"""模型成功路径实测：本地 OpenAI 兼容桩 + 真实 jar 服务，走一次完整的 /api/agent/plan。

**这不是真实密钥实测。** 脚本用 127.0.0.1 上的本地桩冒充模型端点，
不联网、不消耗 DeepSeek 配额，只用来证明「密钥可用时模型建议确实会生成并回传」。
真实 `DEEPSEEK_API_KEY` 的成功路径仍需人工在隔离环境补测，不得把本脚本结果写成已实测。

前置：先执行 `mvn -o clean package` 生成 `travel-server/target/travel-server-0.1.0-SNAPSHOT.jar`。
用法：`python -B tools/model_success_probe.py`，输出一段 JSON 摘要，全部断言通过时 exit=0。
临时数据、日志与进程都在 F 盘临时目录，退出时清理。
"""
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'travel-server' / 'target' / 'travel-server-0.1.0-SNAPSHOT.jar'
TMP_ROOT = Path(os.environ.get('WB_TMP', 'F:/wb_tmp'))
API_KEY = 'local-probe-key'
STUB_TEXT = '（本地桩输出）按候选景点整理两日行程建议；票价与开放时间以知识库标注为准，未知项不按免费处理。'

state = {'calls': []}


class StubHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def read_body(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b';')[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b''.join(chunks)
        return self.rfile.read(int(self.headers.get('Content-Length', '0') or '0'))

    def do_POST(self):
        raw = self.read_body()
        state.setdefault('diagnostics', []).append({
            'path': self.path,
            'contentLength': self.headers.get('Content-Length'),
            'transferEncoding': self.headers.get('Transfer-Encoding'),
            'bearerMatches': self.headers.get('Authorization') == f'Bearer {API_KEY}',
            'bodyPrefix': raw[:160].decode('utf-8', 'replace'),
            'bodyBytes': len(raw),
        })
        if not self.path.endswith('/chat/completions'):
            self.send_error(404)
            return
        if self.headers.get('Authorization') != f'Bearer {API_KEY}':
            self.send_error(401)
            return
        try:
            body = json.loads(raw)
        except ValueError:
            self.send_error(400)
            return
        state['calls'].append({
            'model': body.get('model'),
            'temperature': body.get('temperature'),
            'max_tokens': body.get('max_tokens'),
            'stream': body.get('stream'),
            'messageRoles': [m.get('role') for m in body.get('messages', [])],
            'userPromptChars': len(body.get('messages', [{}, {}])[-1].get('content', '')),
            'requestBytes': len(raw),
            'at': time.strftime('%Y-%m-%dT%H:%M:%S'),
        })
        payload = json.dumps({
            'id': 'probe-local-1',
            'object': 'chat.completion',
            'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': STUB_TEXT},
                         'finish_reason': 'stop'}],
            'usage': {'prompt_tokens': 120, 'completion_tokens': 40, 'total_tokens': 160},
        }, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def client():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(opener, base, path, body=None, form=False):
    data = None if body is None else (urllib.parse.urlencode(body).encode()
                                      if form else json.dumps(body).encode())
    req = urllib.request.Request(base + path, data=data,
        headers={'Content-Type': 'application/x-www-form-urlencoded' if form
                 else 'application/json'})
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.code, json.loads(raw) if raw else None


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    if not JAR.exists():
        print('MODEL SUCCESS PATH PROBE: FAIL 需要先 mvn -o clean package 生成 jar', file=sys.stderr)
        return 2
    TMP_ROOT.mkdir(parents=True, exist_ok=True)
    stub = ThreadingHTTPServer(('127.0.0.1', 0), StubHandler)
    stub_port = stub.server_address[1]
    threading.Thread(target=stub.serve_forever, daemon=True).start()

    process = None
    with tempfile.TemporaryDirectory(prefix='travel-model-probe-', dir=str(TMP_ROOT)) as temporary:
        tmp = Path(temporary)
        env = dict(os.environ, AMAP_KEY='', DEEPSEEK_API_KEY='',
                   DEEPSEEK_BASE_URL=f'http://127.0.0.1:{stub_port}',
                   NO_PROXY='127.0.0.1,localhost', PYTHONIOENCODING='utf-8')
        port = free_port()
        base = f'http://127.0.0.1:{port}'
        with (tmp / 'server.log').open('w', encoding='utf-8') as log:
            try:
                process = subprocess.Popen([
                    'java', '-jar', str(JAR),
                    f'--server.port={port}', '--server.address=127.0.0.1',
                    f'--travel.data-directory={tmp / "data"}',
                    f'--travel.attraction-kb-path={ROOT / "data/attractions_index.json"}',
                    '--travel.amap.key=',
                    f'--travel.ai.api-key={API_KEY}',
                    f'--travel.ai.base-url=http://127.0.0.1:{stub_port}',
                    '--travel.ai.model=probe-model',
                ], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                for _ in range(120):
                    if process.poll() is not None:
                        raise RuntimeError('server exited; see ' + str(log.name))
                    try:
                        if request(client(), base, '/api/health')[0] == 200:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    time.sleep(0.25)
                else:
                    raise RuntimeError('server startup timed out')

                owner = client()
                credentials = {'username': 'model_probe_owner', 'password': 'Password123'}
                code, _ = request(owner, base, '/api/account/register', credentials)
                assert code == 201, f'register status={code}'
                code, _ = request(owner, base, '/api/login', credentials, form=True)
                assert code == 200, f'login status={code}'

                code, status = request(owner, base, '/api/agent/status')
                assert code == 200 and status.get('modelConfigured') is True, \
                    f'modelConfigured expected true, got {status}'

                draft_req = {'destination': '杭州', 'departDate': '2026-11-21',
                    'returnDate': '2026-11-22', 'days': 2, 'travelers': 2,
                    'budget': 1000, 'currency': 'CNY', 'preferences': []}
                started = time.perf_counter()
                code, draft = request(owner, base, '/api/agent/plan', draft_req)
                elapsed_ms = round((time.perf_counter() - started) * 1000, 1)

                summary = {
                    'mode': 'local_openai_compatible_stub',
                    'realApiKeyUsed': False,
                    'httpStatus': code,
                    'elapsedMs': elapsed_ms,
                    'stubCalls': len(state['calls']),
                    'stubRequest': state['calls'][0] if state['calls'] else None,
                    'stubDiagnostics': state.get('diagnostics', []),
                    'advice': (draft or {}).get('advice'),
                    'sources': (draft or {}).get('sources'),
                    'notices': (draft or {}).get('notices'),
                    'dailySchedules': len((draft or {}).get('dailySchedules') or []),
                }
                print(json.dumps(summary, ensure_ascii=False, indent=2))
                errors = []
                if code != 200:
                    errors.append(f'/api/agent/plan status={code}')
                if summary['advice'] != STUB_TEXT:
                    errors.append('advice 未回传本地桩输出')
                if not state['calls']:
                    errors.append('本地桩没有收到模型请求')
                if any('未配置模型密钥' in n for n in summary['notices'] or []):
                    errors.append('成功路径仍出现未配置密钥提示')
                if not any('DeepSeek' in s for s in summary['sources'] or []):
                    errors.append('sources 未标注模型来源')
                if errors:
                    for error in errors:
                        print(f'MODEL SUCCESS PATH PROBE: FAIL {error}', file=sys.stderr)
                    return 1
                print('MODEL SUCCESS PATH PROBE: PASS（本地桩，非真实密钥实测）')
                return 0
            finally:
                if process is not None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                stub.shutdown()
                stub.server_close()


if __name__ == '__main__':
    raise SystemExit(main())
