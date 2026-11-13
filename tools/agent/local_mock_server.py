"""Local-only HTTP Agent for repeatable configuration/admission smoke checks.

Run: python -B tools/agent/local_mock_server.py --port 8090
Use --fail to simulate an unavailable provider. No facts or real recommendations.
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8090)
    parser.add_argument('--provider-id', default='local.mock')
    parser.add_argument('--fail', action='store_true')
    args = parser.parse_args()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, body):
            data = json.dumps(body, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            self.reply(200 if self.path == '/healthz' else 404, {'mock': True})

        def do_POST(self):
            if self.path != '/v1/invoke':
                self.reply(404, {})
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 262144:
                    raise ValueError()
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict):
                    raise ValueError()
            except (ValueError, json.JSONDecodeError):
                self.reply(400, {'error': 'Invalid JSON request'})
                return
            self.reply(503 if args.fail else 200, {
                'schemaVersion': '1.0', 'providerId': args.provider_id, 'status': 'OK',
                'facts': [], 'advice': '本地模拟助手：票价未知，请补充需求。',
                'unknownFields': ['destination', 'budget'],
                'notices': ['仅用于接入验证，不是真实推荐。'],
                'usage': {'elapsedMs': 0, 'modelCalls': 0}})

    with ThreadingHTTPServer(('127.0.0.1', args.port), Handler) as server:
        print('Local mock listening on 127.0.0.1:%d' % server.server_port, flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
