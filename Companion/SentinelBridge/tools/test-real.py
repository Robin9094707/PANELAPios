import os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1];deps=root/'target/build-deps';dest=root/'target/test-real';dest.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join([str(root/'target/real-build/classes'),str(deps/'Geyser-Spigot-2.12.1-1284.jar')])
subprocess.run(['java','-jar',str(deps/'ecj-3.46.0.jar'),'-17','-encoding','UTF-8','-warn:none','-cp',cp,'-d',str(dest),str(root/'tools/tests/RealApiBridgeHarness.java')],check=True)
subprocess.run([os.environ.get('JAVA','java'),'-ea','-cp',str(dest)+os.pathsep+cp,'RealApiBridgeHarness'],check=True)
