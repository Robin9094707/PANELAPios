"""Package complete, reproducible plugin source without build caches or server data."""
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parents[1]
version = next(line.split(':', 1)[1].strip() for line in (root / 'src/main/resources/plugin.yml').read_text().splitlines() if line.startswith('version:'))
output = root / f'target/SimplexAdminSuite-{version}-source.zip'
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if path.is_file() and not any(part in {'target', '.git', '__MACOSX', '.DS_Store', '__pycache__'} or part.startswith('._') for part in relative.parts):
            archive.write(path, f'SimplexAdminSuite-{version}/{relative.as_posix()}')
print(output)
