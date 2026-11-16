#!/usr/bin/env python3
"""Delegate Android tasks to Wukong. Python 3.10+, Android platform-tools; no pip dependencies."""
import argparse
import contextlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import webbrowser
import shutil
from transports import WebUsbTransport, launch_standalone
from video import ScrcpySession

ASSETS = Path(__file__).resolve().parent.parent / 'assets'
STATE = Path(os.environ.get('WUKONG_STATE_DIR', Path.home() / '.wukong'))
TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'}
MAX_BODY = 65536


class Error(Exception):
    pass


class Device:
    def __init__(self, adb='adb', serial=None):
        self.adb = adb
        self.port = None
        self.token = None
        self.serial = serial
        self.mode = 'app'
        self.prefer = 'auto'

    def cmd(self, *args):
        prefix = [self.adb] + (['-s', self.serial] if self.serial else [])
        try:
            return subprocess.run(prefix + list(args), capture_output=True, timeout=25, check=True).stdout.decode().strip()
        except (subprocess.SubprocessError, OSError):
            raise Error('ADB failed. Check platform-tools, the selected device, and USB/wireless debugging authorization.') from None

    def connect(self):
        if not self.serial:
            devices = [line.split()[0] for line in self.cmd('devices').splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
            if len(devices) != 1:
                raise Error('Select one authorized device with --serial (automatic selection requires exactly one).')
            self.serial = devices[0]
        if self.cmd('get-state') != 'device':
            raise Error('Selected device is not available.')
        installed = 'package:com.wukong.phone' in self.cmd('shell', 'cmd package list packages -e com.wukong.phone').splitlines()
        if not installed and self.prefer=='app':raise Error('Wukong App is not installed or enabled.')
        if self.prefer=='shell' and installed:
            probe=Device(self.adb,self.serial);probe.prefer='app'
            try:
                probe.connect()
                if probe.json('GET','/v1/status').get('busy'):raise Error('Stop the App task before selecting standalone mode')
            finally:probe.close()
        if self.prefer == 'shell' or not installed:
            launch_standalone(self, ASSETS, Error)
            return self
        standalone_token=self.cmd('shell','if [ -r /data/local/tmp/wukong-standalone/access.token ]; then cat /data/local/tmp/wukong-standalone/access.token; fi').strip()
        if re.fullmatch('[a-f0-9]{64}',standalone_token):
            self.token=standalone_token;self.port=int(self.cmd('forward','tcp:0','tcp:39744'))
            try:standalone_status=self.json('GET','/v1/status')
            except Error:standalone_status=None
            if standalone_status:
                if standalone_status.get('busy'):
                    self.close();raise Error('Standalone task is still running; stop it before switching to the App.')
                with contextlib.suppress(Error):self.json('POST','/v1/disconnect',{})
                time.sleep(.5)
            self.close()
        self.cmd('shell', 'am start -W -n com.wukong.phone/.companion.CompanionActivity')
        prepared = self.cmd('shell', 'content call --uri content://com.wukong.phone.bootstrap --method companion')
        match = re.search(r'\btoken=([a-f0-9]{64})\b', prepared)
        port = re.search(r'\bport=(\d+)\b', prepared)
        if not match or not port or port.group(1) != '39743':
            raise Error('Install a Wukong build with companion support. Published v0.0.2 does not include this API.')
        self.token = match.group(1)
        self.port = int(self.cmd('forward', 'tcp:0', 'tcp:39743'))
        for _ in range(30):
            try:
                value = self.json('GET', '/v1/model')
                return self
            except Error:
                time.sleep(.1)
        self.close()
        raise Error('Wukong companion did not start. Open Wukong on the phone and retry.')

    def close(self):
        if self.port:
            with contextlib.suppress(Error):
                self.cmd('forward', '--remove', f'tcp:{self.port}')
            self.port = None

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=90)
        try:
            payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
            conn.request(method, path, payload, {'Authorization': f'Bearer {self.token}', 'Content-Type': 'application/json'})
            response = conn.getresponse()
            data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise Error('Device response too large.')
            if response.status >= 400:
                # Device API scrubs credentials; never reflect the submitted body locally.
                try:
                    message = json.loads(data).get('error', 'Request failed')
                except ValueError:
                    message = 'Invalid device response'
                raise Error(f'HTTP {response.status}: {message}')
            if path=='/v1/status':
                status=json.loads(data);status.setdefault('backend',self.mode);status['plannerLocation']='phone';data=json.dumps(status).encode()
            return data, response.getheader('Content-Type', 'application/json')
        except (OSError, http.client.HTTPException):
            raise Error('Device connection interrupted. Query the same request ID; do not resubmit with a new ID.') from None
        finally:
            conn.close()

    def json(self, method, path, body=None):
        return json.loads(self.request(method, path, body)[0])


def runtime_path():
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    return STATE / 'companion.json'


def write_state(value):
    target = runtime_path()
    temporary = target.with_suffix('.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output)
    os.replace(temporary, target)


def state():
    try:
        return json.loads(runtime_path().read_text())
    except (OSError, ValueError):
        raise Error('Start the setup page with: wukong.py serve --background') from None


def open_page():
    saved = state()
    webbrowser.open(f"http://127.0.0.1:{saved['port']}/#{saved['token']}")


def route_allowed(method, path):
    if method == 'GET' and path in ('/v1/status', '/v1/model', '/v1/preview'):
        return True
    if method == 'POST' and path in ('/v1/model', '/v1/model/test', '/v1/models', '/v1/tasks', '/v1/disconnect'):
        return True
    return bool(re.fullmatch(r'/v1/tasks/[a-f0-9-]{36}' + (r'(/logs)?' if method == 'GET' else r'/(stop|takeover|resume|approve|input)'), path))


def make_handler(device, browser_token):
    video_state={'instance':None};video_lock=threading.RLock()
    def close_video():
        with video_lock:
            session=video_state['instance']
            if session:session.close();video_state['instance']=None
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass  # Includes no request path, body, headers, or credentials in logs.

        def setup(self):
            super().setup()
            self.connection.settimeout(8)

        def reply(self, status, data, content_type='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def error(self, status, message):
            self.reply(status, json.dumps({'error': message}).encode())

        def do_GET(self):
            self.dispatch('GET')

        def do_POST(self):
            self.dispatch('POST')

        def dispatch(self, method):
            expected_host = f'127.0.0.1:{self.server.server_port}'
            if self.headers.get('Host') != expected_host or self.headers.get('Origin') not in (None, 'http://' + expected_host):
                self.error(403, 'Origin or Host rejected')
                return
            static = {'/': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css', '/tokens.css': 'tokens.css', '/logo.svg': 'logo.svg', '/webusb.js': 'webusb.js', '/video.js':'video.js'}
            if method == 'GET' and self.path in static:
                path = ASSETS / static[self.path]
                content_type = {'html': 'text/html; charset=utf-8', 'js': 'text/javascript; charset=utf-8', 'css': 'text/css; charset=utf-8', 'svg': 'image/svg+xml'}[path.suffix[1:]]
                self.reply(200, path.read_bytes(), content_type)
                return
            if not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + browser_token):
                self.error(401, 'Open this page with wukong.py open to authorize this browser session.')
                return
            if method == 'POST' and self.path == '/session/close':
                self.reply(200, b'{"closed":true}')
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            transport_route = isinstance(device, WebUsbTransport) and method == 'POST' and self.path in ('/v1/transport/connect','/v1/transport/poll','/v1/transport/reply','/v1/transport/disconnect','/v1/transport/bootstrap','/v1/transport/heartbeat')
            runtime_route = method == 'GET' and self.path in ('/v1/runtime.jar','/v1/runtime-manifest','/v1/scrcpy.jar','/v1/scrcpy-manifest')
            video_route=not isinstance(device,WebUsbTransport) and (method,self.path) in (('POST','/v1/video/start'),('GET','/v1/video/stream'),('POST','/v1/video/input'),('POST','/v1/video/stop'))
            if not transport_route and not runtime_route and not video_route and not route_allowed(method, self.path):
                self.error(404, 'Unknown endpoint')
                return
            try:
                if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) > 1:
                    raise ValueError('Invalid request framing')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= (6*1024*1024 if transport_route else MAX_BODY):
                    raise ValueError('Request too large')
                body = None
                if method == 'POST':
                    if self.headers.get_content_type() != 'application/json' or length == 0:
                        raise ValueError('Expected JSON')
                    data = self.rfile.read(length)
                    if len(data) != length:
                        raise ValueError('Incomplete request')
                    body = json.loads(data)
                    if not isinstance(body, dict):
                        raise ValueError('Expected a JSON object')
                if runtime_route:
                    artifact = ASSETS/'runtime'/({'/v1/runtime.jar':'wukong-runtime.jar','/v1/runtime-manifest':'manifest.json','/v1/scrcpy.jar':'scrcpy-server.jar','/v1/scrcpy-manifest':'scrcpy.json'}[self.path])
                    self.reply(200, artifact.read_bytes(), 'application/octet-stream' if self.path.endswith('.jar') else 'application/json')
                    return
                if video_route:
                    if self.path=='/v1/video/start':
                        target=int(body.get('displayId',0));status=device.json('GET','/v1/status')
                        if target!=0 and target!=status.get('virtualDisplayId'):raise Error('Virtual display is no longer available')
                        with video_lock:
                            session=video_state['instance']
                            if not session or session.closed or session.display_id!=target:
                                close_video();session=ScrcpySession(device,ASSETS,Error,target);video_state['instance']=session
                            result={'width':session.width,'height':session.height,'codec':'h264','displayId':target}
                        self.reply(200,json.dumps(result).encode());return
                    if self.path=='/v1/video/stop':
                        close_video();self.reply(200,b'{"stopped":true}');return
                    session=video_state['instance']
                    if not session or session.closed:raise Error('Video stream has not started')
                    if self.path=='/v1/video/input':
                        with session.control_lock:
                            task=device.json('GET','/v1/tasks/'+str(uuid.UUID(body['taskId'])))
                            if task['phase']!='PAUSED' or task.get('displayId')!=session.display_id:raise Error('Take over this task before interacting with its display')
                            session.input(body)
                        self.reply(200,b'{"accepted":true}');return
                    with video_lock:
                        if session.streaming:raise Error('Only one viewer can consume this video stream')
                        session.streaming=True
                    self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers()
                    try:
                        self.wfile.write(session.metadata);self.wfile.flush()
                        import socket,select
                        while not session.closed:
                            if select.select([self.connection],[],[],0)[0] and self.connection.recv(1,socket.MSG_PEEK)==b'':break
                            try:data=session.video.recv(65536)
                            except socket.timeout:continue
                            if not data:break
                            self.wfile.write(data);self.wfile.flush()
                    except OSError:pass
                    finally:
                        session.close();self.close_connection=True
                    return
                if transport_route:
                    action = self.path.rsplit('/',1)[-1]
                    if action == 'bootstrap':
                        result = bootstrap_credential(body['serial'],body.get('credential'))
                    else:
                        callback = {'connect':device.connected,'poll':lambda value:device.poll(value['session']),'reply':device.complete,'disconnect':device.disconnected,'heartbeat':device.heartbeat}[action]
                        result = callback(body)
                    if action=='connect':
                        try:
                            saved=state()
                            if saved.get('pid')==os.getpid():
                                saved['serial']=device.serial;write_state(saved)
                        except Error:pass
                    self.reply(200,json.dumps(result).encode())
                    return
                data, kind = device.request(method, self.path, body)
                self.reply(200, data, kind)
            except (ValueError, TimeoutError):
                self.error(400, 'Invalid JSON request')
            except Error as error:
                self.error(502, str(error))
    Handler.close_video=staticmethod(close_video)
    return Handler


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True
    # Bound concurrent requests, including slow unauthenticated clients.
    slots = threading.BoundedSemaphore(12)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def bootstrap_credential(serial, credential=None):
    import hashlib
    name = hashlib.sha256(serial.encode()).hexdigest()
    folder = STATE/'devices';folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    path = folder/(name+'.json')
    try:
        value=json.loads(path.read_text())
    except (OSError,ValueError):
        value={'token':secrets.token_hex(32)}
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as out:json.dump(value,out)
    if credential is not None:
        if not re.fullmatch('[a-f0-9]{64}',credential):raise Error('Invalid activation credential')
        value['token']=credential
        fd=os.open(path,os.O_WRONLY|os.O_TRUNC,0o600)
        with os.fdopen(fd,'w') as out:json.dump(value,out)
    return value


def select_backend(args):
    if args.backend == 'webusb' or not shutil.which(args.adb):
        if args.backend in ('app','shell'):raise Error('Local ADB is unavailable')
        return WebUsbTransport(Error,args.serial)
    probe=Device(args.adb,args.serial)
    if not args.serial:
        devices=[line.split()[0] for line in probe.cmd('devices').splitlines()[1:] if len(line.split())>=2 and line.split()[1]=='device']
        if not devices:return WebUsbTransport(Error)
        if len(devices)!=1:raise Error('Multiple ADB devices; choose --serial explicitly.')
        probe.serial=devices[0]
    probe.prefer=args.backend
    return probe


class HostProxy:
    def __init__(self,saved):self.saved=saved
    def request(self,method,path,body=None):
        conn=http.client.HTTPConnection('127.0.0.1',self.saved['port'],timeout=90)
        try:
            conn.request(method,path,json.dumps(body).encode() if body is not None else None,{'Authorization':'Bearer '+self.saved['token'],'Content-Type':'application/json'})
            response=conn.getresponse();data=response.read(4*1024*1024+1)
            if response.status>=400:raise Error(json.loads(data).get('error','Companion failed'))
            return data,response.getheader('Content-Type')
        finally:conn.close()
    def json(self,method,path,body=None):return json.loads(self.request(method,path,body)[0])
    def close(self):pass


def cli_backend(args):
    # A durable host bridge owns transfers; Android owns all planning. A short-lived
    # CLI can exit immediately after submission without ending an Android task.
    settings=argparse.Namespace(adb=args.adb,serial=args.serial,backend=args.backend,port=19831,no_browser=True)
    import io
    with contextlib.redirect_stdout(io.StringIO()):background(settings)
    return HostProxy(state())

def serve(args):
    device = select_backend(args)
    server = None
    token = secrets.token_urlsafe(32)
    try:
        if isinstance(device,Device): device.connect()
        server = LocalServer(('127.0.0.1', args.port), make_handler(device, token))
        write_state({'pid': os.getpid(), 'port': server.server_port, 'token': token, 'serial': device.serial, 'mode':device.mode})
        print(json.dumps({'url': f'http://127.0.0.1:{server.server_port}/', 'serial': device.serial, 'pid': os.getpid()}), flush=True)
        if not args.no_browser:
            open_page()
        server.serve_forever(poll_interval=.25)
    finally:
        if server:
            server.RequestHandlerClass.close_video()
            server.server_close()
        device.close()
        with contextlib.suppress(Error, OSError):
            if state().get('token') == token:
                runtime_path().unlink()


def background(args):
    # No secrets on the subprocess command line or in its stdout.
    try:
        previous = state()
    except Error:
        previous = None
    if previous:
        connection = http.client.HTTPConnection('127.0.0.1', previous['port'], timeout=3)
        healthy = False
        try:
            connection.request('GET', '/v1/model', headers={'Authorization': 'Bearer ' + previous['token']})
            response = connection.getresponse()
            response.read()
            healthy = response.status == 200
            if not healthy:
                # A phone disconnect/restart can invalidate the device connection while
                # the local page is alive. Close that bridge before starting a fresh one.
                connection.request('POST', '/session/close', '{}', {'Authorization': 'Bearer ' + previous['token'], 'Content-Type': 'application/json'})
                connection.getresponse().read()
        except (OSError, http.client.HTTPException):
            pass
        finally:
            connection.close()
        if healthy:
            desired=getattr(args,'backend','auto')
            if desired!='auto' and previous.get('mode') not in (desired, {'shell':'standalone'}.get(desired)):
                raise Error('Close the existing companion before switching backend modes.')
            if args.serial and previous.get('serial') != args.serial:
                raise Error('A setup page is already connected to another device. Close it before switching.')
            if not args.no_browser:
                open_page()
            print(json.dumps({'url': f"http://127.0.0.1:{previous['port']}/", 'serial': previous['serial'], 'alreadyRunning': True}))
            return
    command = [sys.executable, str(Path(__file__).resolve()), '--adb', args.adb, '--backend',getattr(args,'backend','auto')]
    if args.serial:
        command += ['--serial', args.serial]
    command += ['serve', '--port', str(args.port)]
    if args.no_browser:
        command += ['--no-browser']
    child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    for _ in range(120):
        if child.poll() is not None:
            raise Error('Setup server could not start. Run serve in the foreground for diagnostics.')
        try:
            saved = state()
            if saved.get('pid') == child.pid:
                print(json.dumps({'url': f"http://127.0.0.1:{saved['port']}/", 'serial': saved['serial'], 'pid': child.pid}))
                return
        except Error:
            pass
        time.sleep(.25)
    raise Error('Setup is still starting. Run status to inspect the device.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--backend',choices=['auto','app','shell','webusb'],default='auto')
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('status', 'open', 'close'):
        sub.add_parser(name)
    web = sub.add_parser('serve')
    web.add_argument('--port', type=int, default=19831)
    web.add_argument('--background', action='store_true')
    web.add_argument('--no-browser', action='store_true')
    run = sub.add_parser('run')
    run.add_argument('--goal', required=True)
    run.add_argument('--request-id', default=None)
    run.add_argument('--display', choices=['virtual', 'physical'], default='virtual')
    run.add_argument('--initial-package')
    for name in ('task', 'stop', 'takeover', 'resume', 'wait', 'logs'):
        command = sub.add_parser(name)
        command.add_argument('id', type=lambda value: str(uuid.UUID(value)))
        if name == 'wait':
            command.add_argument('--timeout', type=int, default=45)
        if name == 'logs':
            command.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'open':
        open_page()
        print('Opened the authorized setup page.')
        return
    if args.command == 'close':
        saved = state()
        connection = http.client.HTTPConnection('127.0.0.1', saved['port'], timeout=3)
        try:
            connection.request('POST', '/session/close', '{}', {'Authorization': 'Bearer ' + saved['token'], 'Content-Type': 'application/json'})
            if connection.getresponse().status != 200:
                raise Error('Unable to close the companion session.')
        finally:
            connection.close()
        print('{"closed": true, "phoneTaskContinues": true}')
        return
    if args.command == 'serve':
        (background if args.background else serve)(args)
        return
    device = cli_backend(args)
    try:
        if args.command == 'status':
            result = device.json('GET', '/v1/status')
        elif args.command == 'run':
            request_id = str(uuid.UUID(args.request_id)) if args.request_id else str(uuid.uuid4())
            print(f'Request ID: {request_id} (reuse this ID if the connection fails)', file=sys.stderr, flush=True)
            payload = {'id': request_id, 'goal': args.goal, 'display': args.display}
            if args.initial_package:
                payload['initialPackage'] = args.initial_package
            result = device.json('POST', '/v1/tasks', payload)
        elif args.command == 'logs':
            data, _ = device.request('GET', f'/v1/tasks/{args.id}/logs')
            # Log exports can contain personal task content; never overwrite an existing file.
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
            result = {'path': str(args.output.resolve()), 'bytes': len(data)}
        elif args.command in ('stop', 'takeover', 'resume'):
            result = device.json('POST', f'/v1/tasks/{args.id}/{args.command}', {})
        else:
            deadline = time.monotonic() + (max(0, min(args.timeout, 55)) if args.command == 'wait' else 0)
            while True:
                result = device.json('GET', f'/v1/tasks/{args.id}')
                if result['phase'] in TERMINAL | {'AWAITING_APPROVAL', 'PAUSED'} or time.monotonic() >= deadline:
                    break
                time.sleep(1.5)
        print(json.dumps(result, ensure_ascii=False))
    finally:
        device.close()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except (Error, ValueError, OSError) as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
