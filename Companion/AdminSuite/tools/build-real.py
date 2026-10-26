#!/usr/bin/env python3
"""Reproducible release build: genuine Paper API, pinned dependency hashes; no stubs."""
import argparse, hashlib, json, os, shutil, subprocess, urllib.request, zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--offline',action='store_true',help='Use already cached, verified dependencies only')
args=parser.parse_args()
cache=root/'target/build-deps';cache.mkdir(parents=True,exist_ok=True)
lock=json.loads((root/'tools/build-dependencies.json').read_text())
for item in lock:
    path=cache/item['name']
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:
        if args.offline:raise SystemExit('Missing verified dependency: '+item['name'])
        print('Fetching',item['name'],flush=True)
        request=urllib.request.Request(item['url'],headers={'User-Agent':'SimplexAdminSuite-Build/7.5'})
        with urllib.request.urlopen(request,timeout=90) as response:data=response.read()
        if hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('Checksum mismatch: '+item['name'])
        path.write_bytes(data)
    if not zipfile.is_zipfile(path):raise SystemExit('Invalid JAR: '+item['name'])
classes=root/'target/real-build/classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
classpath=os.pathsep.join(str(cache/item['name']) for item in lock if not item['name'].startswith('ecj-'))
java=os.environ.get('JAVA','java')
command=[java,'-jar',str(cache/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',classpath,'-d',str(classes)]
command.extend(str(p) for p in sorted((root/'src/main/java').rglob('*.java')))
subprocess.run(command,check=True)
version=next(line.split(':',1)[1].strip() for line in (root/'src/main/resources/plugin.yml').read_text().splitlines() if line.startswith('version:'))
jar=root/f'target/SimplexAdminSuite-{version}.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
    archive.writestr('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\r\nImplementation-Title: Simplex Admin Suite\r\nImplementation-Version: {version}\r\nBuilt-Against: Paper 26.3.build.180-stable\r\n\r\n')
    with zipfile.ZipFile(cache/'sqlite-jdbc-3.53.4.0.jar') as sqlite:
        for name in sqlite.namelist():
            if name.startswith('org/sqlite/native/') and not any(name.startswith('org/sqlite/native/'+platform+'/') for platform in ['Linux/x86_64','Linux/aarch64','Linux-Musl/x86_64','Linux-Musl/aarch64','Windows/x86_64','Windows/aarch64','Mac/x86_64','Mac/aarch64']): continue
            if name.startswith('META-INF/') and (name.endswith(('.SF','.RSA','.DSA')) or name=='META-INF/MANIFEST.MF'): continue
            if name.endswith('module-info.class') or name.endswith('/'): continue
            archive.writestr(name,sqlite.read(name))
    with zipfile.ZipFile(cache/'mariadb-java-client-3.5.11.jar') as driver:
        for name in driver.namelist():
            if name.endswith('/') or name.endswith('module-info.class'): continue
            if name.startswith('META-INF/') and not name.startswith('META-INF/services/org.mariadb.jdbc.'): continue
            archive.writestr(name,driver.read(name))
    for base in [classes,root/'src/main/resources']:
        for path in sorted(base.rglob('*')):
            if path.is_file():archive.write(path,path.relative_to(base).as_posix())
with jar.open('rb') as output: os.fsync(output.fileno())
print('Built:',jar)
print('SHA256:',hashlib.sha256(jar.read_bytes()).hexdigest())
