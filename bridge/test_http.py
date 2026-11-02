import os
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from starlette.testclient import TestClient

folder = tempfile.TemporaryDirectory()
os.environ['LIANXU_OWNER_TOKEN'] = 'o' * 40
os.environ['LIANXU_BRIDGE_DB'] = str(Path(folder.name) / 'http.db')
import server

class HttpTests(unittest.TestCase):
    def test_mcp_handshake_and_tool_calls(self):
        owner = {'Authorization':'Bearer ' + 'o' * 40}
        agent = {'Authorization':'Bearer ' + 'a' * 40, 'Accept':'application/json, text/event-stream'}
        with TestClient(server.app, base_url='http://127.0.0.1:8765') as c:
            self.assertEqual(401,c.post('/phone/switch',json={}).status_code)
            self.assertEqual(200,c.post('/phone/switch',headers=owner,json={'epoch':1,'mode':'external','agent':'agent','agentToken':'a'*40}).status_code)
            self.assertEqual(200,c.post('/phone/snapshot',headers=owner,json={'epoch':1,'body':{'records':[]}}).status_code)
            class Response:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *args): pass
            server.webhook_url='https://example.invalid/hook'; server.webhook_token='fixture'
            with patch('server.urllib.request.build_opener') as factory:
                send=factory.return_value.open; send.return_value=Response()
                payload={'epoch':1,'id':'fixture-input','body':{'text':'fixture'}}
                self.assertEqual(200,c.post('/phone/enqueue',headers=owner,json=payload).status_code)
                self.assertEqual(200,c.post('/phone/enqueue',headers=owner,json=payload).status_code)
                self.assertEqual(1,send.call_count)
            server.webhook_url='';server.webhook_token=''
            self.assertEqual(401,c.post('/mcp/',json={}).status_code)
            headers = dict(agent, **{'Content-Type':'application/json'})
            init={'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'test','version':'1'}}}
            response=c.post('/mcp/',headers=headers,json=init)
            self.assertEqual(200,response.status_code,response.text)
            self.assertIn('result',response.json())
            headers['MCP-Protocol-Version']='2025-06-18'
            call={'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'read_handoff','arguments':{}}}
            response=c.post('/mcp/',headers=headers,json=call)
            self.assertEqual(200,response.status_code,response.text)
            self.assertFalse(response.json()['result'].get('isError',False),response.text)
            self.assertEqual(200,c.post('/phone/switch',headers=owner,json={'epoch':2,'mode':'builtin'}).status_code)
            self.assertEqual(401,c.post('/mcp/',headers=headers,json=call).status_code)

def tearDownModule():
    server.bridge.db.close()
    folder.cleanup()

if __name__ == '__main__': unittest.main()
