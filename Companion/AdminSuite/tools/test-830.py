#!/usr/bin/env python3
import argparse,json,os,subprocess
from pathlib import Path
suites={'security':'AppApiSecurityHarness','api':'AppApiIntegrationHarness','release':'Release820Harness','outbox':'Outbox840Harness','database':'Database850Harness'}
parser=argparse.ArgumentParser(description='Run existing regression harnesses; default: all. SQL integration needs TEST_DATABASE=1.')
parser.add_argument('--suite',action='append',choices=suites,help='Select an affected area; repeat to combine. No assertions are disabled.')
args=parser.parse_args()
selected=args.suite or list(suites)
print('Selected suites: '+', '.join(selected),flush=True)
root=Path(__file__).resolve().parents[1];cache=root/'target/build-deps';lock=json.loads((root/'tools/build-dependencies.json').read_text())
cp=os.pathsep.join([str(root/'target/real-build/classes'),str(root/'src/main/resources')]+[str(cache/x['name']) for x in lock if not x['name'].startswith('ecj-')]);dest=root/'target/regression830';dest.mkdir(parents=True,exist_ok=True)
names=['Moderation790Harness','AppApiSecurityHarness','AppApiIntegrationHarness','Release820Harness','Outbox840Harness','Database850Harness']
subprocess.run(['java','-jar',str(cache/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',cp,'-d',str(dest),*[str(root/'tools/tests'/f'{n}.java') for n in names]],check=True)
version=next(line.split(':',1)[1].strip() for line in (root/'src/main/resources/plugin.yml').read_text().splitlines() if line.startswith('version:'))
runtime_cp=os.pathsep.join([str(root/f'target/SimplexAdminSuite-{version}.jar')]+[str(cache/x['name']) for x in lock if not x['name'].startswith(('ecj-','mariadb-','sqlite-'))])
for name in [suites[key] for key in dict.fromkeys(selected)]:
 subprocess.run(['java','-ea','-cp',str(dest)+os.pathsep+runtime_cp,'eu.simplexsmp.adminsuite.'+name],cwd=root,check=True)
