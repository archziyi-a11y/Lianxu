import importlib.util
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('launch', HERE / 'launch.py')
launch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launch)

class DeploymentTests(unittest.TestCase):
    def test_config_accepts_private_file_and_rejects_unknown_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.env'
            config.write_text('LIANXU_OWNER_TOKEN=' + 'x' * 40 + '\nLIANXU_BRIDGE_DB=' + str(Path(directory) / 'test.db') + '\n', encoding='utf-8')
            if os.name != 'nt': config.chmod(0o600)
            self.assertEqual('x' * 40, launch.read_config(config)['LIANXU_OWNER_TOKEN'])
            config.write_text(config.read_text(encoding='utf-8') + 'UNEXPECTED=value\n', encoding='utf-8')
            with self.assertRaises(ValueError): launch.read_config(config)

    def test_config_generator_is_new_private_and_silent(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'secret.env'
            args = [sys.executable, str(HERE / 'init_config.py'), str(config), str(Path(directory) / 'sample.db'), '18765']
            result = subprocess.run(args, capture_output=True, text=True, check=True)
            values = launch.read_config(config)
            self.assertGreaterEqual(len(values['LIANXU_OWNER_TOKEN']), 32)
            self.assertNotIn(values['LIANXU_OWNER_TOKEN'], result.stdout + result.stderr)
            self.assertNotEqual(0, subprocess.run(args, capture_output=True).returncode)

    def test_backup_captures_wal_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source.sqlite3'
            target = Path(directory) / 'snapshot.sqlite3'
            with closing(sqlite3.connect(source)) as db:
                db.execute('PRAGMA journal_mode=WAL')
                db.execute('CREATE TABLE records (value TEXT)')
                db.execute('INSERT INTO records VALUES (?)', ('isolated sample',))
                db.commit()
                args = [sys.executable, str(HERE / 'backup.py'), str(source), str(target)]
                subprocess.run(args, check=True, capture_output=True)
                with closing(sqlite3.connect(target)) as backup:
                    self.assertEqual('isolated sample', backup.execute('SELECT value FROM records').fetchone()[0])
                self.assertNotEqual(0, subprocess.run(args, capture_output=True).returncode)

if __name__ == '__main__':
    unittest.main()
