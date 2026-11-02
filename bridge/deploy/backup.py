"""Create a consistent online SQLite backup without replacing an existing file."""
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import sys

if len(sys.argv) != 3:
    raise SystemExit('Usage: backup.py /absolute/path/source.sqlite3 /absolute/path/new-backup.sqlite3')
source, target = (Path(value).resolve() for value in sys.argv[1:])
if not source.is_file() or source == target or not target.parent.is_dir():
    raise SystemExit('Source must exist and target must be new in an existing directory')
fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
os.close(fd)
try:
    with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)) as src, closing(sqlite3.connect(target)) as dst:
        src.backup(dst)
        if dst.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('Backup integrity check failed')
except BaseException:
    target.unlink(missing_ok=True)
    raise
print('Consistent backup created.')
