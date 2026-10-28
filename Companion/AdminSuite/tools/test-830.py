#!/usr/bin/env python3
import json,os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1];cache=root/'target/build-deps';lock=json.loads((root/'tools/build-dependencies.json').read_text())
cp=os.pathsep.join([str(root/'target/real-build/classes'),str(root/'src/main/resources')]+[str(cache/x['name']) for x in lock if not x['name'].startswith('ecj-')]);dest=root/'target/regression830';dest.mkdir(parents=True,exist_ok=True)
names=['Moderation790Harness','AppApiSecurityHarness','AppApiIntegrationHarness','Release820Harness','Outbox840Harness','Database850Harness','PluginFiles860Harness','Security900Harness','Release910Harness','Release912Harness','Graph912Harness']
subprocess.run(['java','-jar',str(cache/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',cp,'-d',str(dest),*[str(root/'tools/tests'/f'{n}.java') for n in names]],check=True)
version=next(line.split(':',1)[1].strip() for line in (root/'src/main/resources/plugin.yml').read_text().splitlines() if line.startswith('version:'))
runtime_cp=os.pathsep.join([str(root/f'target/SimplexAdminSuite-{version}.jar')]+[str(cache/x['name']) for x in lock if not x['name'].startswith(('ecj-','mariadb-','sqlite-'))])
for name in ['AppApiSecurityHarness','AppApiIntegrationHarness','Release820Harness','Outbox840Harness','Database850Harness','PluginFiles860Harness','Security900Harness','Release910Harness','Release912Harness','Graph912Harness']:
 subprocess.run(['java','-ea','-cp',str(dest)+os.pathsep+runtime_cp,'eu.simplexsmp.adminsuite.'+name],cwd=root,check=True)
