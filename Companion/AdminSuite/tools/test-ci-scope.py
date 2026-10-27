"""Small contract checks for CI test selection; no Java/server setup required."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('scope', Path(__file__).with_name('ci-test-scope.py'))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ScopeTests(unittest.TestCase):
    def test_isolated_features_keep_app_baseline(self):
        for name, suite in [('DatabaseInspector.java', 'database'), ('SecurityLogOutbox.java', 'outbox')]:
            with self.subTest(name=name):
                self.assertEqual(scope.select([scope.PREFIX + 'src/main/java/eu/simplexsmp/adminsuite/' + name]),
                                 ['security', 'api', suite])

    def test_shared_and_unknown_changes_run_all(self):
        for path in ['src/main/java/eu/simplexsmp/adminsuite/SimplexAdminSuite.java',
                     'tools/tests/Moderation790Harness.java', 'src/main/resources/config.yml',
                     'tools/build-dependencies.json', 'tools/test-830.py']:
            with self.subTest(path=path):
                self.assertEqual(scope.select([scope.PREFIX + path]), scope.ALL)
        self.assertEqual(scope.select(['.github/workflows/admin-suite.yml']), scope.ALL)

    def test_multiple_features_are_combined(self):
        self.assertEqual(scope.select([scope.PREFIX + 'tools/tests/Database850Harness.java',
                                       scope.PREFIX + 'tools/tests/Outbox840Harness.java']),
                         ['security', 'api', 'outbox', 'database'])

    def test_isolated_rules_refer_to_existing_files(self):
        source = Path(__file__).resolve().parents[1] / 'src/main/java'
        for name in scope.ISOLATED:
            self.assertEqual(len(list(source.rglob(name))), 1, name)


if __name__ == '__main__':
    unittest.main()
