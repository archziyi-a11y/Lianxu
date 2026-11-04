"""Prepare/apply/restore one Caddyfile route with hash checks; no credentials."""
import hashlib
import os
from pathlib import Path
import sys

ANCHOR = b"    handle /sales/materials/images {"
MARKER = b"handle_path /lianxu-phone/*"

def digest(data):
    return hashlib.sha256(data).hexdigest()

def open_checked(path, expected):
    f = open(path, "r+b")
    before = f.read()
    if digest(before) != expected:
        f.close()
        raise ValueError("Caddyfile hash changed; stop and re-audit")
    return f

def prepare(source, snippet, candidate, expected):
    original = Path(source).read_bytes()
    if digest(original) != expected or original.count(ANCHOR) != 1 or MARKER in original:
        raise ValueError("Unexpected Caddyfile; stop and re-audit")
    addition = Path(snippet).read_bytes()
    if addition.count(MARKER) != 1:
        raise ValueError("Unexpected snippet")
    result = original.replace(ANCHOR, addition + ANCHOR, 1)
    fd = os.open(candidate, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(result)
    print("candidate sha256=" + digest(result))

def apply(source, candidate, expected, expected_candidate):
    data = Path(candidate).read_bytes()
    if digest(data) != expected_candidate or data.count(MARKER) != 1:
        raise ValueError("Candidate route missing or duplicated")
    with open_checked(source, expected) as f:
        f.seek(0); f.write(data); f.truncate(); f.flush(); os.fsync(f.fileno())
    print("applied sha256=" + digest(data))

def restore(source, backup, expected):
    data = Path(backup).read_bytes()
    with open_checked(source, expected) as f:
        f.seek(0); f.write(data); f.truncate(); f.flush(); os.fsync(f.fileno())
    print("restored sha256=" + digest(data))

if __name__ == "__main__":
    if len(sys.argv) < 2: raise SystemExit("usage: prepare|apply|restore ...")
    mode,*args=sys.argv[1:]
    if mode == "prepare" and len(args) == 4: prepare(*args)
    elif mode == "apply" and len(args) == 4: apply(*args)
    elif mode == "restore" and len(args) == 3: restore(*args)
    else: raise SystemExit("invalid arguments")
