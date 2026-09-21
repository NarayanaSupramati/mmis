"""Compare Git snapshots with Go's Hash1, without editing module locks.

Hash input uses git archive bytes, not Windows working-tree line endings.
For repos with nested modules/vendor/export attributes use Go's module zip tool
to independently verify a candidate before claiming an exact reconstruction.
"""
import base64
import hashlib
import io
import json
import subprocess
import sys
import zipfile

repo, module, version = sys.argv[1:4]
def git(*args):
    return subprocess.check_output(['git', '-C', repo, *args], stderr=subprocess.DEVNULL)
def hash1(files):
    data = ''.join(hashlib.sha256(content).hexdigest() + '  ' + name + '\n'
                   for name, content in sorted(files.items())).encode()
    return 'h1:' + base64.b64encode(hashlib.sha256(data).digest()).decode()
def snapshot(rev):
    archive = zipfile.ZipFile(io.BytesIO(git('-c', 'core.autocrlf=false', 'archive', '--format=zip', rev)))
    files = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    return {'revision': rev, 'files': len(files), 'sum': hash1({module + '@' + version + '/' + n: b for n,b in files.items()}),
            'goModSum': hash1({'go.mod': files['go.mod']}) if 'go.mod' in files else None}
if len(sys.argv) > 4 and sys.argv[4] == '--zip':
    with zipfile.ZipFile(sys.argv[5]) as archive:
        files = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    print(json.dumps({'archive': sys.argv[5], 'files': len(files), 'sum': hash1(files),
                      'goModSum': hash1({'go.mod': files[module + '@' + version + '/go.mod']})}))
elif len(sys.argv) > 4 and sys.argv[4] == '--find-mod':
    target = sys.argv[5]
    for rev in git('log', '--all', '--format=%H', '--', 'go.mod').decode().split():
        try:
            if hash1({'go.mod': git('show', rev + ':go.mod')}) == target:
                print(json.dumps(snapshot(rev)))
        except subprocess.CalledProcessError:
            pass
else:
    for rev in sys.argv[4:]:
        print(json.dumps(snapshot(rev)))
