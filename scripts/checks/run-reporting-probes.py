"""Autonomous reporting checks only. No integration-test class is selected."""
from pathlib import Path
import os, subprocess, uuid, json, hashlib
root = Path(__file__).resolve().parents[2]
def native(path):
    if os.name != 'nt' and len(path) > 2 and path[1] == ':':
        return '/mnt/' + path[0].lower() + path[2:].replace('\\', '/')
    return path
agent = root / 'target/reporting-agent.jar'
ids = []
for repeat in range(2):
    run_id = 'selfcheck-' + uuid.uuid4().hex
    ids.append(run_id)
    for module in ['api-tests', 'ui-tests']:
        raw = (root / module / 'target/reporting-probe-classpath.txt').read_text().strip()
        deps = [native(p) for p in raw.split(';' if ';' in raw else os.pathsep)]
        aspect = next(p for p in deps if 'aspectjweaver' in p) if any('aspectjweaver' in p for p in deps) else str(Path(deps[0].replace('\\', '/').split('/repository/')[0]) / 'repository/org/aspectj/aspectjweaver/1.9.22.1/aspectjweaver-1.9.22.1.jar')
        env = {k:v for k,v in os.environ.items() if not k.startswith(('TEST_', 'TM_')) and k not in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']}
        env.update(TM_RUN_ID=run_id, TM_SUITE='Selected')
        result = subprocess.run(['java', f'-javaagent:{agent}={root},{module}', f'-javaagent:{aspect}', '-cp', os.pathsep.join([str(root/module/'target/test-classes'),*deps]), 'ru.tarkovskiy.reporting.ReportingProbe'], cwd=root, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
        print(result.stdout)
        if result.returncode: raise SystemExit(result.returncode)
    results = list((root/'test-runs'/run_id).glob('*/Selected/allure-results/*-result.json'))
    assert len(results) >= 6
    if repeat == 0:
        original = {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'test-runs'/run_id).rglob('*') if p.is_file()}
    else:
        assert all(hashlib.sha256(p.read_bytes()).hexdigest() == h for p,h in original.items())
        old_uuids = {json.loads(p.read_text())['uuid'] for p in (root/'test-runs'/ids[0]).glob('*/Selected/allure-results/*-result.json')}
        assert not old_uuids.intersection(json.loads(p.read_text())['uuid'] for p in results)
print('PASS: two independent run IDs; API/UI grouping; previous run hashes unchanged; no mixed UUIDs.')
print('Synthetic runs:', ', '.join(ids))
