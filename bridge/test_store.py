import tempfile
import unittest
import base64
import hashlib
from pathlib import Path
from store import Bridge

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.b = Bridge(str(Path(self.tmp.name) / 'test.db'))
        self.token = 'a' * 40
    def tearDown(self):
        self.b.db.close(); self.tmp.cleanup()
    def test_handoff_revocation_and_retry(self):
        b = self.b
        b.switch(1, 'external', 'agent-a', self.token)
        b.snapshot(1, {'records':[]})
        b.enqueue('s1', {'text':'input'}, 1)
        with self.assertRaises(PermissionError): b.propose(1, 'p1', 1, {})
        self.assertEqual('s1', b.read_handoff(1)['pending'][0]['id'])
        b.accept(1,1)
        self.assertEqual('pending', b.propose(1,'p1',1,{'text':'answer'}))
        self.assertEqual('pending', b.propose(1,'p1',1,{'text':'answer'}))
        with self.assertRaises(ValueError): b.propose(1,'p1',1,{'text':'different'})
        b.switch(2,'external','agent-b','b'*40)
        with self.assertRaises(PermissionError): b.authenticate(self.token)
        with self.assertRaises(PermissionError): b.propose(1,'late',1,{})
        self.assertEqual('s1',b.read_handoff(2)['pending'][0]['id'])
        self.assertEqual('revoked', b.db.execute("SELECT status FROM proposals WHERE id='p1'").fetchone()[0])
    def test_stale_snapshot_and_input_conflict(self):
        b=self.b; b.switch(1,'external','agent',self.token); b.snapshot(1,{'v':1}); b.read_handoff(1)
        b.snapshot(1,{'v':2})
        with self.assertRaises(ValueError): b.accept(1,1)
        b.accept(1,2)
        b.enqueue('s',{'text':'a'},1); b.enqueue('s',{'text':'a'},1)
        with self.assertRaises(ValueError): b.enqueue('s',{'text':'b'},1)
        with self.assertRaises(ValueError): b.propose(1,'p',1,{})
        b.switch(2,'builtin')
        with self.assertRaises(PermissionError): b.propose(1,'p',2,{})
    def test_phone_completion_survives_builtin_handoff(self):
        b=self.b; b.switch(1,"external","a",self.token); b.snapshot(1,{"v":1}); b.enqueue("done",{"text":"x"},1)
        b.switch(2,"builtin"); b.switch(2,"builtin",completed_ids=["done"])
        b.switch(3,"external","b","b"*40,completed_ids=["done"])
        self.assertEqual([],b.read_handoff(3)["pending"])

    def test_images_are_on_demand_and_cleared_at_handoff(self):
        b=self.b; b.switch(1,'external','agent',self.token); b.snapshot(1,{'v':1}); b.accept(1,1)
        b.enqueue('s',{'annotations':[{'inputParts':[{'type':'image','attachmentId':'photo'}]}]},1)
        with self.assertRaises(ValueError): b.request_image(1,'other')
        self.assertEqual('requested',b.request_image(1,'photo'))
        self.assertEqual(['photo'],b.requested_images(1))
        raw=b'fixture image'; encoded=base64.b64encode(raw).decode()
        b.upload_image(1,'photo','image/jpeg',hashlib.sha256(raw).hexdigest(),encoded)
        self.assertEqual(('image/jpeg',encoded),b.read_image(1,'photo'))
        b.switch(2,'external','next','b'*40)
        self.assertEqual({},b.image_data)

if __name__ == '__main__': unittest.main()
