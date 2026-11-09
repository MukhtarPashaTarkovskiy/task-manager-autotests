"""Offline launcher/workflow checks; no Chrome, Maven, HTTP, TestNG suite or DB execution."""
from pathlib import Path
import contextlib
import argparse
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ubuntu_diagnostic', ROOT / 'scripts/run-ui-ubuntu-diagnostic.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def prepared_fake_browser(env, scratch):
    """Actual prepare-browser with a fake installed pair; downloads/real Chrome forbidden."""
    driver = scratch / 'fake driver'
    driver.write_text('''#!/usr/bin/python3 -I
import hashlib, json, os, pathlib, subprocess, sys
if sys.argv[1:] == ['--version']:
    print('ChromeDriver 157.0.8103.38')
    raise SystemExit(0)
if sys.argv[1:] == ['--exit-seven']:
    raise SystemExit(7)
browser = sys.argv[1] == '--browser'
args = sys.argv[2:] if browser else sys.argv[1:]
root = pathlib.Path(args[2])
keys = ('PATH', 'HOME', 'TMPDIR', 'LANG', 'HEADLESS', 'XDG_CACHE_HOME', 'XDG_CONFIG_HOME', 'XDG_RUNTIME_DIR')
blocked = [k for k in os.environ if k.startswith(('TEST_', 'TM_')) or k in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS', 'CLASSPATH')]
data = dict(blockedKeys=sorted(blocked), args=args, systemDigests={k: hashlib.sha256(os.environ[k].encode()).hexdigest() for k in keys if k in os.environ})
(root / ('browser.json' if browser else 'driver.json')).write_text(json.dumps(data))
if not browser:
    subprocess.run(['/usr/bin/python3', '-I', __file__, '--browser', *args], check=True)
''')
    driver.chmod(0o700)
    env = dict(env, RUNNER_TEMP=str(scratch), GITHUB_ENV=str(scratch / 'github-env'))
    original_file, original_access, original_version = Path.is_file, launcher.os.access, launcher.executable_version
    chrome = '/usr/bin/google-chrome'
    with patch.object(launcher, 'require_linux'), \
         patch.object(Path, 'is_file', lambda path: str(path) == chrome or original_file(path)), \
         patch.object(launcher.os, 'access', lambda path, mode: str(path) == chrome or original_access(path, mode)), \
         patch.object(launcher.shutil, 'which', return_value=str(driver)), \
         patch.object(launcher, 'executable_version', lambda path: '157.0.8103.38' if str(path) == chrome else original_version(path)), \
         patch.object(launcher, 'fetch', side_effect=AssertionError('Network forbidden')):
        launcher.prepare_browser(env)
    values = dict(line.split('=', 1) for line in Path(env['GITHUB_ENV']).read_text().splitlines())
    return dict(env, **values), driver


class OfflineChecks(unittest.TestCase):
    def env(self):
        return dict(TEST_BASE_URL=launcher.ORIGIN, HEADLESS='true', TM_RUN_ID='offline-run', RUNNER_TEMP='/tmp',
                    TEST_USER_PASSWORD='FAKE_secret_<&>+pass', TEST_DB_URL='jdbc:postgresql://db.example.test/task_manager_test?sslmode=require',
                    TEST_DB_USERNAME='FAKE_user', TEST_DB_PASSWORD='FAKE_db_password', TEST_ALLOWED_DB_HOST='db.example.test',
                    TEST_ALLOWED_DB_PORT='5432', UI_DIAGNOSTIC_CHROME_PATH='/usr/bin/google-chrome',
                    UI_DIAGNOSTIC_DRIVER_PATH='/tmp/driver', UI_DIAGNOSTIC_CHROME_VERSION='157.0.8103.38',
                    UI_DIAGNOSTIC_DRIVER_VERSION='157.0.8103.38')

    def test_navigation_needs_no_credentials(self):
        env = {k: v for k, v in self.env().items() if k not in launcher.SETTINGS}
        launcher.validate_settings('NavigationOnly', env)
        child = launcher.child_env(self.env(), 'NavigationOnly')
        self.assertTrue(all(k not in child for k in launcher.SETTINGS))
        self.assertEqual(child['TM_SUITE'], 'NavigationOnly')

    def test_build_environment_has_no_test_secrets_or_jvm_overrides(self):
        env = dict(self.env(), JAVA_TOOL_OPTIONS='secret override', MAVEN_ARGS='-X', CLASSPATH='private')
        child = launcher.child_env(env)
        self.assertFalse(any(k.startswith(('TEST_', 'TM_')) or k in launcher.JAVA_OVERRIDES for k in child))

    def test_private_child_output_and_exit_code(self):
        class Process:
            pid = 999999
            def wait(self, **kwargs):
                return 7
        def popen(command, **kwargs):
            self.assertTrue(kwargs['start_new_session'])
            self.assertEqual(kwargs['stderr'], launcher.subprocess.STDOUT)
            kwargs['stdout'].write(b'PRIVATE_CHILD_OUTPUT')
            return Process()
        with patch.object(launcher.subprocess, 'Popen', popen), contextlib.redirect_stdout(io.StringIO()) as console:
            code, private = launcher.execute(['fake-executable'], {}, ROOT)
        self.assertEqual(code, 7)
        self.assertEqual(private, 'PRIVATE_CHILD_OUTPUT')
        self.assertEqual(console.getvalue(), '')

    def test_cancellation_terminates_child_process_group(self):
        class Process:
            pid = 999999
            waits = 0
            def wait(self, **kwargs):
                self.waits += 1
                if self.waits == 1:
                    raise KeyboardInterrupt
                return -15
        with patch.object(launcher.subprocess, 'Popen', return_value=Process()), patch.object(launcher.os, 'killpg') as kill:
            with self.assertRaises(KeyboardInterrupt):
                launcher.execute(['fake-executable'], {}, ROOT)
        kill.assert_called_once_with(999999, launcher.signal.SIGTERM)

    def test_smoke_preserves_test_credentials(self):
        launcher.validate_settings('UiSmoke', self.env())
        self.assertEqual(launcher.child_env(self.env(), 'UiSmoke')['TEST_DB_URL'], self.env()['TEST_DB_URL'])

    def test_prepared_driver_removes_secrets_from_driver_and_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            scratch = Path(temporary)
            env, actual = prepared_fake_browser(self.env(), scratch)
            wrapper = Path(env['UI_DIAGNOSTIC_DRIVER_PATH'])
            self.assertNotEqual(wrapper, actual)
            child = dict(os.environ, **env, TEST_FUTURE_SECRET='SYNTHETIC_future', TM_PRIVATE_SETTING='SYNTHETIC_setting',
                         JAVA_TOOL_OPTIONS='SYNTHETIC_override')
            before = dict(child)
            subprocess.run([str(wrapper), '--probe', 'direct', str(scratch)], env=child, check=True, timeout=10)
            self.assertEqual(child, before)
            for name in ('driver.json', 'browser.json'):
                self.assertEqual(json.loads((scratch / name).read_text())['blockedKeys'], [])
            self.assertEqual(subprocess.run([str(wrapper), '--exit-seven'], env=child, timeout=10).returncode, 7)
            self.assertEqual(launcher.executable_version(wrapper), env['UI_DIAGNOSTIC_DRIVER_VERSION'])

    def test_invalid_targets_are_refused_offline(self):
        for key, value in [('TEST_BASE_URL', 'https://prod.example'), ('TM_RUN_ID', '../old'),
                           ('TEST_DB_URL', 'jdbc:postgresql://db.example.test/task_manager?sslmode=require'),
                           ('TEST_DB_URL', 'jdbc:postgresql://db.example.test/task_manager_test?sslmode=disable'),
                           ('TEST_ALLOWED_DB_PORT', '5433'), ('TEST_ALLOWED_DB_HOST', 'other.example.test'),
                           ('TEST_DB_PASSWORD', '')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                launcher.validate_settings('UiSmoke', dict(self.env(), **{key: value}))
        with self.assertRaises(ValueError):
            launcher.validate_settings('UiRegression', self.env())

    def test_driver_selection_uses_exact_build_and_download_origin(self):
        version = '157.0.8103.80'
        url = f'https://storage.googleapis.com/chrome-for-testing-public/{version}/linux64/chromedriver-linux64.zip'
        doc = {'builds': {'157.0.8103': {'version': version, 'downloads': {'chromedriver': [{'platform': 'linux64', 'url': url}]}}}}
        self.assertEqual(launcher.driver_download(doc, '157.0.8103.38'), (version, url))
        doc['builds']['157.0.8103']['downloads']['chromedriver'][0]['url'] = 'https://unapproved.example/driver.zip'
        with self.assertRaises(ValueError):
            launcher.driver_download(doc, '157.0.8103.38')

    def test_only_sanitized_diagnostics_leave_private_console(self):
        raw = '\n'.join(['WebDriver secret raw https://private.example/token', 'UI_NAVIGATION',
                         'expected=' + launcher.ORIGIN + '/login', 'urlAfterWait=[DATA_EMPTY]',
                         'classification=DATA_DOCUMENT', 'httpStatus=UNKNOWN', 'readyState=complete',
                         'exceptionMessage=arbitrary private text', 'url=https://private.example/token',
                         'unknown=private', '', 'UI_DIAGNOSTIC_RESULT mode=UiSmoke passed=4 failed=0 skipped=0'])
        safe = launcher.diagnostics(raw, self.env())
        self.assertIn('classification=DATA_DOCUMENT', safe)
        self.assertIn('httpStatus=UNKNOWN', safe)
        self.assertNotIn('private', safe)
        self.assertNotIn('exceptionMessage', safe)
        self.assertIn('passed=4', safe)

    def test_readiness_and_chrome_failures_remain_distinguishable(self):
        raw = '\n'.join(['Readiness exception https://private.example/token',
                         'UI_RENDER_READINESS', 'status=NOT_READY', 'durationMs=240000', '',
                         'UI_NAVIGATION_ONLY_STAGE', 'phase=RENDER_READINESS_FAILED', '',
                         'UI_RENDER_READINESS', 'status=READY', 'durationMs=1200', '',
                         'UI_NAVIGATION_ONLY_STAGE', 'phase=CHROME_NAVIGATION_FAILED', ''])
        safe = launcher.diagnostics(raw, self.env())
        self.assertIn('status=NOT_READY', safe)
        self.assertIn('status=READY', safe)
        self.assertIn('durationMs=240000', safe)
        self.assertIn('phase=RENDER_READINESS_FAILED', safe)
        self.assertIn('phase=CHROME_NAVIGATION_FAILED', safe)
        self.assertNotIn('private.example', safe)

    def test_secret_audit_rejects_encoded_values_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = root / 'diagnostics.log'
            for secret in launcher.secret_forms(self.env()):
                text.write_text(secret)
                with self.assertRaises(ValueError):
                    launcher.audit(root, self.env())
            text.write_text('safe')
            self.assertEqual(launcher.audit(root, self.env()), 1)
            (root / 'link').symlink_to(text)
            with self.assertRaises(ValueError):
                launcher.audit(root, self.env())

    def test_exact_smoke_inventory_is_unchanged(self):
        suite = ET.parse(ROOT / 'ui-tests/smoke-tests.xml')
        methods = suite.findall('.//include')
        self.assertEqual([m.get('name') for m in methods], ['shouldLoginSuccessfully', 'shouldCreateTaskSuccessfully',
                                                          'shouldEditTaskSuccessfully', 'shouldDeleteTaskSuccessfully'])

    def test_build_compiles_only_ui_and_no_test_goal(self):
        with tempfile.TemporaryDirectory() as directory:
            compile_command, dependencies, launch = launcher.commands(ROOT, Path(directory), 'NavigationOnly', self.env())
            self.assertIn('test-compile', compile_command)
            self.assertNotIn('test', compile_command)
            self.assertEqual(compile_command[compile_command.index('-pl') + 1], 'ui-tests')
            (Path(directory) / 'classpath.txt').write_text('/tmp/fake.jar')
            java = launch()
            self.assertEqual(java[-1], 'NavigationOnly')
            self.assertTrue(any(x.startswith('-javaagent:') and 'reporting-agent.jar' in x for x in java))
            self.assertTrue(any(x.startswith('-javaagent:') and 'aspectjweaver' in x for x in java))
            self.assertNotIn('Invoke-TestMaven.ps1', ' '.join(java + dependencies))

    def simulated_run(self, mode, diagnostic_code=0, report_code=0, leak=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / 'pom.xml').write_bytes((ROOT / 'pom.xml').read_bytes())
        env = self.env()
        calls = []
        def execute(command, child_env, cwd):
            calls.append(command)
            if command[0] == 'mvn':
                for arg in command:
                    if arg.startswith('-Dmdep.outputFile='):
                        Path(arg.split('=', 1)[1]).write_text('/tmp/fake.jar')
                return 0, 'RAW_PRIVATE_BUILD_TEXT'
            if command[0] == 'java':
                part = root / 'test-runs/offline-run/ui-tests' / ('Smoke' if mode == 'UiSmoke' else 'NavigationOnly')
                results = part / 'allure-results'
                results.mkdir(parents=True)
                (results / 'result.json').write_text(env['TEST_USER_PASSWORD'] if leak else '{}')
                (part / 'completed.marker').write_text('finished')
                return diagnostic_code, 'UI_NAVIGATION\nclassification=TEST_READY\n\nRAW_PRIVATE_TEXT'
            self.assertEqual(command[0], 'allure')
            return report_code, 'PRIVATE_REPORT_TEXT'
        with patch.object(launcher, 'ROOT', root), patch.object(launcher, 'require_linux'), patch.object(launcher, 'execute', execute), contextlib.redirect_stdout(io.StringIO()) as captured:
            code = launcher.run(mode, env)
        return code, captured.getvalue(), root / 'test-runs/offline-run/ubuntu-diagnostic', calls

    def test_failure_remains_failure_and_audited_artifact_survives(self):
        code, console, output, calls = self.simulated_run('UiSmoke', diagnostic_code=7)
        self.assertEqual(code, 1)
        self.assertTrue((output / 'audited.marker').exists())
        self.assertEqual(json.loads((output / 'status.json').read_text())['exitCode'], 7)
        self.assertNotIn('PRIVATE', console)

    def test_report_failure_is_not_green(self):
        code, _, output, _ = self.simulated_run('NavigationOnly', report_code=2)
        self.assertEqual(code, 1)
        self.assertTrue((output / 'audited.marker').exists())

    def test_leak_blocks_upload(self):
        code, console, output, _ = self.simulated_run('UiSmoke', leak=True)
        self.assertEqual(code, 1)
        self.assertFalse((output / 'audited.marker').exists())
        self.assertNotIn(self.env()['TEST_USER_PASSWORD'], console)

    def test_existing_results_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'test-runs/offline-run').mkdir(parents=True)
            with patch.object(launcher, 'ROOT', root), patch.object(launcher, 'require_linux'), self.assertRaises(ValueError):
                launcher.run('NavigationOnly', self.env())

    def test_yaml_manual_only_serialized_and_secrets_scoped(self):
        import yaml
        doc = yaml.load((ROOT / '.github/workflows/ui-ubuntu-diagnostic.yml').read_text(), Loader=yaml.BaseLoader)
        self.assertEqual(set(doc['on']), {'workflow_dispatch'})
        mode = doc['on']['workflow_dispatch']['inputs']['mode']
        self.assertEqual(mode['default'], 'NavigationOnly')
        self.assertEqual(mode['options'], ['NavigationOnly', 'UiSmoke'])
        self.assertEqual(doc['concurrency'],
                         {'group': 'task-manager-test-environment', 'cancel-in-progress': 'false', 'queue': 'max'})
        job = doc['jobs']['diagnostic']
        self.assertEqual(job['runs-on'], 'ubuntu-24.04')
        self.assertEqual(job['if'], "github.ref == 'refs/heads/develop'")
        self.assertFalse(any(k.startswith('TEST_DB') for k in job['env']))
        for step in job['steps']:
            if 'secrets.' in json.dumps(step):
                self.assertEqual(step['if'], "inputs.mode == 'UiSmoke'")
        upload = job['steps'][-1]
        self.assertIn('always()', upload['if'])
        self.assertIn('audited.marker', upload['if'])


def compile_java_probe(args):
    raw = args.classpath_file.read_text().strip()
    def native(path):
        return '/mnt/' + path[0].lower() + path[2:].replace('\\', '/') if os.name != 'nt' and len(path) > 2 and path[1] == ':' else path
    jars = [native(p) for p in raw.split(';' if ';' in raw else os.pathsep)]
    repo = next(p.replace('\\', '/').split('/repository/')[0] + '/repository' for p in jars if '/repository/' in p.replace('\\', '/'))
    for artifact in ('jackson-core', 'jackson-databind', 'jackson-annotations'):
        jars.append(str(Path(repo) / f'com/fasterxml/jackson/core/{artifact}/2.18.2/{artifact}-2.18.2.jar'))
    if any(not Path(p).is_file() for p in jars):
        raise SystemExit('Missing cached dependencies; no downloads attempted')
    env = launcher.child_env(os.environ)
    env.update(UI_DIAGNOSTIC_CHROME_VERSION='157.0.8103.38', UI_DIAGNOSTIC_DRIVER_VERSION='157.0.8103.38')
    sources = [*sorted((ROOT / 'ui-tests/src/test/java').rglob('*.java')),
               *sorted((ROOT / 'reporting/src/main/java').rglob('*.java')),
               ROOT / 'scripts/checks/UbuntuDiagnosticLauncherProbe.java',
               ROOT / 'scripts/checks/UbuntuDriverEnvironmentProbe.java']
    with tempfile.TemporaryDirectory(prefix='ubuntu-launcher-offline-') as scratch:
        compiler = ['java', 'com.sun.tools.javac.Main', '-proc:none', '-encoding', 'UTF-8']
        compiler += ['-source', '17', '-target', '17', '--system', str(args.jdk_home)] if args.jdk_home else ['--release', '17']
        compiled = subprocess.run([*compiler, '-cp', os.pathsep.join(jars), '-d', scratch, *map(str, sources)], env=env)
        if compiled.returncode:
            raise SystemExit(compiled.returncode)
        checked = subprocess.run(['java', '-cp', os.pathsep.join([scratch, *jars]),
                    'ru.tarkovskiy.ui.base.UbuntuDiagnosticLauncherProbe', scratch], env=env, cwd=scratch, timeout=30)
        if checked.returncode:
            raise SystemExit(checked.returncode)
        prepared, driver = prepared_fake_browser(dict(env, **OfflineChecks().env()), Path(scratch))
        for mode in ('NavigationOnly', 'UiSmoke', 'UiRegression'):
            child = launcher.child_env(prepared, 'NavigationOnly' if mode == 'NavigationOnly' else 'UiSmoke')
            child.update(TEST_FUTURE_SECRET='SYNTHETIC_future', TM_PRIVATE_SETTING='SYNTHETIC_setting', TMPDIR=scratch)
            if mode == 'UiRegression':
                child['TM_SUITE'] = 'Regression'
            checked = subprocess.run(['java', '-cp', os.pathsep.join([scratch, *jars]),
                'ru.tarkovskiy.ui.base.UbuntuDriverEnvironmentProbe', mode, scratch, str(driver)],
                env=child, cwd=scratch, timeout=30)
            if checked.returncode:
                raise SystemExit(checked.returncode)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compile-java', action='store_true')
    parser.add_argument('--jdk-home', type=Path)
    parser.add_argument('--classpath-file', type=Path, default=ROOT / 'ui-tests/target/reporting-probe-classpath.txt')
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(OfflineChecks))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.compile_java:
        compile_java_probe(args)
