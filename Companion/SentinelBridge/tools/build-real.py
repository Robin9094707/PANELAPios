#!/usr/bin/env python3
"""Compile against the genuine checksum-pinned Geyser distribution. No API stubs in releases."""
import argparse,hashlib,json,os,shutil,subprocess,urllib.request,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--offline',action='store_true');args=p.parse_args()
cache=root/'target/build-deps';cache.mkdir(parents=True,exist_ok=True)
lock=json.loads((root/'tools/build-dependencies.json').read_text())
for item in lock:
 path=cache/item['name']
 if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:
  if args.offline: raise SystemExit('Missing verified dependency: '+item['name'])
  data=urllib.request.urlopen(item['url'],timeout=90).read()
  if hashlib.sha256(data).hexdigest()!=item['sha256']: raise SystemExit('Checksum mismatch: '+item['name'])
  path.write_bytes(data)
classes=root/'target/real-build/classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
subprocess.run([os.environ.get('JAVA','java'),'-jar',str(cache/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',str(cache/'Geyser-Spigot-2.12.1-1284.jar'),'-d',str(classes),*[str(p) for p in (root/'src/main/java').rglob('*.java')]],check=True)
version=next(line.split(':',1)[1].strip() for line in (root/'src/main/resources/extension.yml').read_text().splitlines() if line.startswith('version:'))
jar=root/f'target/SimplexSentinelBridge-{version}.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\r\nImplementation-Version: {version}\r\nBuilt-Against: Geyser 2.12.1 build 1284\r\n\r\n')
 for base in [classes,root/'src/main/resources']:
  for p in base.rglob('*'):
   if p.is_file(): z.write(p,p.relative_to(base).as_posix())
print('Built:',jar,'SHA256:',hashlib.sha256(jar.read_bytes()).hexdigest())
