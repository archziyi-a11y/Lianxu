"""Lianxu local bridge: explicit single-writer epoch, durable input and proposals.

Run only on loopback or behind an authenticated TLS reverse proxy. This process never
reads Android files directly. Phone snapshots and agent proposals are opaque to the
bridge; Android must validate each proposal against its current local sources.
"""
import hashlib
import hmac
import json
import os
import sqlite3
import threading
from pathlib import Path

class Bridge:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS control (id INTEGER PRIMARY KEY CHECK(id=1), epoch INTEGER NOT NULL, mode TEXT NOT NULL, agent TEXT, token_hash TEXT, accepted INTEGER NOT NULL);
        INSERT OR IGNORE INTO control VALUES (1,0,'builtin',NULL,NULL,0);
        CREATE TABLE IF NOT EXISTS snapshots (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS inputs (id TEXT PRIMARY KEY, body TEXT NOT NULL, epoch INTEGER NOT NULL, status TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS proposals (id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, base_revision INTEGER NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS used_tokens (hash TEXT PRIMARY KEY);
        ''')
    @staticmethod
    def digest(token):
        return hashlib.sha256(token.encode()).hexdigest()
    def switch(self, epoch, mode, agent=None, token=None):
        if mode not in ('builtin', 'external') or mode == 'external' and (not agent or not token or len(token) < 32):
            raise ValueError('invalid control')
        with self.lock, self.db:
            self.db.execute('BEGIN IMMEDIATE')
            current = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
            digest = self.digest(token) if token else None
            if epoch == current['epoch'] and mode == current['mode'] and agent == current['agent'] and digest == current['token_hash']: return
            if epoch <= current['epoch']: raise ValueError('stale epoch')
            if digest and self.db.execute('SELECT 1 FROM used_tokens WHERE hash=?', (digest,)).fetchone(): raise ValueError('token already used')
            if digest: self.db.execute('INSERT INTO used_tokens VALUES (?)', (digest,))
            self.db.execute('UPDATE control SET epoch=?,mode=?,agent=?,token_hash=?,accepted=0 WHERE id=1', (epoch,mode,agent,self.digest(token) if token else None))
            self.db.execute("UPDATE inputs SET epoch=? WHERE status='pending'", (epoch,))
            self.db.execute("UPDATE proposals SET status='revoked' WHERE status='pending'")
    def authenticate(self, token):
        with self.lock:
            row = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
            if row['mode'] != 'external' or not row['token_hash'] or not hmac.compare_digest(row['token_hash'], self.digest(token)):
                raise PermissionError('revoked')
            return row['epoch']
    def _writer(self, epoch, accepted=True):
        row = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
        if row['mode'] != 'external' or row['epoch'] != epoch or accepted and not row['accepted']:
            raise PermissionError('revoked or handoff incomplete')
    def snapshot(self, epoch, body):
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        def basis(value):
            copy = json.loads(json.dumps(value))
            for board in copy.get('boards', []): board.pop('draft', None)
            return copy
        with self.lock, self.db:
            control = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
            if control['epoch'] != epoch or control['mode'] != 'external': raise ValueError('stale epoch')
            old = self.db.execute('SELECT * FROM snapshots WHERE id=1').fetchone()
            if old and old['body'] == encoded: return old['revision']
            revision = old['revision'] if old and basis(json.loads(old['body'])) == basis(body) else old['revision'] + 1 if old else 1
            self.db.execute('INSERT OR REPLACE INTO snapshots VALUES (1,?,?)', (revision,encoded))
            return revision
    def read_handoff(self, epoch):
        with self.lock:
            self._writer(epoch, False)
            row = self.db.execute('SELECT * FROM snapshots WHERE id=1').fetchone()
            if not row: raise ValueError('no snapshot')
            return {'revision': row['revision'], 'snapshot':json.loads(row['body']), 'pending':self.pending(epoch)}
    def accept(self, epoch, revision):
        with self.lock, self.db:
            self._writer(epoch, False)
            row = self.db.execute('SELECT revision FROM snapshots WHERE id=1').fetchone()
            if not row or row['revision'] != revision: raise ValueError('stale handoff')
            self.db.execute('UPDATE control SET accepted=1 WHERE id=1')
    def enqueue(self, id, body, epoch):
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        with self.lock, self.db:
            row = self.db.execute('SELECT epoch FROM control WHERE id=1').fetchone()
            if row['epoch'] != epoch: raise ValueError('stale epoch')
            old = self.db.execute('SELECT body FROM inputs WHERE id=?', (id,)).fetchone()
            if old and old['body'] != encoded: raise ValueError('input conflict')
            if not old: self.db.execute("INSERT INTO inputs VALUES (?,?,?,'pending')", (id,encoded,epoch))
    def pending(self, epoch):
        with self.lock:
            self._writer(epoch, False)
            return [{'id':r['id'],'body':json.loads(r['body'])} for r in self.db.execute("SELECT * FROM inputs WHERE status='pending' AND epoch=? ORDER BY rowid", (epoch,))]
    def propose(self, epoch, id, base_revision, body):
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        with self.lock, self.db:
            self._writer(epoch)
            snapshot = self.db.execute('SELECT revision FROM snapshots WHERE id=1').fetchone()
            if not snapshot or snapshot['revision'] != base_revision: raise ValueError('stale snapshot')
            old = self.db.execute('SELECT * FROM proposals WHERE id=?', (id,)).fetchone()
            if old:
                if old['body'] != encoded or old['epoch'] != epoch or old['base_revision'] != base_revision: raise ValueError('proposal conflict')
                return old['status']
            self.db.execute("INSERT INTO proposals VALUES (?,?,?,?,'pending')", (id,epoch,base_revision,encoded))
            return 'pending'
    def proposals(self, epoch):
        with self.lock:
            control = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
            if control['epoch'] != epoch or control['mode'] != 'external': raise ValueError('stale epoch')
            return [dict(r) for r in self.db.execute("SELECT * FROM proposals WHERE status='pending' AND epoch=? ORDER BY rowid", (epoch,))]
    def acknowledge(self, epoch, id, status):
        if status not in ('applied','rejected'): raise ValueError('invalid status')
        with self.lock, self.db:
            self.db.execute('BEGIN IMMEDIATE')
            row = self.db.execute('SELECT * FROM proposals WHERE id=?', (id,)).fetchone()
            if not row or row['epoch'] != epoch: raise ValueError('unknown proposal')
            control = self.db.execute('SELECT * FROM control WHERE id=1').fetchone()
            if control['epoch'] != epoch or control['mode'] != 'external': raise ValueError('stale epoch')
            if row['status'] != 'pending' and row['status'] != status: raise ValueError('ack conflict')
            if status == 'applied':
                body = json.loads(row['body'])
                input_ids = body.get('inputIds', [])
                if not isinstance(input_ids, list) or not all(isinstance(i, str) for i in input_ids): raise ValueError('invalid inputIds')
                for input_id in input_ids:
                    pending = self.db.execute('SELECT * FROM inputs WHERE id=?', (input_id,)).fetchone()
                    if not pending or pending['epoch'] != epoch: raise ValueError('unknown input')
                    self.db.execute("UPDATE inputs SET status='completed' WHERE id=?", (input_id,))
            self.db.execute('UPDATE proposals SET status=? WHERE id=?', (status,id))
