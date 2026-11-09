"""JDK-only Windows agent startup checks; no TestNG, browser, HTTP or JDBC."""
from pathlib import Path
import os, subprocess, uuid, argparse
p=argparse.ArgumentParser();p.add_argument('--java',default='java');o=p.parse_args()
root=Path(__file__).resolve().parents[2]
windows=o.java.endswith('.exe') or os.name=='nt'
def host(path):
 s=str(path)
 if windows and s.startswith('/mnt/'):
  return s[5].upper()+':/'+s[7:]
 return s.replace('\\','/')
jar=root/'target/reporting-agent.jar'
raw=(root/'ui-tests/target/reporting-probe-classpath.txt').read_text().strip().replace('\\','/')
repo=raw.split(';')[0].split('/repository/')[0]+'/repository'
aspect=repo+'/org/aspectj/aspectjweaver/1.9.22.1/aspectjweaver-1.9.22.1.jar'
env={k:v for k,v in os.environ.items() if not k.startswith(('TEST_','TM_')) and k not in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']}
def invoke(args):
 r=subprocess.run([o.java,*args],env=env,cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
 if r.returncode: raise AssertionError(r.stdout)
 print(r.stdout.strip());return r.stdout.strip().split()[-1] if r.stdout.strip() else None
def start(module, session=None):
 options=host(root)+','+module+(',maven='+session if session else '')
 return invoke(['-javaagent:'+host(jar)+'='+options,'-javaagent:'+aspect,'-cp',host(root/module/'target/test-classes'),host(root/'scripts/checks/AgentStartupProbe.java'),module])
ids=[start(m) for m in ['ui-tests','api-tests','ui-tests','api-tests']]
assert len(set(ids))==4
key='startup-'+uuid.uuid4().hex
invoke(['-cp',host(jar),'ru.tarkovskiy.reporting.bootstrap.IsolatedRunAgent',host(root),key])
a=start('api-tests',key);b=start('ui-tests',key);assert a==b
print('PASS: four direct starts have unique IDs; explicit Maven session groups API/UI; both agents loaded from JARs.')

# Compile only two JDK-only probe mains, including a stub with IDEA's launcher name.
classes=root/'target/agent-startup-probe'
classes.mkdir(parents=True,exist_ok=True)
javac=o.java.replace('java.exe','javac.exe') if windows else 'javac'
r=subprocess.run([javac,'-d',host(classes),host(root/'scripts/checks/AgentStartupProbe.java'),host(root/'scripts/checks/RemoteTestNGStarter.java')],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
assert r.returncode==0,r.stdout
for module in ['ui-tests','api-tests']:
 base=host(root)+','+module
 invoke(['-javaagent:'+host(jar)+'='+base+',maven=nonexistent-idea-session',
         '-javaagent:'+host(jar)+'='+base, '-javaagent:'+aspect,
         '-cp',host(root/module/'target/test-classes')+(';' if windows else ':')+host(classes),
         'com.intellij.rt.testng.RemoteTestNGStarter',module])
print('PASS: IDEA launcher ignores inherited Maven timestamp; duplicate reporting agent is idempotent.')
