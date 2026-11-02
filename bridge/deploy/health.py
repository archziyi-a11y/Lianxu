"""Check unauthorized access and the owner-authenticated control endpoint, without printing secrets."""
import json
import sys
import urllib.error
import urllib.request
from launch import read_config

def check(url, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(url, b'{}', headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

if len(sys.argv) != 3:
    raise SystemExit('Usage: health.py /path/to/config.env https://selected-host')
config = read_config(sys.argv[1])
base = sys.argv[2].rstrip('/')
if not (base.startswith('https://') or base.startswith('http://127.0.0.1:')):
    raise SystemExit('Use HTTPS or a loopback test URL')
unauthenticated = check(base + '/phone/control')
authenticated = check(base + '/phone/control', config['LIANXU_OWNER_TOKEN'])
print('unauthenticated:', unauthenticated, 'authenticated:', authenticated)
if (unauthenticated, authenticated) != (401, 200):
    raise SystemExit(1)
