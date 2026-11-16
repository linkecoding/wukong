"""Transport selection only. No model requests or planning runs on the computer."""
import base64
import hashlib
import http.client
import json
from pathlib import Path
import queue
import secrets
import shlex
import socket
import threading
import time
import uuid


class WebUsbTransport:
    mode = 'webusb'
    serial = None
    planner_location = 'phone'

    def __init__(self, error_type, expected_serial=None):
        self.Error = error_type
        self.expected_serial = expected_serial
        self.pending = {}
        self.queue = queue.Queue(16)
        self.lock = threading.Lock()
        self.session = None
        self.kind = None
        self.last_seen = 0
        self.closed = False

    def connected(self, body):
        serial = body['serial']
        if self.expected_serial and serial != self.expected_serial:
            raise self.Error('Selected USB device does not match --serial.')
        with self.lock:
            if self.session and time.monotonic() - self.last_seen < 15:
                raise self.Error('A browser already owns this device connection. Disconnect it first.')
            self.session = secrets.token_urlsafe(24)
            self.serial = serial
            self.kind = body['kind']
            if self.kind not in ('app', 'standalone'):
                raise self.Error('Invalid USB runtime kind')
            self.last_seen = time.monotonic()
            return {'session': self.session}

    def poll(self, session):
        if session != self.session or self.closed:
            raise self.Error('Browser connection expired')
        self.last_seen = time.monotonic()
        try:
            return self.queue.get(timeout=5)
        except queue.Empty:
            return {'idle': True}

    def heartbeat(self, body):
        if body.get("session") != self.session:raise self.Error("Browser connection expired")
        self.last_seen=time.monotonic();return {"ok":True}

    def complete(self, body):
        if body.get('session') != self.session:
            raise self.Error('Browser connection expired')
        self.last_seen = time.monotonic()
        with self.lock:
            waiting = self.pending.get(body['id'])
            if waiting:
                waiting['reply'] = body
                waiting['event'].set()
        return {'ok': True}

    def disconnected(self, body):
        if body.get('session') == self.session:
            with self.lock:
                self.session = None
                for item in self.pending.values():
                    item['reply'] = {'error': 'USB disconnected; inspect the original request ID before retrying.'}
                    item['event'].set()
        return {'ok': True}

    def request(self, method, path, body=None):
        if not self.session or time.monotonic()-self.last_seen > 15:
            if method == 'GET' and path == '/v1/status':
                return json.dumps({'protocol': 1, 'backend': 'webusb', 'plannerLocation': 'phone', 'needsUsb': True, 'expectedSerial':self.expected_serial, 'engineReady': False, 'overlayReady': True,
                                   'model': {'configured': False, 'endpoint': '', 'model': '', 'providerId': 'custom', 'presets': []}}).encode(), 'application/json'
            if method == 'GET' and path == '/v1/model':
                return b'{"configured":false,"needsUsb":true}', 'application/json'
            raise self.Error('Connect your phone with the WebUSB button first.')
        request_id = str(uuid.uuid4())
        item = {'event': threading.Event()}
        with self.lock:
            self.pending[request_id] = item
        try:
            self.queue.put({'id': request_id, 'method': method, 'path': path, 'body': body}, timeout=2)
            if not item['event'].wait(85):
                raise self.Error('USB request timed out; query the same task ID, never replay with a new ID.')
            reply = item['reply']
            if reply.get('error'):
                raise self.Error(reply['error'])
            data = base64.b64decode(reply['data'], validate=True)
            if len(data)>4*1024*1024:
                raise self.Error('Device response too large')
            if reply['status'] >= 400:
                raise self.Error('HTTP %s: %s' % (reply['status'],json.loads(data).get('error','Request failed')))
            return data, reply.get('contentType','application/json')
        except queue.Full:
            raise self.Error('USB bridge busy') from None
        finally:
            with self.lock:
                self.pending.pop(request_id, None)

    def json(self, method, path, body=None):
        return json.loads(self.request(method,path,body)[0])

    def close(self):
        self.closed = True
        self.disconnected({'session':self.session})


def launch_standalone(device, assets, error_type):
    """Push a dex-only JAR and launch it. Does not install a package or export any App key."""
    jar = assets / 'runtime' / 'wukong-runtime.jar'
    manifest = json.loads((assets/'runtime'/'manifest.json').read_text()) if jar.exists() else {}
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest()!=manifest.get('sha256'):
        raise error_type('Standalone runtime missing or invalid. Use a complete skill archive or build it with scripts/package-skill.py.')
    # Separate namespace/port from the installed App. Forced shell tests require App idle.
    import os
    state_dir=Path(os.environ.get('WUKONG_STATE_DIR',Path.home()/'.wukong'))/'devices'
    state_dir.mkdir(mode=0o700,parents=True,exist_ok=True)
    state_file=state_dir/(hashlib.sha256(device.serial.encode()).hexdigest()+'.json')
    try: saved=json.loads(state_file.read_text())
    except (OSError,ValueError): saved={'token':secrets.token_hex(32)}
    phone_token=device.cmd('shell','if [ -r /data/local/tmp/wukong-standalone/access.token ]; then cat /data/local/tmp/wukong-standalone/access.token; fi').strip()
    import re
    if re.fullmatch('[a-f0-9]{64}',phone_token):saved['token']=phone_token
    token=saved['token']
    device.token=token
    device.port=int(device.cmd('forward','tcp:0','tcp:39744'))
    try: status=device.json('GET','/v1/status')
    except error_type: status=None
    if status and status.get('plannerLocation')=='phone':
        if status.get('buildId')==manifest.get('buildId'):
            fd=os.open(state_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            with os.fdopen(fd,'w') as output:json.dump(saved,output)
            device.mode='standalone';return
        if status.get('busy'):
            device.close();raise error_type('Existing runtime is running a task; stop it before upgrading.')
        try:device.json('POST','/v1/disconnect',{})
        except error_type:pass
        for attempt in range(50):
            alive=device.cmd('shell',"p=$(cat /data/local/tmp/wukong-standalone/runtime.pid 2>/dev/null); case \"$p\" in ''|*[!0-9]*) exit 0;; esac; if [ -r /proc/$p/cmdline ]; then tr '\\000' ' ' </proc/$p/cmdline; fi")
            if 'com.wukong.phone.StandaloneMain' not in alive:break
            time.sleep(.1)
        else:raise error_type('Existing runtime is still shutting down; retry after it stops.')
    device.close()
    rotated=device.cmd('shell','if [ -r /data/local/tmp/wukong-standalone/access.token ]; then cat /data/local/tmp/wukong-standalone/access.token; fi').strip()
    if re.fullmatch('[a-f0-9]{64}',rotated):saved['token']=rotated;token=rotated
    fd=os.open(state_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f:json.dump(saved,f)
    directory = '/data/local/tmp/wukong-standalone'
    device.cmd('shell', f'mkdir -p {directory}; chmod 700 {directory}')
    device.cmd('shell',f'if [ -f {directory}/runtime.jar ]; then chmod 600 {directory}/runtime.jar; fi')
    device.cmd('push',str(jar),directory+'/runtime.jar')
    device.cmd('shell',f'chmod 444 {directory}/runtime.jar')
    # UiAutomation is exclusive. Release only Wukong's own idle legacy engine;
    # never kill another app/automation service. App tasks must be idle beforehand.
    stop = r'''pfile=/data/local/tmp/wukong-shell.pid; if [ -f "$pfile" ]; then p=$(cat "$pfile"); case "$p" in ''|*[!0-9]*) exit 1;; esac; if [ -r /proc/$p/cmdline ]; then case $(tr '\000' ' ' </proc/$p/cmdline) in *com.wukong.shell.Main*) kill "$p";; esac; fi; fi'''
    device.cmd('shell','sh -c '+shlex.quote(stop))
    # Never put the activation credential in a shell argument.
    import subprocess
    command=[device.adb,'-s',device.serial,'shell',f'umask 077; cat > {directory}/activation']
    subprocess.run(command,input=token.encode(),capture_output=True,check=True,timeout=20)
    launch=f'CLASSPATH={directory}/runtime.jar nohup app_process /system/bin com.wukong.phone.StandaloneMain {directory}/activation {directory}/tasks >{directory}/runtime.log 2>&1 </dev/null &'
    device.cmd('shell','sh -c '+shlex.quote(launch))
    device.token=token
    device.port=int(device.cmd('forward','tcp:0','tcp:39744'))
    for _ in range(60):
        try:
            if device.json('GET','/v1/status').get('plannerLocation')=='phone':
                device.mode='standalone';return
        except error_type:
            time.sleep(.15)
    device.close()
    raise error_type('Standalone runtime did not start. Inspect its private runtime.log on the phone.')
