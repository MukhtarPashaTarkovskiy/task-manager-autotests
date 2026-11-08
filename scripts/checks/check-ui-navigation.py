"""Compile UI sources and run only UiNavigationProbe with fake WebDriver, offline.

Uses an already cached dependency classpath; never invokes Maven/TestNG or agents.
All compiler output and Allure attachments live in a new temporary directory.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--classpath-file', type=Path, default=root / 'ui-tests/target/reporting-probe-classpath.txt')
parser.add_argument('--jdk-home', type=Path, help='Java 17 home used for compiler API checking')
args = parser.parse_args()
raw = args.classpath_file.read_text().strip()


def native(value):
    if os.name != 'nt' and len(value) > 2 and value[1] == ':':
        return '/mnt/' + value[0].lower() + value[2:].replace('\\', '/')
    return value


jars = [native(p) for p in raw.split(';' if ';' in raw else os.pathsep)]
# Older reporting-probe caches predate the module's declared Jackson dependency.
# Use only the already cached, POM-pinned JARs; never resolve or download anything.
normalized = [p.replace('\\', '/') for p in jars]
repo = next((p.split('/repository/')[0] + '/repository' for p in normalized
             if '/repository/' in p), None)
if repo:
    for artifact in ['jackson-databind', 'jackson-core', 'jackson-annotations']:
        jar = str(Path(repo) / 'com/fasterxml/jackson/core' / artifact / '2.18.2' / (artifact + '-2.18.2.jar'))
        if jar not in jars:
            jars.append(jar)
if any(not Path(p).is_file() for p in jars):
    raise SystemExit('Cached dependency JAR missing; no downloads attempted.')
env = {k: v for k, v in os.environ.items()
       if not k.startswith(('TEST_', 'TM_')) and k not in
       ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS']}
env['TEST_USER_PASSWORD'] = 'OFFLINE_ONLY_<&>_secret+value'
sources = [*sorted((root / 'ui-tests/src/test/java').rglob('*.java')),
           *sorted((root / 'reporting/src/main/java').rglob('*.java')),
           root / 'scripts/checks/UiNavigationProbe.java']
with tempfile.TemporaryDirectory(prefix='ui-navigation-offline-') as scratch:
    cp = os.pathsep.join(jars)
    compiler = ['java', 'com.sun.tools.javac.Main', '-proc:none', '-encoding', 'UTF-8']
    compiler += ['-source', '17', '-target', '17', '--system', str(args.jdk_home)] if args.jdk_home else ['--release', '17']
    compiled = subprocess.run([*compiler, '-cp', cp, '-d', scratch, *map(str, sources)], env=env)
    if compiled.returncode:
        raise SystemExit(compiled.returncode)
    checked = subprocess.run(['java', '-Dallure.results.directory=' + str(Path(scratch) / 'allure'),
                    '-cp', os.pathsep.join([scratch, cp]), 'ru.tarkovskiy.ui.reporting.UiNavigationProbe'],
                   cwd=scratch, env=env, timeout=60)
    raise SystemExit(checked.returncode)
