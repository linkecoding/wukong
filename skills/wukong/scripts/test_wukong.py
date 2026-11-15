"""Host protocol boundary tests; no device or provider calls."""
import http.client
import importlib.util
import json
from pathlib import Path
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('wukong', Path(__file__).with_name('wukong.py'))
wukong = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wukong)

class FakePhone:
    def __init__(self):
        self.calls = []
    def request(self, method, path, body):
        self.calls.append((method, path, body))
        return b'{"configured":true}', 'application/json'

class CompanionWebTest(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone()
        self.server = wukong.LocalServer(('127.0.0.1', 0), wukong.make_handler(self.phone, 'private-browser-token'))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
    def request(self, method, path, headers=None, body=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=2)
        try:
            conn.request(method, path, body, headers or {})
            reply = conn.getresponse()
            return reply.status, reply.read(), dict(reply.getheaders())
        finally:
            conn.close()
    def test_page_contains_no_credentials_and_has_no_external_assets(self):
        code, data, headers = self.request('GET', '/')
        self.assertEqual(200, code)
        self.assertNotIn(b'private-browser-token', data)
        self.assertIn("frame-ancestors 'none'", headers['Content-Security-Policy'])
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual([], self.phone.calls)
    def test_authorization_origin_and_route_allowlist(self):
        self.assertEqual(401, self.request('GET', '/v1/model')[0])
        auth = {'Authorization': 'Bearer private-browser-token'}
        self.assertEqual(403, self.request('GET', '/v1/model', {**auth, 'Origin': 'https://evil.example'})[0])
        self.assertEqual(403, self.request('GET', '/v1/model', {**auth, 'Host': 'evil.example'})[0])
        self.assertEqual(404, self.request('GET', '/v1/shell', auth)[0])
        self.assertEqual([], self.phone.calls)
        self.assertEqual(200, self.request('GET', '/v1/model', auth)[0])
        self.assertEqual([('GET', '/v1/model', None)], self.phone.calls)
    def test_json_validation_and_no_arbitrary_proxy(self):
        headers = {'Authorization': 'Bearer private-browser-token', 'Content-Type': 'application/json'}
        for body in ('[]', 'broken', '"text"'):
            self.assertEqual(400, self.request('POST', '/v1/model', headers, body)[0])
        self.assertEqual(400, self.request('POST', '/v1/model', {**headers, 'Content-Type': 'text/plain'}, '{}')[0])
        self.assertEqual(404, self.request('POST', '/v1/tasks/../shell', headers, '{}')[0])
        self.assertEqual([], self.phone.calls)
        self.assertEqual(200, self.request('POST', '/v1/model', headers, '{"key":"entered-on-page"}')[0])
        self.assertEqual({'key': 'entered-on-page'}, self.phone.calls[0][2])
    def test_only_task_scoped_controls_are_exposed(self):
        task = '/v1/tasks/67d9fbe4-8ac2-4b1d-8b04-7ac3323d3672'
        for action in ('stop', 'takeover', 'resume'):
            self.assertTrue(wukong.route_allowed('POST', task + '/' + action))
        for action in ('approve', 'shell', 'input', 'tap'):
            self.assertFalse(wukong.route_allowed('POST', task + '/' + action))
        self.assertFalse(wukong.route_allowed('GET', '/v1/tasks'))
    def test_background_reconnect_closes_a_stale_phone_bridge(self):
        def disconnected(*_):
            raise wukong.Error('Device credential revoked')
        self.phone.request = disconnected
        previous = {'port': self.server.server_port, 'token': 'private-browser-token', 'serial': 'phone1'}
        child = SimpleNamespace(pid=12345, poll=lambda: None)
        args = SimpleNamespace(adb='adb', serial='phone1', no_browser=True, port=0)
        with patch.object(wukong, 'state', side_effect=[previous, {'pid': 12345, 'port': 34567, 'serial': 'phone1'}]), patch.object(wukong.subprocess, 'Popen', return_value=child) as spawn, patch('builtins.print'):
            wukong.background(args)
            spawn.assert_called_once()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())
    def test_background_cannot_silently_switch_a_healthy_device(self):
        previous = {'port': self.server.server_port, 'token': 'private-browser-token', 'serial': 'phone1'}
        args = SimpleNamespace(adb='adb', serial='phone2', no_browser=True, port=0)
        with patch.object(wukong, 'state', return_value=previous), patch.object(wukong.subprocess, 'Popen') as spawn:
            with self.assertRaises(wukong.Error):
                wukong.background(args)
            spawn.assert_not_called()

if __name__ == '__main__':
    unittest.main()
