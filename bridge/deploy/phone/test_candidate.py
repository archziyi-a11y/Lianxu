import importlib.util
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('phone_caddy', HERE / 'prepare_caddy.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class CandidateTests(unittest.TestCase):
    def test_hash_guard_in_place_apply_and_restore(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source = root/'Caddyfile'; snippet = root/'snippet'; candidate = root/'candidate'; backup = root/'backup'
            original = b'example.invalid {\n    handle /sales/materials/images {\n    }\n}\n'
            source.write_bytes(original); backup.write_bytes(original)
            snippet.write_bytes(b'    handle_path /lianxu-phone/* {\n    }\n\n')
            expected = module.digest(original)
            module.prepare(source, snippet, candidate, expected)
            candidate_hash = module.digest(candidate.read_bytes())
            inode = source.stat().st_ino
            with self.assertRaises(FileExistsError): module.prepare(source, snippet, candidate, expected)
            source.write_bytes(original + b'# concurrent change\n')
            with self.assertRaises(ValueError): module.apply(source, candidate, expected, candidate_hash)
            source.write_bytes(original)
            candidate.write_bytes(candidate.read_bytes()+b'# changed\n')
            with self.assertRaises(ValueError): module.apply(source, candidate, expected, candidate_hash)
            candidate.write_bytes(candidate.read_bytes().removesuffix(b'# changed\n'))
            module.apply(source, candidate, expected, candidate_hash)
            self.assertEqual(inode, source.stat().st_ino)
            self.assertIn(module.MARKER, source.read_bytes())
            source.write_bytes(source.read_bytes()+b'# another change\n')
            with self.assertRaises(ValueError): module.restore(source, backup, candidate_hash)
            source.write_bytes(candidate.read_bytes())
            module.restore(source, backup, candidate_hash)
            self.assertEqual(original, source.read_bytes())

    def test_prepare_refuses_existing_route_and_wrong_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'source'; snippet=root/'snippet'; candidate=root/'candidate'
            source.write_bytes(module.ANCHOR+b'\n'+module.MARKER);snippet.write_bytes(module.MARKER)
            with self.assertRaises(ValueError): module.prepare(source,snippet,candidate,module.digest(source.read_bytes()))
            source.write_bytes(module.ANCHOR)
            with self.assertRaises(ValueError): module.prepare(source,snippet,candidate,'0'*64)
            self.assertFalse(candidate.exists())

if __name__ == '__main__': unittest.main()
