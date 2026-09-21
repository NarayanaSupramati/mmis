"""Inventory local gomobile outputs; compare logical ZIP entries including nested classes.jar."""
import hashlib, io, json, pathlib, re, sys, zipfile

root = pathlib.Path(sys.argv[1])
sha = lambda b: hashlib.sha256(b).hexdigest()
def entries(data, prefix=''):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('/'): continue
            content = archive.read(name)
            if name.endswith('.jar'):
                result.extend(entries(content, prefix + name + '!/'))
            else:
                result.append({'path': prefix + name, 'bytes': len(content), 'sha256': sha(content)})
    return result

manifest = {'artifacts': {}}
for name in ['bindings.aar', 'bindings-sources.jar']:
    data = (root/name).read_bytes()
    listing = entries(data)
    manifest['artifacts'][name] = {'bytes': len(data), 'sha256': sha(data), 'entries': listing,
        'normalizedSha256': sha(json.dumps(listing, sort_keys=True, separators=(',', ':')).encode())}
with zipfile.ZipFile(root/'bindings.aar') as aar:
    manifest['androidManifest'] = aar.read('AndroidManifest.xml').decode()
    manifest['proguard'] = aar.read('proguard.txt').decode()
    manifest['abis'] = sorted({n.split('/')[1] for n in aar.namelist() if n.startswith('jni/') and n.endswith('.so')})
    for name in aar.namelist():
        if name.startswith('jni/') and name.endswith('.so'):
            dest = root/'inspection'/name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(aar.read(name))
with zipfile.ZipFile(root/'bindings-sources.jar') as sources:
    manifest['javaSources'] = sorted(n for n in sources.namelist() if n.endswith('.java'))
    api = {}
    for cls in ['Bindings', 'Cmix', 'DMClient', 'DMReceiver', 'DMReceiverBuilder', 'DmCallbacks', 'TimeSource', 'Notifications']:
        source = sources.read('bindings/'+cls+'.java').decode('utf8')
        # One tab is the outer type; two tabs belong to generated JNI proxies.
        api[cls] = [s.strip() for s in source.splitlines() if re.match(r'\tpublic .*\(', s)]
    (root/'api-inventory.json').write_text(json.dumps(api, indent=2)+'\n', encoding='utf8')
(root/'aar-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
if len(sys.argv)>2:
    other = json.loads((pathlib.Path(sys.argv[2])/'aar-manifest.json').read_text())
    comparison = {}
    for name, current in manifest['artifacts'].items():
        prior = other['artifacts'][name]
        comparison[name] = {'byteIdentical': current['sha256']==prior['sha256'],
            'contentIdentical': current['normalizedSha256']==prior['normalizedSha256'],
            'differences': sorted({f['path'] for f in current['entries'] if f not in prior['entries']} |
                                  {f['path'] for f in prior['entries'] if f not in current['entries']})}
    (root/'reproducibility.json').write_text(json.dumps(comparison, indent=2)+'\n')
    print(json.dumps(comparison))
print(json.dumps({'abis': manifest['abis'], 'artifacts': {n: {k:v for k,v in a.items() if k!='entries'} for n,a in manifest['artifacts'].items()}}))
