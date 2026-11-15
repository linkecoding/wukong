#!/usr/bin/env python3
"""Create a local production identity once. Never writes secrets to stdout."""
import os, pathlib, secrets, subprocess
root = pathlib.Path(__file__).resolve().parents[1]
folder = pathlib.Path.home() / 'Library/Application Support/Wukong/signing'
folder.mkdir(parents=True, exist_ok=True, mode=0o700)
key = folder / 'wukong-release.p12'
config = root / 'signing.properties'
if key.exists() or config.exists():
    raise SystemExit('Signing material already exists; refusing to replace it.')
password = secrets.token_hex(32)
env = dict(os.environ, WUKONG_KEY_PASSWORD=password)
java = pathlib.Path(os.environ.get('JAVA_HOME', str(pathlib.Path.home() / 'Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home')))
result = subprocess.run([str(java/'bin/keytool'), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-storepass:env', 'WUKONG_KEY_PASSWORD', '-keypass:env', 'WUKONG_KEY_PASSWORD', '-alias', 'wukong', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000', '-dname', 'CN=Wukong, OU=Android, O=Wukong'], env=env, capture_output=True)
if result.returncode: raise SystemExit('keytool failed; no signing configuration was written.')
key.chmod(0o600)
content = f'storeFile={key}\nstorePassword={password}\nkeyAlias=wukong\nkeyPassword={password}\n'
for path in [folder/'signing.properties', config]:
    fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    with os.fdopen(fd,'w') as file: file.write(content)
print('Created production key and private signing configuration. Back up the signing folder securely.')
