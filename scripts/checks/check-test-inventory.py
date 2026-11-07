"""Offline inventory: executes only array-returning DataProviders, never tests/fixtures."""
from pathlib import Path
import subprocess,os,argparse
p=argparse.ArgumentParser();p.add_argument('--java',required=True);o=p.parse_args()
root=Path(__file__).resolve().parents[2]
def win(p):
 s=str(p).replace('\\','/')
 return s[5].upper()+':/'+s[7:] if s.startswith('/mnt/') else s
env={k:v for k,v in os.environ.items() if not k.startswith(('TEST_','TM_')) and k not in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']}
env['TEST_USER_PASSWORD']='synthetic-inventory-only'
if os.name!='nt': env['WSLENV']=env.get('WSLENV','')+':TEST_USER_PASSWORD'
for module,expected,package,names in [('api-tests',51,'api',['DbConnectionTest','LoginTest','RegistrationTest','TaskApiTest']),('ui-tests',66,'ui',['LoginUiTest','RegistrationUiTest','TaskUiTest'])]:
 discovered={f.stem for f in (root/module/'src/test/java'/('ru/tarkovskiy/'+package+'/tests')).glob('*.java') if '@Test(' in f.read_text()}
 assert discovered==set(names), 'Business test class inventory changed'
 raw=(root/module/'target/reporting-probe-classpath.txt').read_text().strip()
 cp=win(root/module/'target/test-classes')+';'+raw
 args=[o.java,'-cp',cp,win(root/'scripts/checks/TestInventoryProbe.java'),str(expected),*[f'ru.tarkovskiy.{package}.tests.{n}' for n in names]]
 r=subprocess.run(args,cwd=root,env=env,text=True,encoding='utf-8',errors='replace',stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
 print(r.stdout)
 if r.returncode:raise SystemExit(r.returncode)
