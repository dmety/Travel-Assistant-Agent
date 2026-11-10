"""真实服务回归入口：先 mvn clean verify，再运行 python -B tools/acceptance.py。

仅使用标准库、临时数据目录和随机端口；不调用外部API，不改动用户数据。
"""
import concurrent.futures
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'travel-server/target/travel-server-0.1.0-SNAPSHOT.jar'


def client():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(opener, base, path, body=None, method=None, form=False):
    data = None if body is None else (urllib.parse.urlencode(body).encode() if form
        else json.dumps(body).encode())
    req = urllib.request.Request(base + path, data=data, method=method,
        headers={'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'})
    try:
        response = opener.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.code, json.loads(raw) if raw else None


def check(condition, description):
    if not condition:
        raise AssertionError(description)
    print('PASS', description, flush=True)


def main():
    check(JAR.exists(), 'packaged server exists')
    env = dict(os.environ, AMAP_KEY='', DEEPSEEK_API_KEY='', NO_PROXY='127.0.0.1,localhost',
        PYTHONIOENCODING='utf-8')
    with tempfile.TemporaryDirectory(prefix='travel-acceptance-') as temporary:
        data = Path(temporary) / 'data'
        process = None
        with (Path(temporary) / 'server.log').open('w', encoding='utf-8') as log:
            def stop():
                nonlocal process
                if process is not None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                    process = None

            def start():
                nonlocal process
                with socket.socket() as sock:
                    sock.bind(('127.0.0.1', 0))
                    port = sock.getsockname()[1]
                base = f'http://127.0.0.1:{port}'
                process = subprocess.Popen(['java', '-jar', str(JAR),
                    f'--server.port={port}', '--server.address=127.0.0.1',
                    f'--travel.data-directory={data}',
                    f'--travel.attraction-kb-path={ROOT / "data/attractions_index.json"}',
                    '--travel.amap.key=', '--travel.ai.api-key='], cwd=ROOT, env=env,
                    stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                for _ in range(120):
                    if process.poll() is not None:
                        raise RuntimeError('server exited; see ' + str(log.name))
                    try:
                        if request(client(), base, '/api/health')[0] == 200:
                            return base
                    except (OSError, urllib.error.URLError):
                        pass
                    time.sleep(0.25)
                raise RuntimeError('server startup timed out')

            try:
                base = start()
                subprocess.run([sys.executable, '-B', str(ROOT / 'tools/itinerary_acceptance.py'),
                    '--base', base, '--fail-fast', '--quiet-json'], cwd=ROOT, env=env, check=True)
                owner, stranger = client(), client()
                credentials = {'username': 'acceptance_owner', 'password': 'Password123'}
                check(request(owner, base, '/api/account/register', credentials)[0] == 201, 'register')
                check(request(owner, base, '/api/account/register', credentials)[0] == 409, 'duplicate rejected')
                check(request(owner, base, '/api/login', dict(credentials, password='WrongPassword'), form=True)[0] == 401, 'wrong password rejected')
                check(request(owner, base, '/api/login', credentials, form=True)[0] == 200, 'login')
                # A-2：模型购票建议会消耗配额，匿名请求必须在安全层被拒，不能进入控制器。
                check(request(client(), base, '/api/agent/tickets/advice', {})[0] == 401,
                    'anonymous model advice is rejected with 401')

                # ---- #5 基础业务闭环：草案 -> 补问 -> 检查 -> 确认保存 -> 重启重读 ----
                draft_req = {'destination': '杭州', 'departDate': '2026-11-21',
                    'returnDate': '2026-11-22', 'days': 2, 'travelers': 2,
                    'budget': 1000, 'currency': 'CNY', 'preferences': []}
                before = len(request(owner, base, '/api/itineraries')[1])
                code, draft = request(owner, base, '/api/agent/plan', draft_req)
                check(code == 200 and bool(draft['dailySchedules']), 'plan returns a daily draft')
                check(not draft['questions'], 'complete request has no follow-up questions')
                check(all('attractionId' in item and 'source' in item
                          for day in draft['dailySchedules'] for item in day['items']),
                      'draft items carry attraction id and source')
                check(len(request(owner, base, '/api/itineraries')[1]) == before,
                      'generating a draft does not create an itinerary')
                code, missing = request(owner, base, '/api/agent/plan', {'destination': '杭州'})
                fields = {q['field'] for q in missing['questions']}
                check(code == 200 and {'departDate', 'returnDate', 'days', 'travelers', 'budget'} <= fields,
                      'missing fields produce structured follow-up questions')
                code, inverted = request(owner, base, '/api/agent/plan',
                    dict(draft_req, departDate='2026-11-25', returnDate='2026-11-21'))
                check(any(c['code'] == 'RETURN_BEFORE_DEPART' for c in inverted['checks']),
                      'inverted dates are reported in draft checks')
                check(draft['budgetEstimate']['verdict'] in
                      {'NO_BUDGET', 'EXCEEDS_LIMIT', 'CANNOT_DETERMINE', 'WITHIN_KNOWN_SCOPE'},
                      'draft carries a budget verdict with an explicit verdict code')
                code, payload = request(owner, base, '/api/agent/plan/materialize',
                    {'title': '闭环验收-杭州二日游', 'request': draft_req, 'attractionIds': []})
                check(code == 200 and bool(payload['dailySchedules']),
                      'confirm produces a read-only itinerary payload')
                check(len(request(owner, base, '/api/itineraries')[1]) == before,
                      'materializing a payload still does not create an itinerary')
                code, closure = request(owner, base, '/api/itineraries',
                    {'title': payload['title'], 'plan': payload['plan']})
                check(code == 201, 'confirmed draft creates an itinerary')
                closure_path = '/api/itineraries/' + closure['id']
                code, closure = request(owner, base,
                    closure_path + '/daily?version=' + str(closure['version']),
                    payload['dailySchedules'], method='PUT')
                check(code == 200, 'daily schedules saved using the created version')
                closure_note = payload['dailySchedules'][0]['activities'][0]['note']
                check('attractionId=' in closure_note,
                      'saved activity keeps a traceable attraction id')

                def register_racer(password):
                    return request(client(), base, '/api/account/register',
                        {'username': 'same_concurrent', 'password': password})[0]
                with concurrent.futures.ThreadPoolExecutor(2) as executor:
                    codes = list(executor.map(register_racer, ['Password111', 'Password222']))
                check(sorted(codes) == [201, 409], 'concurrent duplicate: one success, one conflict')
                code, trip = request(owner, base, '/api/itineraries', {'title': 'restart-check'})
                check(code == 201, 'create restart fixture')
                path = '/api/itineraries/' + trip['id']
                check(request(stranger, base, path)[0] == 401, 'anonymous cannot read itinerary')
                code, trip = request(owner, base, path + '/activities?version=1',
                    {'date': '2026-11-21', 'title': '八达岭长城'})
                check(code == 200, 'create activity for ticket registration')
                activity_id = trip['dailySchedules'][0]['activities'][0]['id']
                ticket_path = path + '/activities/' + activity_id + '/ticket-record'
                code, trip = request(owner, base, ticket_path + '?version=2',
                    {'attractionId': 'amap-B000A45467', 'product': '成人门票',
                     'visitDate': '2026-11-21', 'travelers': 2, 'paidTotal': 70}, method='PUT')
                check(code == 200 and trip['dailySchedules'][0]['activities'][0]['ticketRecord']['source']
                    == 'USER_REPORTED', 'ticket registration is explicitly user reported')

                source = json.loads((ROOT / 'data/attractions_index.json').read_text(encoding='utf-8'))
                code, result = request(stranger, base, '/api/attractions?city=' + urllib.parse.quote('杭州'))
                check(code == 200 and result['items'], 'real Hangzhou query')
                item = next(x for x in source['attractions'] if x['id'] == 'osm-node-1218840067')
                code, detail = request(stranger, base, '/api/attractions/' + item['id'])
                check(code == 200 and detail['ticket'] == item['ticket'], 'updated ticket reaches Java detail API')
                check(request(owner, base, '/api/logout', {}, method='POST')[0] == 200, 'logout')
                check(request(owner, base, path)[0] == 401, 'logged-out session rejected')
                accounts = json.loads((data / 'accounts.json').read_text(encoding='utf-8'))
                check(all(x['passwordHash'].startswith('$2') for x in accounts), 'passwords stored as BCrypt')
                stop()
                base = start()
                owner = client()
                check(request(owner, base, '/api/login', credentials, form=True)[0] == 200, 'account survives actual process restart')
                check(request(owner, base, path)[1]['title'] == 'restart-check', 'itinerary survives actual process restart')
                restored = request(owner, base, path)[1]
                record = restored['dailySchedules'][0]['activities'][0]['ticketRecord']
                check(record['source'] == 'USER_REPORTED' and record['paidTotal'] == 70,
                    'ticket registration survives actual process restart without creating expenses')
                check(not restored['expenses'], 'registration does not duplicate expenses')
                restored_closure = request(owner, base, closure_path)[1]
                check(restored_closure['title'] == '闭环验收-杭州二日游',
                      'confirmed draft survives actual process restart')
                check(restored_closure['plan']['departDate'] == '2026-11-21',
                      'draft dates survive actual process restart')
                check(restored_closure['dailySchedules'][0]['activities'][0]['note'] == closure_note,
                      'daily draft content survives restart unchanged')

                check(request(owner, base, path, method='DELETE')[0] == 200, 'delete persisted itinerary')
                stop()
                base = start()
                owner = client()
                request(owner, base, '/api/login', credentials, form=True)
                check(request(owner, base, path)[0] == 404, 'deleted itinerary stays absent after restart')
            except Exception:
                log.flush()
                print('SERVER LOG (tail):', flush=True)
                print(Path(log.name).read_text(encoding='utf-8', errors='replace')[-6000:], flush=True)
                raise
            finally:
                stop()
    print('ALL REAL-SERVICE ACCEPTANCE CHECKS PASSED')


if __name__ == '__main__':
    main()
