import base64
import importlib.util
import json
from pathlib import Path
import sys
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent))
import wukong
from transports import WebUsbTransport

class SelectionTest(unittest.TestCase):
    def test_missing_adb_opens_usb_backend_without_install(self):
        args=SimpleNamespace(adb='/missing/adb',serial=None,backend='auto')
        with patch.object(wukong.shutil,'which',return_value=None):
            self.assertIsInstance(wukong.select_backend(args),WebUsbTransport)
    def test_app_is_preferred_before_shell_bootstrap(self):
        device=wukong.Device('adb','fixture')
        def command(*args):
            if args==('get-state',):return 'device'
            if args==('shell','cmd package list packages -e com.wukong.phone'):return 'package:com.wukong.phone'
            if args[0]=='forward':return '12345'
            if 'content call' in args[-1]:return 'Bundle[{token='+('a'*64)+', port=39743}]'
            return ''
        with patch.object(device,'cmd',side_effect=command),patch.object(device,'json',return_value={}),patch.object(wukong,'launch_standalone') as launch:
            device.connect();launch.assert_not_called();self.assertEqual('app',device.mode)
    def test_absent_app_bootstraps_runtime_without_install(self):
        device=wukong.Device('adb','fixture')
        with patch.object(device,'cmd',side_effect=lambda *args:'device' if args==('get-state',) else '' ) as command,patch.object(wukong,'launch_standalone') as launch:
            device.connect();launch.assert_called_once()
            self.assertFalse(any('install' in str(call) for call in command.call_args_list))
    def test_multiple_devices_are_never_silently_selected(self):
        args=SimpleNamespace(adb='adb',serial=None,backend='auto')
        with patch.object(wukong.shutil,'which',return_value='/adb'),patch.object(wukong.Device,'cmd',return_value='List of devices attached\na device\nb device\n'):
            with self.assertRaises(wukong.Error):wukong.select_backend(args)

class BrowserBridgeTest(unittest.TestCase):
    def test_native_request_round_trip_and_session_binding(self):
        bridge=WebUsbTransport(wukong.Error,'fixture-phone')
        session=bridge.connected({'serial':'fixture-phone','kind':'standalone'})['session']
        result=[]
        thread=threading.Thread(target=lambda:result.append(bridge.request('GET','/v1/status')))
        thread.start();request=bridge.poll(session)
        self.assertEqual('/v1/status',request['path'])
        bridge.complete({'session':session,'id':request['id'],'status':200,'contentType':'application/json','data':base64.b64encode(b'{"plannerLocation":"phone"}').decode()})
        thread.join(2);self.assertFalse(thread.is_alive())
        self.assertEqual('phone',json.loads(result[0][0])['plannerLocation'])
        with self.assertRaises(wukong.Error):bridge.poll('wrong-session')
        with self.assertRaises(wukong.Error):bridge.connected({'serial':'different-phone','kind':'standalone'})
    def test_disconnect_unblocks_pending_without_replaying(self):
        bridge=WebUsbTransport(wukong.Error);session=bridge.connected({'serial':'fixture-phone','kind':'standalone'})['session']
        errors=[]
        def wait():
            try:bridge.request('POST','/v1/tasks',{'id':'original'})
            except wukong.Error as error:errors.append(str(error))
        thread=threading.Thread(target=wait);thread.start();bridge.poll(session);bridge.disconnected({'session':session});thread.join(2)
        self.assertFalse(thread.is_alive());self.assertEqual(1,len(errors));self.assertIn('original request ID',errors[0]);self.assertTrue(bridge.queue.empty())

if __name__=='__main__':unittest.main()
