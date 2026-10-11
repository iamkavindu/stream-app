import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('reports', Path(__file__).parents[1] / 'summarize-surefire.py')
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class SurefireSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def write(self, name, xml):
        (self.directory / name).write_text(xml, encoding='utf-8')

    def test_aggregates_successful_suites(self):
        for name, count in [('A', 3), ('B', 2)]:
            self.write(f'TEST-{name}.xml', f'<testsuite tests="{count}" failures="0" errors="0" skipped="0"/>')
        report, passed = reports.summarize(self.directory)
        self.assertTrue(passed)
        self.assertIn('| 5 | 0 | 0 | 0 |', report)
        self.assertIn('excludes `slow` and `pipeline`', report)

    def test_rejects_skips_and_lists_test_identity(self):
        self.write('TEST-A.xml', '<testsuite tests="1" skipped="1"><testcase classname="TranscoderTest" name="invalidMedia"><skipped/></testcase></testsuite>')
        report, passed = reports.summarize(self.directory)
        self.assertFalse(passed)
        self.assertIn('TranscoderTest.invalidMedia', report)

    def test_rejects_failures_and_errors(self):
        for attribute in ('failures', 'errors'):
            with self.subTest(attribute=attribute):
                self.write('TEST-A.xml', f'<testsuite tests="1" {attribute}="1"/>')
                self.assertFalse(reports.summarize(self.directory)[1])

    def test_rejects_missing_empty_and_malformed_reports(self):
        self.assertFalse(reports.summarize(self.directory)[1])
        for xml in ('<testsuite tests="0"/>', '<broken', '<testsuites/>', '<testsuite tests="-1"/>'):
            with self.subTest(xml=xml):
                self.write('TEST-A.xml', xml)
                self.assertFalse(reports.summarize(self.directory)[1])


if __name__ == '__main__':
    unittest.main()
