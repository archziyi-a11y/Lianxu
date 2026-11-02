"""Load a private environment file without exposing its contents in process arguments."""
import os
from pathlib import Path
import sys

ALLOWED = {'LIANXU_OWNER_TOKEN', 'LIANXU_BRIDGE_PORT', 'LIANXU_BRIDGE_DB',
           'LIANXU_GROK_WEBHOOK_URL', 'LIANXU_GROK_WEBHOOK_TOKEN'}

def read_config(path):
    path = Path(path)
    if os.name != 'nt' and path.stat().st_mode & 0o077:
        raise ValueError('Configuration file must not be accessible to group or others')
    values = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or key not in ALLOWED or key in values or not value:
            raise ValueError('Invalid configuration entry')
        values[key] = value
    if len(values.get('LIANXU_OWNER_TOKEN', '')) < 32:
        raise ValueError('Owner token must be at least 32 characters')
    if not Path(values.get('LIANXU_BRIDGE_DB', '')).is_absolute():
        raise ValueError('Database path must be absolute')
    port = int(values.get('LIANXU_BRIDGE_PORT', '8765'))
    if not 1 <= port <= 65535:
        raise ValueError('Invalid port')
    return values

def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: launch.py /path/to/config.env')
    values = read_config(sys.argv[1])
    environment = {k: v for k, v in os.environ.items() if k not in ALLOWED}
    environment.update(values)
    server = Path(__file__).resolve().parent.parent / 'server.py'
    os.execve(sys.executable, [sys.executable, str(server)], environment)

if __name__ == '__main__':
    main()
