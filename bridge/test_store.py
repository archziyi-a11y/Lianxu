import tempfile
import unittest
import base64
import hashlib
import sqlite3
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

    def test_explicit_same_writer_resume_preserves_pending_and_rejects_reuse(self):
        b=self.b; b.switch(2,'external','agent',self.token); b.snapshot(2,{'v':1}); b.accept(2,1)
        b.enqueue('input',{'annotations':[{'inputParts':[{'type':'image','attachmentId':'photo'}]}]},2)
        b.request_image(2,'photo')
        raw=b'isolated photo'; encoded=base64.b64encode(raw).decode()
        b.upload_image(2,'photo','image/jpeg',hashlib.sha256(raw).hexdigest(),encoded)
        b.mark_webhook_sent('2:input'); b.mark_webhook_sent('2:image:photo')
        b.propose(2,'pending',1,{'inputIds':['input']})
        b.propose(2,'terminal',1,{'inputIds':[]}); b.acknowledge(2,'terminal','applied')
        with self.assertRaises(ValueError): b.switch(7,'external','agent',self.token)
        with self.assertRaises(ValueError): b.switch(7,'external','other',self.token,resume_active=True)
        b.db.execute("CREATE TRIGGER fail_resume BEFORE UPDATE OF epoch ON proposals BEGIN SELECT RAISE(ABORT,'injected'); END")
        with self.assertRaises(sqlite3.IntegrityError): b.switch(7,'external','agent',self.token,resume_active=True)
        self.assertEqual(2,b.authenticate(self.token)); self.assertEqual(2,b.image_requests['photo'])
        self.assertFalse(b.webhook_sent('7:input'))
        b.db.execute('DROP TRIGGER fail_resume')
        b.switch(7,'external','agent',self.token,resume_active=True)
        self.assertEqual(7,b.authenticate(self.token))
        self.assertEqual(1,b.db.execute('SELECT accepted FROM control').fetchone()[0])
        self.assertEqual('input',b.pending(7)[0]['id'])
        self.assertEqual('pending',b.proposal_status(7,'pending'))
        self.assertEqual((2,'applied'),tuple(b.db.execute("SELECT epoch,status FROM proposals WHERE id='terminal'").fetchone()))
        self.assertEqual(('image/jpeg',encoded),b.read_image(7,'photo'))
        self.assertTrue(b.webhook_sent('7:input')); self.assertTrue(b.webhook_sent('7:image:photo'))
        b.switch(7,'external','agent',self.token,resume_active=True)
        b.switch(8,'builtin')
        with self.assertRaises(ValueError): b.switch(9,'external','agent',self.token,resume_active=True)
        with self.assertRaises(ValueError): b.switch(9,'external','agent',self.token)
        b.switch(9,'external','new-agent','b'*40)
        with self.assertRaises(PermissionError): b.authenticate(self.token)
        with self.assertRaises(ValueError): b.switch(10,'external','agent',self.token,resume_active=True)

    def test_resume_accepts_lost_ack_for_completed_input_without_moving_history(self):
        b=self.b;b.switch(2,'external','agent',self.token);b.snapshot(2,{'v':1});b.accept(2,1)
        b.enqueue('done',{'text':'completed on phone'},2);b.enqueue('other',{'text':'still pending'},2)
        b.propose(2,'lost-ack',1,{'inputIds':['done']})
        b.switch(7,'external','agent',self.token,completed_ids=['done'],resume_active=True)
        b.acknowledge(7,'lost-ack','applied')
        self.assertEqual((2,'completed'),tuple(b.db.execute("SELECT epoch,status FROM inputs WHERE id='done'").fetchone()))
        self.assertEqual(['other'],[x['id'] for x in b.pending(7)])
        self.assertEqual('applied',b.proposal_status(7,'lost-ack'))
        b.db.execute("UPDATE inputs SET epoch=2 WHERE id='other'")
        b.propose(7,'bad-old-pending',1,{'inputIds':['other']})
        with self.assertRaises(ValueError): b.acknowledge(7,'bad-old-pending','applied')
        self.assertEqual('pending',b.db.execute("SELECT status FROM inputs WHERE id='other'").fetchone()[0])

    def test_resume_flag_allows_unseen_token_as_strict_new_handoff(self):
        b=self.b
        b.switch(1,'external','old',self.token);b.snapshot(1,{'v':1});b.accept(1,1)
        b.enqueue('input',{'text':'pending'},1);b.propose(1,'old-pending',1,{'inputIds':['input']})
        b.switch(2,'external','new','b'*40,resume_active=True)
        self.assertEqual(0,b.db.execute('SELECT accepted FROM control').fetchone()[0])
        self.assertEqual('input',b.read_handoff(2)['pending'][0]['id'])
        self.assertEqual('revoked',b.db.execute("SELECT status FROM proposals WHERE id='old-pending'").fetchone()[0])
        with self.assertRaises(PermissionError): b.authenticate(self.token)
        b.switch(3,'builtin')
        b.switch(4,'external','third','c'*40,resume_active=True)
        self.assertEqual(0,b.db.execute('SELECT accepted FROM control').fetchone()[0])

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
