"""scrcpy server streaming/control via a local ADB forward. No model inference here."""
import base64
import json
import secrets
import shlex
import socket
import struct
import subprocess
import threading
import time


class ScrcpySession:
    def __init__(self, device, assets, error_type, display_id):
        self.device=device;self.Error=error_type;self.display_id=display_id
        self.video=None;self.control=None;self.process=None;self.port=None
        self.control_lock=threading.RLock();self.streaming=False;self.closed=False
        self.settings=json.loads((assets/'runtime/scrcpy.json').read_text())
        jar=assets/'runtime/scrcpy-server.jar'
        import hashlib
        if hashlib.sha256(jar.read_bytes()).hexdigest()!=self.settings['sha256']:raise error_type('scrcpy checksum mismatch')
        name=secrets.randbelow(0x7fffffff);self.scid=f'{name:08x}'
        target=f'/data/local/tmp/wukong-scrcpy-{self.settings["version"]}.jar'
        device.cmd('push',str(jar),target)
        self.port=int(device.cmd('forward','tcp:0','localabstract:scrcpy_'+self.scid))
        options=['scid='+self.scid,'log_level=warn','audio=false','video=true','control=true','tunnel_forward=true',
                 'video_codec=h264','video_bit_rate=4000000','max_fps=30','max_size=1600','send_device_meta=false',
                 'send_codec_meta=true','send_frame_meta=true','send_dummy_byte=true','clipboard_autosync=false','power_on=false',
                 'stay_awake=false','display_id='+str(display_id)]
        command=f'CLASSPATH={target} app_process / com.genymobile.scrcpy.Server {self.settings["version"]} '+shlex.join(options)
        self.process=subprocess.Popen([device.adb,'-s',device.serial,'shell',command],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        self.output=[]
        def drain():
            for line in self.process.stdout:
                self.output.append(line.decode(errors='replace').strip());self.output=self.output[-12:]
        threading.Thread(target=drain,daemon=True).start()
        try:
            end=time.monotonic()+12
            while time.monotonic()<end:
                try:
                    self.video=socket.create_connection(('127.0.0.1',self.port),timeout=.4)
                    self.video.settimeout(.5)
                    if self.video.recv(1)==b'\0':break
                    self.video.close();self.video=None
                except OSError:
                    if self.video:self.video.close();self.video=None
                    time.sleep(.1)
            if not self.video:raise error_type('scrcpy could not start: '+' '.join(self.output)[-1000:])
            self.control=socket.create_connection(('127.0.0.1',self.port),timeout=4)
            self.video.settimeout(10)
            self.metadata=self.read_exact(12)
            codec,self.width,self.height=struct.unpack('>III',self.metadata)
            if codec!=0x68323634 or not 0<self.width<=16384 or not 0<self.height<=16384:raise error_type('Invalid H.264 stream metadata')
            self.video.settimeout(1)
            def drain_control():
                try:
                    while not self.closed and self.control.recv(65536):pass
                except OSError:pass
            threading.Thread(target=drain_control,daemon=True).start()
        except Exception:
            self.close();raise

    def read_exact(self,n):
        data=b''
        while len(data)<n:
            value=self.video.recv(n-len(data))
            if not value:raise self.Error('scrcpy stream ended')
            data+=value
        return data

    def input(self,body):
        with self.control_lock:
            kind=body['kind']
            if kind in ('home','back'):
                key=3 if kind=='home' else 4
                for action in (0,1):self.control.sendall(struct.pack('>BBIII',0,action,key,0,0))
            elif kind=='text':
                text=body['text'].encode('utf-8')
                if len(text)>8000:raise self.Error('Text too long')
                self.control.sendall(b'\1'+struct.pack('>I',len(text))+text)
            elif kind=='gesture':
                w,h=int(body['width']),int(body['height'])
                if not 0<w<=16384 or not 0<h<=16384:raise self.Error('Invalid video size')
                coords=[int(body[k]) for k in ('x','y','endX','endY')]
                if not all(0<=v<=1000 for v in coords):raise self.Error('Invalid coordinates')
                x,y,ex,ey=coords;duration=max(60,min(2000,int(body.get('duration',60))))
                def touch(action,x,y):self.control.sendall(struct.pack('>BBQIIHHHII',2,action,0xfffffffffffffffe,round(x*(w-1)/1000),round(y*(h-1)/1000),w,h,0 if action==1 else 65535,0,0))
                touch(0,x,y)
                try:
                    steps=max(1,duration//25)
                    for n in range(1,steps+1):
                        if self.closed:raise self.Error('Video closed')
                        time.sleep(duration/steps/1000);touch(2,x+(ex-x)*n/steps,y+(ey-y)*n/steps)
                    touch(1,ex,ey)
                except Exception:
                    if not self.closed:touch(3,ex,ey)
                    raise
            else:raise self.Error('Unsupported manual input')

    def close(self):
        self.closed=True
        for sock in (self.video,self.control):
            if sock:
                try:sock.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                sock.close()
        if self.process:
            self.process.terminate()
            try:self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:self.process.kill()
        if self.port:
            try:self.device.cmd('forward','--remove','tcp:'+str(self.port))
            except self.Error:pass
