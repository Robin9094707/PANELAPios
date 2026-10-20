#!/usr/bin/env python3
import json,os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1];cache=root/'target/build-deps';lock=json.loads((root/'tools/build-dependencies.json').read_text())
cp=os.pathsep.join([str(root/'target/real-build/classes'),str(root/'src/main/resources')]+[str(cache/x['name']) for x in lock if not x['name'].startswith('ecj-')]);dest=root/'target/regression830';dest.mkdir(parents=True,exist_ok=True)
names=['Moderation790Harness','AppApiSecurityHarness','AppApiIntegrationHarness','Release820Harness']
subprocess.run(['java','-jar',str(cache/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',cp,'-d',str(dest),*[str(root/'tools/tests'/f'{n}.java') for n in names]],check=True)
for name in ['AppApiSecurityHarness','AppApiIntegrationHarness','Release820Harness']:
 subprocess.run(['java','-ea','-cp',str(dest)+os.pathsep+cp,'eu.simplexsmp.adminsuite.'+name],cwd=root,check=True)
