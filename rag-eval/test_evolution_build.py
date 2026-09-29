import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from evolution_build import source_snapshot


class BuildSnapshotTest(unittest.TestCase):
    def test_snapshot_excludes_local_profiles_and_checks_cached_wrapper_before_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = root / 'stocksage-backend'
            wrapper = b'fictional wrapper bytes; never executed'
            files = {'pom.xml': b'<project/>', 'src/main/java/Example.java': b'class Example {}',
                     'src/main/resources/application-local.properties': b'unit-test-only-secret',
                     'src/main/resources/.env': b'unit-test-only-secret',
                     '.mvn/wrapper/maven-wrapper.properties': ('wrapperSha256Sum=' + hashlib.sha256(wrapper).hexdigest()).encode(),
                     '.mvn/wrapper/maven-wrapper.jar': wrapper,
                     'target/ignored.txt': b'not source'}
            for name, content in files.items():
                path = backend / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            names = '\0'.join('stocksage-backend/' + name for name in files if not name.endswith('.jar')).encode()
            with patch('evolution_build.subprocess.check_output', side_effect=['a' * 40, names]):
                revision, snapshot = source_snapshot(root)
            self.assertEqual(revision, 'a' * 40)
            self.assertEqual(set(snapshot), {'pom.xml', 'src/main/java/Example.java',
                                            '.mvn/wrapper/maven-wrapper.properties', '.mvn/wrapper/maven-wrapper.jar'})
            self.assertNotIn(b'unit-test-only-secret', snapshot.values())
            (backend / '.mvn/wrapper/maven-wrapper.jar').write_bytes(b'changed wrapper')
            with patch('evolution_build.subprocess.check_output', side_effect=['a' * 40, names]):
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    source_snapshot(root)


if __name__ == '__main__':
    unittest.main()
