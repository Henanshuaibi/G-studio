from pathlib import Path
import subprocess, zipfile, re, xml.etree.ElementTree as E
root=E.parse('AndroidManifest.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
version=root.attrib[a+'versionName']
apk=Path(f'build/G-studio-{version}.apk')
assert root.attrib['package']=='cn.g.studio'
assert root.attrib[a+'versionCode']=='9' and version=='0.9'
assert root.find('application').attrib[a+'label']=='G studio'
assert root.find('application').attrib[a+'allowBackup']=='false'
assert root.find('uses-sdk').attrib[a+'minSdkVersion']=='26'
assert {p.attrib[a+'name'] for p in root.findall('uses-permission')}=={'android.permission.INTERNET'}
with zipfile.ZipFile(apk) as z:
    assert {'classes.dex','AndroidManifest.xml','resources.arsc'}<=set(z.namelist())
    assert not any(n.endswith(('.keystore','.jks','.java')) or 'signing-password' in n for n in z.namelist())
    dex=z.read('classes.dex')
    assert Path('tools/signing-password').read_bytes().strip() not in dex
    assert b'test-secret-not-real' not in dex
certs=subprocess.check_output(['apksigner','verify','--print-certs',str(apk)],text=True)
cert=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',certs).group(1)
assert cert==Path('signing-certificate.sha256').read_text().strip()
for previous in [('0.1',1),('0.2',2),('0.3',3),('0.4',4),('0.5',5),('0.6',6),('0.7',7),('0.8',8)]:
    old=Path(f'../G-studio-{previous[0]}.apk')
    if not old.exists():continue
    old_certs=subprocess.check_output(['apksigner','verify','--print-certs',str(old)],text=True)
    assert re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',old_certs).group(1)==cert
    old_badging=subprocess.check_output(['aapt2','dump','badging',str(old)],text=True)
    assert f"package: name='cn.g.studio' versionCode='{previous[1]}'" in old_badging
badging=subprocess.check_output(['aapt2','dump','badging',str(apk)],text=True)
assert "package: name='cn.g.studio' versionCode='9' versionName='0.9'" in badging
assert "application-label:'G studio'" in badging
assert "launchable-activity: name='cn.g.studio.MainActivity'" in badging
assert re.findall(r"uses-permission: name='([^']+)'",badging)==['android.permission.INTERNET']
subprocess.run(['apksigner','verify','--verbose',str(apk)],check=True)
subprocess.run(['zipalign','-c','4',str(apk)],check=True)
print('PASS: APK identity, permissions, secret exclusion, signature, 0.1 through 0.8 update compatibility and alignment')
