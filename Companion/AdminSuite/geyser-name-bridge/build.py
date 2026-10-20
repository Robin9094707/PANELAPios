#!/usr/bin/env python3
"""Build extension against genuine API bytecode extracted from official Geyser, never stubs."""
import argparse,hashlib,os,subprocess,urllib.request,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent; suite=root.parent
args=argparse.ArgumentParser();args.add_argument('--offline',action='store_true');args=args.parse_args()
api=root/'target/build-deps/geyser-api-2.11.3-build1251.jar';api.parent.mkdir(parents=True,exist_ok=True)
expected=(root/'api.sha256').read_text().strip()
if not api.exists() or hashlib.sha256(api.read_bytes()).hexdigest()!=expected:
 if args.offline:raise SystemExit('Missing verified genuine Geyser API cache')
 full=root/'target/Geyser-Spigot-build1251.jar'
 url='https://download.geysermc.org/v2/projects/geyser/versions/2.11.3/builds/1251/downloads/spigot'
 urllib.request.urlretrieve(url,full)
 if hashlib.sha256(full.read_bytes()).hexdigest()!='0bd00484f688c49e45f84f7c8bf9326f52d48a4c697d72a28526f2fe3f55dc36':raise SystemExit('Geyser checksum mismatch')
 with zipfile.ZipFile(full) as source,zipfile.ZipFile(api,'w',zipfile.ZIP_DEFLATED) as out:
  for name in source.namelist():
   if name.endswith('.class') and name.startswith(('org/geysermc/geyser/api/','org/geysermc/api/','org/geysermc/event/','org/geysermc/cumulus/')):out.writestr(name,source.read(name))
 if hashlib.sha256(api.read_bytes()).hexdigest()!=expected:raise SystemExit('Extracted API checksum mismatch')
classes=root/'target/classes';classes.mkdir(parents=True,exist_ok=True)
compiler=suite/'target/build-deps/ecj-3.46.0.jar';java=os.environ.get('JAVA','java')
subprocess.run([java,'-jar',str(compiler),'-17','-encoding','UTF-8','-warn:none','-cp',str(api),'-d',str(classes),*[str(p) for p in (root/'src/main/java').rglob('*.java')]],check=True)
jar=root/'target/SimplexNameBridge-1.0.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as out:
 out.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\nBuilt-Against: Geyser 2.11.3 build 1251\r\n\r\n')
 for base in [classes,root/'src/main/resources']:
  for path in sorted(base.rglob('*')):
   if path.is_file():out.write(path,path.relative_to(base).as_posix())
print('Built:',jar);print('SHA256:',hashlib.sha256(jar.read_bytes()).hexdigest())
