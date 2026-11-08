"""Linux-only diagnostic launcher. No execution on import; use --help for commands.

prepare-browser downloads a driver only when the installed one is incompatible.
run builds without tests, then explicitly invokes NavigationOnly or the Smoke XML.
Raw child output stays private; only allowlisted diagnostic fields are published.
"""
from pathlib import Path
import argparse
import base64
import html
import io
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ORIGIN = 'https://task-manager-test-588r.onrender.com'
VERSION = r'[0-9]+(?:\.[0-9]+){3}'
SECRET_KEYS = ('TEST_USER_PASSWORD', 'TEST_DB_URL', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'TEST_ALLOWED_DB_HOST')
SETTINGS = (*SECRET_KEYS, 'TEST_ALLOWED_DB_PORT')
JAVA_OVERRIDES = ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS', 'CLASSPATH')
MANIFEST = 'https://googlechromelabs.github.io/chrome-for-testing/latest-patch-versions-per-build-with-downloads.json'


def require_linux():
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        raise ValueError('Linux x64 required')
    release = platform.freedesktop_os_release()
    if release.get('ID') != 'ubuntu' or release.get('VERSION_ID') != '24.04':
        raise ValueError('Ubuntu 24.04 required')


def child_env(env, mode=None):
    clean = {k: v for k, v in env.items() if not k.startswith(('TEST_', 'TM_')) and k not in JAVA_OVERRIDES}
    clean['MAVEN_SKIP_RC'] = '1'
    if mode:
        clean.update(TEST_BASE_URL=ORIGIN, HEADLESS='true', TM_RUN_ID=env['TM_RUN_ID'],
                     TM_SUITE='Smoke' if mode == 'UiSmoke' else 'NavigationOnly')
        if mode == 'UiSmoke':
            clean.update({k: env[k] for k in SETTINGS})
    return clean


def secret_forms(env):
    forms = set()
    for key in SECRET_KEYS:
        value = env.get(key)
        if value:
            xml = value.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')
            java_url = urllib.parse.quote_plus(value, safe='*').replace('~', '%7E')
            forms.update((value, urllib.parse.quote(value, safe=''), urllib.parse.quote_plus(value, safe=''),
                          java_url, xml, html.escape(value, quote=True), base64.b64encode(value.encode()).decode(),
                          json.dumps(value, ensure_ascii=True)[1:-1], json.dumps(value, ensure_ascii=False)[1:-1]))
    return sorted(forms, key=len, reverse=True)


def redact(text, env):
    for value in secret_forms(env):
        text = text.replace(value, '[REDACTED]')
    return text


def validate_settings(mode, env):
    if mode not in ('NavigationOnly', 'UiSmoke') or env.get('TEST_BASE_URL') != ORIGIN or env.get('HEADLESS') != 'true':
        raise ValueError('Invalid diagnostic mode or TEST environment')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,119}', env.get('TM_RUN_ID', '')):
        raise ValueError('Invalid Run ID')
    if mode == 'UiSmoke':
        if any(not env.get(k, '').strip() for k in SETTINGS):
            raise ValueError('Missing TEST settings')
        # The Java launcher additionally reuses DbConnection.validateTarget before TestNG.
        host, port = env['TEST_ALLOWED_DB_HOST'], env['TEST_ALLOWED_DB_PORT']
        dns = r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+'
        match = re.fullmatch(r'jdbc:postgresql://([a-z0-9.-]+)(?::([1-9][0-9]{0,4}))?/task_manager_test\?sslmode=(require|verify-full)', env['TEST_DB_URL'])
        if (len(host) > 253 or not re.fullmatch(dns, host) or not re.fullmatch(r'[1-9][0-9]{0,4}', port)
                or int(port) > 65535 or not match or match[1] != host or int(match[2] or 5432) != int(port)):
            raise ValueError('TEST database target refused')


def build(version):
    if not re.fullmatch(VERSION, version):
        raise ValueError('Invalid browser version')
    return version.rsplit('.', 1)[0]


def executable_version(path):
    result = subprocess.run([str(path), '--version'], capture_output=True, text=True, check=True, timeout=15,
                            env=child_env(os.environ))
    match = re.search(r'\b(' + VERSION + r')\b', result.stdout)
    if not match:
        raise ValueError('Executable version unavailable')
    return match[1]


def driver_download(manifest, chrome_version):
    entry = manifest['builds'][build(chrome_version)]
    version = entry['version']
    if build(version) != build(chrome_version):
        raise ValueError('ChromeDriver build mismatch')
    item = next(x for x in entry['downloads']['chromedriver'] if x['platform'] == 'linux64')
    expected = f'https://storage.googleapis.com/chrome-for-testing-public/{version}/linux64/chromedriver-linux64.zip'
    if item['url'] != expected:
        raise ValueError('Driver download origin/path refused')
    return version, expected


def fetch(url):
    # Only used by prepare-browser, never by NavigationOnly against the application.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError('Unexpected download redirect')
    with urllib.request.build_opener(NoRedirect).open(url, timeout=60) as response:
        return response.read()


def prepare_browser(env):
    require_linux()
    chrome = Path('/usr/bin/google-chrome')
    if not chrome.is_file() or not os.access(chrome, os.X_OK):
        raise ValueError('Runner Google Chrome unavailable')
    chrome_version = executable_version(chrome)
    candidate = shutil.which('chromedriver')
    driver_version = executable_version(candidate) if candidate else ''
    driver = Path(candidate) if candidate else None
    if not candidate or build(driver_version) != build(chrome_version):
        driver_version, url = driver_download(json.loads(fetch(MANIFEST)), chrome_version)
        directory = Path(tempfile.mkdtemp(prefix='ui-ubuntu-driver-', dir=env['RUNNER_TEMP']))
        driver = directory / 'chromedriver'
        with zipfile.ZipFile(io.BytesIO(fetch(url))) as archive:
            # No extractall, traversal, scripts or arbitrary archive paths.
            content = archive.read('chromedriver-linux64/chromedriver')
        with driver.open('xb') as output:
            output.write(content)
        driver.chmod(0o700)
        if executable_version(driver) != driver_version:
            raise ValueError('Downloaded driver version mismatch')
    values = dict(UI_DIAGNOSTIC_CHROME_PATH=str(chrome), UI_DIAGNOSTIC_DRIVER_PATH=str(driver),
                  UI_DIAGNOSTIC_CHROME_VERSION=chrome_version, UI_DIAGNOSTIC_DRIVER_VERSION=driver_version)
    if any('\n' in v or '\r' in v for v in values.values()):
        raise ValueError('Unsafe browser metadata')
    with Path(env['GITHUB_ENV']).open('a') as output:
        for key, value in values.items():
            output.write(f'{key}={value}\n')
    print(f'Browser pair validated: Chrome {chrome_version}; ChromeDriver {driver_version}')


def execute(command, env, cwd):
    # No shell, no command/exception dumps. Private raw output is never an artifact.
    with tempfile.TemporaryFile(mode='w+b') as raw:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=raw, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait()
        except BaseException:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise
        raw.seek(0)
        return code, raw.read().decode('utf-8', errors='replace')


def diagnostics(raw, env):
    """Allowlist transport for already sanitized Java reports, not a second classifier."""
    headers = ('UI_NAVIGATION', 'UI_BROWSER_SESSION', 'UI_FAILURE_DIAGNOSTICS', 'UI_RENDER_READINESS', 'UI_NAVIGATION_ONLY_STAGE')
    allowed = {
        'expected', 'url', 'urlAfterGet', 'urlAfterWait', 'startedUtc', 'driverGetMs', 'totalMs',
        'commandOrReadinessFailed', 'classification', 'readyState', 'domElements', 'exactExpectedUrl',
        'expectedMarkersVisible', 'httpStatus', 'networkError', 'redirectCount', 'responseStartMs',
        'documentDurationMs', 'navigationType', 'chromeVersion', 'chromeDriverVersion', 'seleniumVersion',
        'javaVersion', 'os', 'osVersion', 'architecture', 'availableProcessors', 'jvmMaxHeapMiB', 'githubActions',
        'requestedHeadlessNew', 'requestedWindow', 'requestedDisableDevShmUsage', 'pageLoadStrategy',
        'implicitTimeoutMs', 'pageLoadTimeoutMs', 'scriptTimeoutMs', 'proxyType', 'acceptInsecureCerts',
        'proxyEnvironmentPresent', 'explicitDriverPropertyPresent', 'locatorKnown', 'matchingElements',
        'exceptionType', 'browser',
        'status', 'durationMs', 'phase',
    }
    route = re.compile(re.escape(ORIGIN) + r'/(?:login|register|tasks(?:/new|/[0-9]+/edit)?)?')
    output, active = [], False
    for line in redact(raw, env).splitlines():
        if line in headers:
            active = True
            output.append(line)
        elif re.fullmatch(r'UI_DIAGNOSTIC_RESULT mode=(NavigationOnly|UiSmoke) passed=[0-9]+ failed=[0-9]+ skipped=[0-9]+', line):
            output.append(line)
            active = False
        elif not line:
            if active:
                output.append('')
            active = False
        elif active and '=' in line:
            key, value = line.split('=', 1)
            if key not in allowed:
                continue
            if key in ('expected', 'url', 'urlAfterGet', 'urlAfterWait'):
                safe = bool(route.fullmatch(value) or re.fullmatch(r'\[(?:DATA_EMPTY|DATA_OTHER|ABOUT_BLANK|ABOUT_OTHER|CHROME_ERROR|CHROME_INTERNAL|UNAPPROVED_URL_OMITTED|UNAVAILABLE)\]', value))
            else:
                # No URLs, headers, quotes, brackets with private values or arbitrary free text.
                safe = bool(re.fullmatch(r'[A-Za-z0-9_.: -]{1,160}', value))
            if safe:
                output.append(line)
    return '\n'.join(output) + '\n'


def audit(directory, env):
    forms = secret_forms(env)
    count = 0
    for path in directory.rglob('*'):
        if path.is_symlink():
            raise ValueError('Artifact symlink refused')
        if not path.is_file() or path.suffix.lower() in ('.png', '.woff', '.woff2', '.ico'):
            continue
        text = path.read_bytes().decode('utf-8', errors='replace')
        count += 1
        if any(value in text for value in forms):
            raise ValueError('Artifact secret audit failed')
    if not count:
        raise ValueError('No textual artifacts')
    return count


def commands(root, scratch, mode, env):
    repo = Path.home() / '.m2/repository'
    plugin = 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:'
    import xml.etree.ElementTree as ET
    pom = ET.parse(root / 'pom.xml').getroot()
    aspect = pom.find('{*}properties/{*}aspectj.version').text
    if not re.fullmatch(r'[0-9.]+', aspect):
        raise ValueError('Invalid AspectJ version')
    maven = ['mvn', '-B', '-ntp', f'-Dmaven.repo.local={repo}']
    compile_command = [*maven, '-pl', 'ui-tests', '-am', 'test-compile']
    dependencies = [*maven, '-f', 'ui-tests/pom.xml', plugin + 'build-classpath',
                    f'-Dmdep.outputFile={scratch / "classpath.txt"}', plugin + 'get', f'-Dartifact=org.aspectj:aspectjweaver:{aspect}']
    agent = root / 'target/reporting-agent.jar'
    aspect_jar = repo / f'org/aspectj/aspectjweaver/{aspect}/aspectjweaver-{aspect}.jar'
    # Defer reading the classpath until after Maven has produced it.
    def launch():
        cp = os.pathsep.join((str(root / 'ui-tests/target/test-classes'), (scratch / 'classpath.txt').read_text().strip()))
        return ['java', f'-javaagent:{agent}={root},ui-tests', f'-javaagent:{aspect_jar}', '-cp', cp,
                'ru.tarkovskiy.ui.base.UbuntuDiagnosticLauncher', mode]
    return compile_command, dependencies, launch


def run(mode, env):
    require_linux()
    validate_settings(mode, env)
    for key in ('UI_DIAGNOSTIC_CHROME_VERSION', 'UI_DIAGNOSTIC_DRIVER_VERSION'):
        build(env.get(key, ''))
    if build(env['UI_DIAGNOSTIC_CHROME_VERSION']) != build(env['UI_DIAGNOSTIC_DRIVER_VERSION']):
        raise ValueError('Browser pair mismatch')
    if env.get('UI_DIAGNOSTIC_CHROME_PATH') != '/usr/bin/google-chrome':
        raise ValueError('Unexpected system Chrome')
    run_root = ROOT / 'test-runs' / env['TM_RUN_ID']
    if run_root.exists():
        raise ValueError('Run ID already exists; previous results preserved')
    output = run_root / 'ubuntu-diagnostic'
    output.mkdir(parents=True)
    code, report_code, stage = 1, 1, 'build'
    raw = ''
    try:
        with tempfile.TemporaryDirectory(prefix='ui-ubuntu-build-', dir=env['RUNNER_TEMP']) as temporary:
            compile_cmd, deps_cmd, launch = commands(ROOT, Path(temporary), mode, env)
            for command in (compile_cmd, deps_cmd):
                result, _ = execute(command, child_env(env), ROOT)
                if result:
                    code = result
                    raise RuntimeError('Build failed')
            stage = 'diagnostic'
            code, raw = execute(launch(), child_env(env, mode), ROOT)
    except Exception:
        code = code or 1
    finally:
        safe = diagnostics(raw, env)
        (output / 'diagnostics.log').write_text(safe)
        (output / 'status.json').write_text(json.dumps(dict(mode=mode, stage=stage, exitCode=code,
            os='Ubuntu 24.04', selectedChrome=env['UI_DIAGNOSTIC_CHROME_VERSION'],
            selectedChromeDriver=env['UI_DIAGNOSTIC_DRIVER_VERSION']), indent=2))
        part = run_root / 'ui-tests' / ('Smoke' if mode == 'UiSmoke' else 'NavigationOnly')
        results = part / 'allure-results'
        try:
            if results.is_dir():
                audit(results, env)
                shutil.copytree(results, output / 'allure-results')
                if (part / 'completed.marker').is_file():
                    shutil.copyfile(part / 'completed.marker', output / 'completed.marker')
                report_code, _ = execute(['allure', 'generate', str(output / 'allure-results'),
                                          '--output', str(output / 'allure-html')], child_env(env), ROOT)
            audit(output, env)
            (output / 'audited.marker').write_text('audited\n')
            # Emit only after audit; failed tests retain their nonzero exit status.
            print(safe, end='')
            print(f'Ubuntu diagnostic: mode={mode}; stage={stage}; exitCode={code}; allureExitCode={report_code}; artifact audited')
        except Exception:
            print('Ubuntu diagnostic: artifact audit/report preparation failed; upload refused')
            return 1
    return 0 if code == 0 and report_code == 0 else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare-browser')
    launch = sub.add_parser('run')
    launch.add_argument('--mode', choices=('NavigationOnly', 'UiSmoke'), required=True)
    args = parser.parse_args()
    os.umask(0o077)
    def cancelled(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, cancelled)
    try:
        if args.command == 'prepare-browser':
            prepare_browser(os.environ)
            return 0
        return run(args.mode, os.environ)
    except KeyboardInterrupt:
        print('Ubuntu diagnostic cancelled; child process group terminated')
        return 130
    except Exception:
        print('Ubuntu diagnostic failed: validate runner, TEST settings and browser setup; private details suppressed')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
