"""Offline Ubuntu CI validation. Mock HTTP/processes, synthetic Allure/TestNG only."""
from pathlib import Path
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
import shutil
from unittest.mock import patch
import xml.etree.ElementTree as ET
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ubuntu_ci', ROOT / 'scripts/run-tests-ubuntu.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def environment():
    return dict(TEST_BASE_URL=ci.diag.ORIGIN, HEADLESS='true', TM_RUN_ID='offline-run', RUNNER_TEMP='/tmp',
        TEST_USER_PASSWORD='SYNTHETIC_only_<&>+password',
        TEST_DB_URL='jdbc:postgresql://db.example.test/task_manager_test?sslmode=require',
        TEST_DB_USERNAME='SYNTHETIC_db_username', TEST_DB_PASSWORD='SYNTHETIC_db_password',
        TEST_ALLOWED_DB_HOST='db.example.test', TEST_ALLOWED_DB_PORT='5432',
        UI_DIAGNOSTIC_CHROME_PATH='/usr/bin/google-chrome', UI_DIAGNOSTIC_DRIVER_PATH='/tmp/fake-driver',
        UI_DIAGNOSTIC_CHROME_VERSION='157.0.8103.38', UI_DIAGNOSTIC_DRIVER_VERSION='157.0.8103.80')


class Checks(unittest.TestCase):
    def test_selection_order_counts_xml(self):
        for choice, counts in [('Smoke', [6, 4]), ('Regression', [51, 66]), ('All', [6, 4, 51, 66])]:
            suites = ci.selection(choice)
            self.assertEqual([ci.SUITES[s][2] for s in suites], counts)
            for s in suites:
                module, kind, _ = ci.SUITES[s]
                xml = ROOT / module / (kind.lower() + '-tests.xml')
                self.assertEqual(ET.parse(xml).getroot().tag, 'suite')
        with self.assertRaises(ValueError):
            ci.selection('NavigationOnly')

    def test_environment_isolation_and_credentials_not_arguments(self):
        env = dict(environment(), JAVA_TOOL_OPTIONS='unsafe', TM_OTHER='unsafe', TEST_OTHER='unsafe')
        for suite in ci.SUITES:
            child = ci.test_env(env, suite)
            self.assertEqual(child['TM_SUITE'], ci.SUITES[suite][1])
            self.assertEqual(child['TEST_DB_PASSWORD'], env['TEST_DB_PASSWORD'])
            self.assertNotIn('JAVA_TOOL_OPTIONS', child)
            self.assertNotIn('TM_OTHER', child)
            self.assertNotIn('TEST_OTHER', child)
            with tempfile.TemporaryDirectory() as temporary:
                scratch = Path(temporary)
                (scratch / 'classpath.txt').write_text('/tmp/synthetic.jar')
                module = ci.SUITES[suite][0]
                compile_cmd, deps, launch = ci.diag.commands(ROOT, scratch, suite, env, module, 'synthetic.Main')
                self.assertIn('test-compile', compile_cmd)
                self.assertNotIn('test', compile_cmd)
                self.assertIn(module, compile_cmd)
                for value in ci.diag.secret_forms(env):
                    self.assertNotIn(value, ' '.join(compile_cmd + deps + launch()))
        self.assertTrue(all(k not in ci.diag.child_env(env) for k in ci.diag.SETTINGS))

    def test_bad_db_and_browser_targets_refused(self):
        for key, value in [('TEST_BASE_URL', 'https://prod.invalid'), ('TEST_DB_URL', 'jdbc:postgresql://db.example.test/task_manager?sslmode=require'), ('TEST_ALLOWED_DB_PORT', '5433'), ('TEST_DB_PASSWORD', '')]:
            with self.assertRaises(ValueError):
                ci.diag.validate_settings('UiSmoke', dict(environment(), **{key: value}))
        with tempfile.TemporaryDirectory() as temporary, patch.object(ci, 'ROOT', Path(temporary)), patch.object(ci.diag, 'require_linux'):
            with self.assertRaises(ValueError):
                ci.run('All', dict(environment(), UI_DIAGNOSTIC_DRIVER_VERSION='156.0.1.2'))

    def test_readiness_fixed_origin_and_markers_without_credentials(self):
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, *args): return b'<input id="username"><input id="password">'
        class Opener:
            def open(inner, url, **kwargs):
                self.assertEqual(url, ci.diag.ORIGIN + '/login')
                self.assertEqual(set(kwargs), {'timeout'})
                return Response()
        with patch.object(ci, 'no_redirect', return_value=Opener()):
            self.assertEqual(ci.readiness()['status'], 'READY')
        # Time is fake: never wait four real minutes or make a request.
        with patch.object(ci, 'no_redirect', side_effect=OSError), patch.object(ci.time, 'monotonic', side_effect=[0, 0, 0, 241, 241, 241]), patch.object(ci.time, 'sleep'):
            self.assertEqual(ci.readiness()['status'], 'NOT_READY')

    def simulated(self, value='All', bad=None, report_code=0, leak=False, readiness='READY', build_fail=False, identifier='offline-run', root=None, summary_patch=None, report_leak=False):
        if root is None:
            temp = tempfile.TemporaryDirectory()
            self.addCleanup(temp.cleanup)
            root = Path(temp.name)
        (root / 'pom.xml').write_bytes((ROOT / 'pom.xml').read_bytes())
        env = dict(environment(), TM_RUN_ID=identifier)
        launches = []
        allures = []
        def execute(command, child, cwd):
            if command[0] == 'mvn':
                for arg in command:
                    if arg.startswith('-Dmdep.outputFile='):
                        Path(arg.split('=', 1)[1]).write_text('/tmp/synthetic.jar')
                return (2 if build_fail else 0), 'PRIVATE_BUILD'
            if command[0] == 'java':
                suite = command[-1]
                launches.append(suite)
                module, kind, count = ci.SUITES[suite]
                part = root / 'test-runs' / identifier / module / kind
                results = part / 'allure-results'
                results.mkdir(parents=True)
                # Smoke scenarios intentionally overlap regression, exactly as in project.
                for n in range(count):
                    item = {'uuid': suite + str(n), 'name': 'synthetic', 'fullName': module + '.method' + str(n),
                        'historyId': module + '-' + str(n), 'status': 'failed' if bad == suite and n == 0 else 'passed',
                        'start': n, 'stop': n + 1, 'attachments': [{'name': 'safe', 'source': 'safe.txt', 'type': 'text/plain'}]}
                    ci.write_json(results / (str(n) + '-result.json'), item)
                (results / 'safe.txt').write_text(env['TEST_DB_PASSWORD'] if leak else 'synthetic safe attachment')
                (part / 'completed.marker').write_text('finished')
                summary = {'expected': {'method': count}, 'actual': {'method': count}, 'complete': True,
                    'passed': count if bad != suite else count - 1, 'failed': int(bad == suite), 'skipped': 0, 'configurationErrors': 0}
                summary.update(summary_patch or {})
                ci.write_json(part / 'suite-summary.json', summary)
                return int(bad == suite), 'UI_NAVIGATION\nclassification=TEST_READY\n\nPRIVATE_RAW_TEXT'
            self.assertEqual(command[0], 'allure')
            allures.append(command)
            if report_code:
                return report_code, 'PRIVATE_REPORT'
            source = Path(command[2]); html = Path(command[-1])
            (html / 'widgets').mkdir(parents=True)
            (html / 'history').mkdir()
            ci.write_json(html / 'widgets/summary.json', {'statistic': {'total': len(list(source.glob('*-result.json'))), **{status: sum(item['status'] == status for _, item in ci.read_results(source)) for status in ('passed', 'failed', 'broken', 'skipped', 'unknown')}}})
            ci.write_json(html / 'history/history.json', {'synthetic': {}})
            (html / 'index.html').write_text(env['TEST_DB_PASSWORD'] if report_leak else 'synthetic offline report')
            return 0, 'PRIVATE_REPORT'
        with patch.object(ci, 'ROOT', root), patch.object(ci.diag, 'require_linux'), patch.object(ci.diag, 'execute', execute), patch.object(ci, 'readiness', return_value={'status': readiness, 'durationMs': 1}), contextlib.redirect_stdout(io.StringIO()) as console:
            code = ci.run(value, env)
        self.assertNotIn('PRIVATE', console.getvalue())
        self.assertNotIn(env['TEST_DB_PASSWORD'], console.getvalue())
        return code, root / 'test-runs' / identifier / 'ubuntu-ci', launches, allures

    def test_all_sequential_four_suites_nine_reports_and_127_executions(self):
        code, output, launches, allures = self.simulated()
        self.assertEqual(code, 0)
        self.assertEqual(launches, list(ci.SUITES))
        self.assertEqual(len(allures), 9)
        manifest = json.loads((output / 'reports/Combined-All/manifest.json').read_text())
        self.assertEqual((manifest['executions'], manifest['uniqueTests']), (127, 117))
        copied = ci.read_results(output / 'reports/Combined-All/allure-results')
        self.assertEqual(len(set(r['historyId'] for _, r in copied)), 127)
        for _, item in copied:
            self.assertTrue((output / 'reports/Combined-All/allure-results' / item['attachments'][0]['source']).is_file())
        self.assertTrue((output / 'audited.marker').is_file())
        self.assertTrue((output / 'history-audited.marker').is_file())

    def test_smoke_and_regression_three_reports(self):
        for kind in ('Smoke', 'Regression'):
            code, output, launches, allures = self.simulated(kind)
            self.assertEqual(code, 0)
            self.assertEqual(launches, ci.selection(kind))
            self.assertEqual(len(allures), 3)

    def test_test_failure_does_not_stop_other_suites_or_artifacts(self):
        code, output, launches, allures = self.simulated(bad='ApiSmoke')
        self.assertEqual(code, 1)
        self.assertEqual(launches, list(ci.SUITES))
        self.assertEqual(len(allures), 9)
        self.assertTrue((output / 'audited.marker').is_file())

    def test_not_ready_and_build_failure_do_not_start_java(self):
        for kwargs in ({'readiness': 'NOT_READY'}, {'build_fail': True}):
            code, output, launches, _ = self.simulated('Smoke', **kwargs)
            self.assertEqual(code, 1)
            self.assertEqual(launches, [])
            self.assertTrue((output / 'audited.marker').is_file())
            status = json.loads((output / 'status.json').read_text())
            self.assertTrue(status['failed'])

    def test_report_failure_preserves_raw_artifacts(self):
        code, output, _, _ = self.simulated('Smoke', report_code=3)
        self.assertEqual(code, 1)
        self.assertTrue((output / 'audited.marker').is_file())
        self.assertTrue((output / 'UiSmoke/results/allure-results/0-result.json').is_file())

    def test_leak_blocks_all_uploads(self):
        with self.assertRaises(ValueError):
            self.simulated('Smoke', leak=True)
        with self.assertRaises(ValueError):
            self.simulated('Smoke', report_leak=True)

    def test_zero_java_code_does_not_hide_incomplete_or_configuration_failure(self):
        for summary in ({'complete': False}, {'actual': {}}, {'configurationErrors': 1}, {'skipped': 1}):
            code, output, _, _ = self.simulated('Smoke', summary_patch=summary)
            self.assertEqual(code, 1)
            self.assertTrue((output / 'audited.marker').is_file())

    def test_history_reuses_history_only_without_mixing_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, first, _, _ = self.simulated('Smoke', root=root, identifier='offline-first')
            digest = hashlib.sha256((first / 'ApiSmoke/results/allure-results/0-result.json').read_bytes()).hexdigest()
            _, second, _, _ = self.simulated('Smoke', root=root, identifier='offline-second')
            self.assertTrue((second / 'reports/Combined-Smoke/allure-results/history/history.json').is_file())
            self.assertEqual(len(ci.read_results(second / 'reports/Combined-Smoke/allure-results')), 10)
            self.assertEqual(hashlib.sha256((first / 'ApiSmoke/results/allure-results/0-result.json').read_bytes()).hexdigest(), digest)
            with patch.object(ci, 'ROOT', root), patch.object(ci.diag, 'require_linux'), self.assertRaises(ValueError):
                ci.run('Smoke', dict(environment(), TM_RUN_ID='offline-first'))

    def test_attachment_traversal_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'raw'; target = Path(temporary) / 'copy'
            source.mkdir(); target.mkdir()
            ci.write_json(source / 'x-result.json', {'historyId': 'id', 'attachments': [{'source': '../private.txt'}]})
            with self.assertRaises(ValueError):
                ci.copy_results(source, target, 'UiSmoke', False)

    def test_yaml_serialized_dispatch_gate_and_exports(self):
        doc = yaml.load((ROOT / '.github/workflows/tests-ubuntu.yml').read_text(), Loader=yaml.BaseLoader)
        self.assertEqual(set(doc['on']), {'workflow_dispatch', 'push'})
        self.assertEqual(doc['on']['push']['branches'], ['develop'])
        self.assertEqual(doc['on']['workflow_dispatch']['inputs']['suite']['options'], ['Smoke', 'Regression', 'All'])
        self.assertNotIn('concurrency', doc)  # Disabled push must not disturb the shared TEST queue.
        self.assertEqual(doc['permissions'], {'contents': 'read'})
        self.assertEqual(set(doc['jobs']), {'test'})
        job = doc['jobs']['test']
        self.assertNotIn('needs', job)
        self.assertEqual(' '.join(job['if'].split()),
            "github.ref == 'refs/heads/develop' && "
            "(github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'push' && vars.UBUNTU_CI_ENABLED == 'true'))")
        self.assertEqual(job['env']['SUITE_SELECTION'],
            "${{ github.event_name == 'workflow_dispatch' && inputs.suite || 'All' }}")
        self.assertEqual(job['concurrency'], {'group': 'task-manager-test-environment', 'cancel-in-progress': 'false'})
        self.assertEqual(job['runs-on'], 'ubuntu-24.04')
        self.assertEqual(job['environment'], 'test')
        self.assertNotIn('strategy', job)
        for step in job['steps']:
            if 'secrets.' in json.dumps(step):
                self.assertEqual(step['id'], 'run')
            if step.get('uses', '').startswith('actions/upload-artifact'):
                self.assertIn('always()', step['if'])
                self.assertIn('audited.marker', step['if'])
        diagnostic = yaml.load((ROOT / '.github/workflows/ui-ubuntu-diagnostic.yml').read_text(),
            Loader=yaml.BaseLoader)
        self.assertEqual(set(diagnostic['on']), {'workflow_dispatch'})
        self.assertEqual(diagnostic['concurrency'], job['concurrency'])


def compile_java(jdk):
    jars = set()
    sources = []
    for module in ('api-tests', 'ui-tests'):
        raw = (ROOT / module / 'target/reporting-probe-classpath.txt').read_text().strip()
        for path in raw.split(';' if ';' in raw else os.pathsep):
            jars.add('/mnt/' + path[0].lower() + path[2:].replace('\\', '/') if len(path) > 2 and path[1] == ':' else path)
        sources.extend((ROOT / module / 'src/test/java').rglob('*.java'))
    repository = next(p.split('/repository/')[0] + '/repository' for p in jars if '/repository/' in p)
    for name in ('jackson-core', 'jackson-databind', 'jackson-annotations'):
        jars.add(str(Path(repository) / f'com/fasterxml/jackson/core/{name}/2.18.2/{name}-2.18.2.jar'))
    if any(not Path(p).is_file() for p in jars):
        raise SystemExit('Missing cached dependencies; no download attempted')
    sources.extend((ROOT / 'reporting/src/main/java').rglob('*.java'))
    sources.append(ROOT / 'scripts/checks/UbuntuSuiteProbe.java')
    env = ci.diag.child_env(os.environ)
    env['TEST_USER_PASSWORD'] = 'synthetic-offline-inventory'
    with tempfile.TemporaryDirectory(prefix='ubuntu-ci-offline-java-') as temporary:
        command = ['java', 'com.sun.tools.javac.Main', '-proc:none', '-encoding', 'UTF-8']
        command += ['-source', '17', '-target', '17', '--system', str(jdk)] if jdk else ['--release', '17']
        result = subprocess.run([*command, '-cp', os.pathsep.join(sorted(jars)), '-d', temporary, *map(str, sources)], env=env)
        if result.returncode: raise SystemExit(result.returncode)
        result = subprocess.run(['java', '-cp', os.pathsep.join([temporary, *sorted(jars)]),
            'ru.tarkovskiy.reporting.UbuntuSuiteProbe', temporary], cwd=ROOT, env=env, timeout=90)
        if result.returncode: raise SystemExit(result.returncode)
        agent_classes = Path(temporary) / 'agent-classes'
        agent_classes.mkdir()
        agent_source = ROOT / 'reporting/agent/ru/tarkovskiy/reporting/bootstrap/IsolatedRunAgent.java'
        result = subprocess.run([*command, '-d', str(agent_classes), str(agent_source)], env=env)
        if result.returncode: raise SystemExit(result.returncode)
        agent = Path(temporary) / 'reporting-agent.jar'
        with zipfile.ZipFile(agent, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: ru.tarkovskiy.reporting.bootstrap.IsolatedRunAgent\n\n')
            for path in agent_classes.rglob('*.class'):
                archive.write(path, path.relative_to(agent_classes).as_posix())
        for module in ('api-tests', 'ui-tests'):
            services = ROOT / module / 'src/test/resources/META-INF/services'
            shutil.copytree(services, Path(temporary) / 'META-INF/services', dirs_exist_ok=True)
            for phase in ('setup', 'cleanup', 'test'):
                child = dict(env, TM_RUN_ID='offline-' + module + '-' + phase, TM_SUITE='Smoke')
                result = subprocess.run(['java', f'-javaagent:{agent}={temporary},{module}', '-cp', os.pathsep.join([temporary, *sorted(jars)]),
                    'ru.tarkovskiy.reporting.UbuntuSuiteProbe', temporary, phase], cwd=ROOT, env=child, timeout=45,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                if result.returncode:
                    raise SystemExit('Synthetic real-listener execution failed; no business tests run')
                part = Path(temporary) / 'test-runs' / child['TM_RUN_ID'] / module / 'Smoke'
                ci.diag.audit(part, dict(environment(), TEST_USER_PASSWORD=child['TEST_USER_PASSWORD']))
                items = ci.read_results(part / 'allure-results')
                if not items or not (part / 'suite-summary.json').is_file():
                    raise SystemExit('Synthetic listener failure results missing')
        print('PASS: real reporting-agent and API/UI SPI listeners, 6 synthetic failure cases; audited raw results')



def real_allure_offline():
    """Actual cached Allure CLI, synthetic data only; no download and no services."""
    lib = ROOT / '.allure/allure-2.46.1/lib'
    if not (lib / 'allure-commandline-2.46.1.jar').is_file():
        raise SystemExit('Cached Allure CLI missing; no download attempted')
    with tempfile.TemporaryDirectory(prefix='ubuntu-ci-offline-allure-') as temporary:
        root = Path(temporary); run = root / 'test-runs/offline-allure'; output = run / 'ubuntu-ci'
        output.mkdir(parents=True)
        for suite, (module, kind, count) in ci.SUITES.items():
            results = run / module / kind / 'allure-results'
            results.mkdir(parents=True)
            for n in range(count):
                ci.write_json(results / (str(n) + '-result.json'), {'uuid': suite + str(n), 'name': 'synthetic',
                    'fullName': module + '.case' + str(n), 'historyId': module + str(n), 'status': 'passed', 'start': n, 'stop': n + 1,
                    'attachments': [{'name': 'safe synthetic attachment', 'source': 'safe.txt', 'type': 'text/plain'}]})
            (results / 'safe.txt').write_text('synthetic offline attachment')
        def execute(command, env, cwd):
            return original(['java', '-cp', str(lib / '*'), 'io.qameta.allure.CommandLine', *command[1:]], env, cwd)
        original = ci.diag.execute
        with patch.object(ci, 'ROOT', root), patch.object(ci.diag, 'execute', execute):
            if ci.generate_reports(run, output, list(ci.SUITES), dict(environment(), TM_RUN_ID='offline-allure')):
                raise SystemExit('Synthetic Allure generation failed')
        stats = json.loads((output / 'reports/Combined-All/html/widgets/summary.json').read_text())['statistic']
        if stats['total'] != 127 or stats['passed'] != 127:
            raise SystemExit('Synthetic Allure executions collapsed')
        ci.diag.audit(output, environment())
        print('PASS: actual offline Allure CLI generated 9 synthetic reports; Combined-All 127/127; audited')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compile-java', action='store_true')
    parser.add_argument('--jdk-home', type=Path)
    parser.add_argument('--allure-offline', action='store_true')
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Checks))
    if not result.wasSuccessful(): raise SystemExit(1)
    if args.compile_java: compile_java(args.jdk_home)
    if args.allure_offline: real_allure_offline()
