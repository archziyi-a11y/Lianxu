"""Create a new private sample configuration without printing credentials."""
import os
from pathlib import Path
import secrets
import sys

if len(sys.argv) != 4:
    raise SystemExit('Usage: init_config.py /absolute/config.env /absolute/new-sample.sqlite3 port')
config = Path(sys.argv[1]); database = Path(sys.argv[2]); port = int(sys.argv[3])
if not config.is_absolute() or not database.is_absolute() or database.exists() or not 1 <= port <= 65535:
    raise SystemExit('Use absolute paths, a new database and a valid port')
if any(ch in str(database) for ch in '\r\n'):
    raise SystemExit('Invalid database path')
owner = secrets.token_urlsafe(48)
content = f'LIANXU_BRIDGE_PORT={port}\nLIANXU_BRIDGE_DB={database}\nLIANXU_OWNER_TOKEN={owner}\n'
fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as file:
        file.write(content)
except BaseException:
    config.unlink(missing_ok=True)
    raise
print('New private sample configuration created.')
