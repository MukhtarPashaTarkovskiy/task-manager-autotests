"""Ubuntu full CI. Sequential existing XML suites; private child output; audited exports only."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import signal
import tempfile
import time
import urllib.request
import urllib.error

_spec = importlib.util.spec_from_file_location('ui_ubuntu_diagnostic', Path(__file__).with_name('run-ui-ubuntu-diagnostic.py'))
diag = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(diag)
ROOT = diag.ROOT
SUITES = {
    'ApiSmoke': ('api-tests', 'Smoke', 6), 'UiSmoke': ('ui-tests', 'Smoke', 4),
    'ApiRegression': ('api-tests', 'Regression', 51), 'UiRegression': ('ui-tests', 'Regression', 66),
}


def selection(value):
    if value not in ('Smoke', 'Regression', 'All'):
        raise ValueError('Unknown selection')
    return [key for key, (_, kind, _) in SUITES.items() if value == 'All' or kind == value]


def test_env(env, suite):
    module, kind, _ = SUITES[suite]
    clean = diag.child_env(env, 'UiSmoke')
    clean['TM_SUITE'] = kind
    return clean


def no_redirect():
    class Refuse(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError('Redirect refused')
    return urllib.request.build_opener(Refuse)


def readiness():
    """Windows CI contract: GET fixed TEST /login, HTTP 200 and both markers, <=4m.

    No credentials, redirects, arbitrary targets, response/exception logging or browser retry.
    UI additionally retains its existing RenderReadiness fixture, as Windows does.
    """
    started = time.monotonic()
    deadline = started + 240
    while time.monotonic() < deadline:
        try:
            with no_redirect().open(diag.ORIGIN + '/login', timeout=min(20, max(.1, deadline - time.monotonic()))) as response:
                body = response.read(2_000_000)
                if response.status == 200 and b'id="username"' in body and b'id="password"' in body:
                    return {'status': 'READY', 'durationMs': round((time.monotonic() - started) * 1000)}
        except (OSError, ValueError):
            pass
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(5, remaining))
    return {'status': 'NOT_READY', 'durationMs': round((time.monotonic() - started) * 1000)}


def write_json(path, value):
    with path.open('x', encoding='utf-8') as out:
        json.dump(value, out, indent=2, ensure_ascii=True)


def read_results(directory):
    results = []
    for path in sorted(directory.glob('*-result.json')):
        item = json.loads(path.read_text())
        if not item.get('fullName') or not item.get('historyId') or item.get('status') not in ('passed', 'failed', 'broken', 'skipped', 'unknown'):
            raise ValueError('Invalid Allure result')
        results.append((path.name, item))
    return results


def report_plan(chosen):
    plan = []
    for kind in ('Smoke', 'Regression', 'All'):
        if kind == 'All' and len(chosen) != 4:
            continue
        members = [s for s in chosen if kind == 'All' or SUITES[s][1] == kind]
        if not members:
            continue
        for scope in ('api-tests', 'ui-tests', 'Combined'):
            plan.append((scope + '-' + kind, [s for s in members if scope == 'Combined' or SUITES[s][0] == scope]))
    return plan


def copy_results(source, destination, suite, namespace):
    # Never append original raw results and never rewrite them in place.
    for path in source.iterdir():
        if path.is_symlink() or not path.is_file():
            raise ValueError('Unexpected raw result entry')
        if path.name == 'environment.properties':
            continue
        dest = destination / (suite + '-' + path.name)
        if path.name.endswith('-result.json') or path.name.endswith('-container.json'):
            item = json.loads(path.read_text())
            # Attachment source paths must stay flat, scoped to these audited inputs.
            def rewrite(value):
                if isinstance(value, dict):
                    for key, child in list(value.items()):
                        if key == 'source':
                            if not isinstance(child, str) or Path(child).name != child or not (source / child).is_file():
                                raise ValueError('Unsafe attachment reference')
                            value[key] = suite + '-' + child
                        else:
                            rewrite(child)
                elif isinstance(value, list):
                    for child in value:
                        rewrite(child)
            rewrite(item)
            if path.name.endswith('-result.json'):
                # All includes intentional Smoke+Regression overlap, never retries.
                if namespace:
                    item['historyId'] = hashlib.sha256((suite + ':' + item['historyId']).encode()).hexdigest()
                    # Allure 2.46 also derives retry identity from testCaseId/fullName + parameters.
                    item['testCaseId'] = hashlib.sha256((suite + ':' + (item.get('testCaseId') or item['fullName'])).encode()).hexdigest()
                    item.setdefault('parameters', []).append({'name': 'Execution suite', 'value': suite})
                item.setdefault('labels', []).append({'name': 'executionSuite', 'value': suite})
            write_json(dest, item)
        else:
            shutil.copyfile(path, dest)


def generate_reports(run, output, chosen, env):
    history_root = ROOT / '.allure-history-ubuntu'
    reports = output / 'reports'
    reports.mkdir()
    failure = False
    for stream, members in report_plan(chosen):
        directory = reports / stream
        directory.mkdir()
        inputs = directory / 'allure-results'
        inputs.mkdir()
        inventory, unique = [], set()
        expected_stats = dict.fromkeys(('passed', 'failed', 'broken', 'skipped', 'unknown'), 0)
        available = []
        for suite in members:
            module, kind, _ = SUITES[suite]
            source = run / module / kind / 'allure-results'
            if not source.is_dir():
                failure = True
                continue
            diag.audit(source, env)
            items = read_results(source)
            if not items:
                failure = True
                continue
            available.append(suite)
            for _, item in items:
                inventory.append([suite, item['fullName'], item['historyId']])
                unique.add((module, item['fullName'], item['historyId']))
                expected_stats[item['status']] += 1
            copy_results(source, inputs, suite, stream.endswith('-All'))
        if not available:
            continue
        fingerprint = hashlib.sha256(json.dumps(sorted(inventory)).encode()).hexdigest()
        # Immutable history, matching stream+inventory only; never copy old result files.
        historical = []
        if history_root.exists():
            diag.audit(history_root, env)
            for manifest_path in history_root.glob('*/' + stream + '/manifest.json'):
                manifest = json.loads(manifest_path.read_text())
                if manifest.get('fingerprint') == fingerprint and manifest.get('runId') != env['TM_RUN_ID']:
                    historical.append((manifest.get('createdNs', 0), manifest_path.parent))
        if historical:
            previous = max(historical, key=lambda value: value[0])[1] / 'history'
            if previous.is_dir():
                shutil.copytree(previous, inputs / 'history')
        write_json(inputs / 'executor.json', {'name': 'GitHub Actions Ubuntu', 'type': 'github',
            'buildName': env['TM_RUN_ID'], 'buildOrder': int(env.get('GITHUB_RUN_NUMBER', '0'))})
        (inputs / 'environment.properties').write_text('Environment=TEST\nOS=Ubuntu-24.04\nHeadless=true\n')
        diag.audit(inputs, env)
        code, _ = diag.execute(['allure', 'generate', str(inputs), '--output', str(directory / 'html')], diag.child_env(env), ROOT)
        if code:
            failure = True
            continue
        manifest = {'runId': env['TM_RUN_ID'], 'stream': stream, 'fingerprint': fingerprint,
            'members': available, 'requestedMembers': members, 'executions': len(inventory), 'uniqueTests': len(unique),
            'createdNs': time.time_ns()}
        write_json(directory / 'manifest.json', manifest)
        stats = json.loads((directory / 'html/widgets/summary.json').read_text())['statistic']
        if (stats['total'] != len(inventory) or available != members
                or any(stats.get(key, 0) != count for key, count in expected_stats.items())):
            failure = True
        diag.audit(directory, env)
        # Store only history and its manifest in a separate Ubuntu-only immutable cache.
        history = directory / 'html/history'
        if history.is_dir():
            archived = history_root / env['TM_RUN_ID'] / stream
            archived.mkdir(parents=True, exist_ok=False)
            shutil.copytree(history, archived / 'history')
            write_json(archived / 'manifest.json', manifest)
    if history_root.exists():
        diag.audit(history_root, env)
    return failure


def run(value, env):
    diag.require_linux()
    chosen = selection(value)
    diag.validate_settings('UiSmoke', env)  # Same strict TEST/JDBC/TLS contract for all suites.
    if env.get('UI_DIAGNOSTIC_CHROME_PATH') != '/usr/bin/google-chrome' or diag.build(env.get('UI_DIAGNOSTIC_CHROME_VERSION', '')) != diag.build(env.get('UI_DIAGNOSTIC_DRIVER_VERSION', '')):
        raise ValueError('Browser pair not prepared')
    run_root = ROOT / 'test-runs' / env['TM_RUN_ID']
    if run_root.exists():
        raise ValueError('Run ID exists; preserved')
    output = run_root / 'ubuntu-ci'
    output.mkdir(parents=True)
    status = {'selection': value, 'runId': env['TM_RUN_ID'], 'suites': {},
              'os': 'Ubuntu 24.04', 'selectedChrome': env['UI_DIAGNOSTIC_CHROME_VERSION'],
              'selectedChromeDriver': env['UI_DIAGNOSTIC_DRIVER_VERSION']}
    failed = False
    for suite in chosen:
        module, kind, expected = SUITES[suite]
        record = {'expected': expected, 'stage': 'build', 'exitCode': 1}
        status['suites'][suite] = record
        safe = ''
        try:
            with tempfile.TemporaryDirectory(prefix='ubuntu-ci-', dir=env['RUNNER_TEMP']) as temporary:
                main = 'ru.tarkovskiy.api.base.UbuntuApiLauncher' if module == 'api-tests' else 'ru.tarkovskiy.ui.base.UbuntuDiagnosticLauncher'
                compile_cmd, deps, launch = diag.commands(ROOT, Path(temporary), suite, env, module, main)
                for command in (compile_cmd, deps):
                    code, _ = diag.execute(command, diag.child_env(env), ROOT)
                    if code:
                        raise ValueError('Build failed')
                record['stage'] = 'readiness'
                record['render'] = readiness()
                if record['render']['status'] != 'READY':
                    raise ValueError('Render not ready; no suite started')
                record['stage'] = 'test'
                code, raw = diag.execute(launch(), test_env(env, suite), ROOT)
                record['exitCode'] = code
                safe = diag.diagnostics(raw, env)
                part = run_root / module / kind
                summary = json.loads((part / 'suite-summary.json').read_text())
                complete = (summary['complete'] is True and summary['expected'] == summary['actual']
                            and sum(summary['expected'].values()) == expected
                            and summary['passed'] == expected and summary['failed'] == 0 and summary['skipped'] == 0
                            and summary['configurationErrors'] == 0 and (part / 'completed.marker').is_file())
                if code or not complete:
                    raise ValueError('Failed or incomplete suite')
                record['stage'] = 'complete'
        except Exception:
            record['exitCode'] = record['exitCode'] or 1
            failed = True
        # Audit every completed/partial suite before copying anything for publication.
        part = run_root / module / kind
        export = output / suite
        export.mkdir()
        if part.exists():
            diag.audit(part, env)  # Safety failure aborts; never publish an unaudited sibling.
            shutil.copytree(part, export / 'results')
        (export / 'diagnostics.log').write_text(safe)
        write_json(export / 'status.json', record)
        diag.audit(export, env)
        print(safe, end='')
        print(f'{suite}: stage={record["stage"]}; exitCode={record["exitCode"]}; expected={expected}')
    failed = generate_reports(run_root, output, chosen, env) or failed
    status['failed'] = failed
    write_json(output / 'status.json', status)
    diag.audit(output, env)
    (output / 'audited.marker').write_text('audited\n')
    if (ROOT / '.allure-history-ubuntu').exists():
        (output / 'history-audited.marker').write_text('audited\n')
    print(f'Ubuntu CI {value}: {"FAILURE" if failed else "SUCCESS"}; audited results ready')
    return int(failed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', required=True, choices=('Smoke', 'Regression', 'All'))
    args = parser.parse_args()
    os.umask(0o077)
    def cancelled(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, cancelled)
    try:
        return run(args.suite, os.environ)
    except KeyboardInterrupt:
        print('Ubuntu CI cancelled; child process group terminated')
        return 130
    except Exception:
        print('Ubuntu CI failed; invalid environment/build/report or audit failure; private details suppressed; upload refused')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
