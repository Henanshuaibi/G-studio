#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
gstudio_sdk=$(python3 -c 'import os;print(os.getenv("GSTUDIO_SDK_DIR","../pulse-player/tools"))')
gstudio_key=tools/g-studio.keystore
gstudio_password=tools/signing-password
for gstudio_file in "$gstudio_sdk/android.jar" "$gstudio_sdk/android-res.jar" "$gstudio_sdk/r8.jar" "$gstudio_key" "$gstudio_password" signing-certificate.sha256; do
    if [ ! -f "$gstudio_file" ]; then echo "缺少构建文件：$gstudio_file" >&2; exit 1; fi
done
gstudio_cert=$(keytool -exportcert -keystore "$gstudio_key" -storepass:file "$gstudio_password" -alias gstudio 2>/dev/null | sha256sum | cut -d ' ' -f 1)
if [ "$gstudio_cert" != "$(cat signing-certificate.sha256)" ]; then echo '签名指纹不匹配，停止构建。' >&2; exit 1; fi
gstudio_version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"])')
mkdir -p build/generated build/classes build/dex
find build/classes -type f -name '*.class' -delete
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$gstudio_sdk/android-res.jar" --manifest AndroidManifest.xml --java build/generated -o build/resources.apk build/res.zip
javac --release 8 -encoding UTF-8 -classpath "$gstudio_sdk/android.jar" -d build/classes src/cn/g/studio/*.java build/generated/cn/g/studio/R.java
jar cf build/classes.jar -C build/classes .
java -Xmx512m -cp "$gstudio_sdk/r8.jar" com.android.tools.r8.D8 --lib "$gstudio_sdk/android.jar" --min-api 26 --output build/dex build/classes.jar
cp build/resources.apk build/unsigned.apk
jar uf build/unsigned.apk -C build/dex classes.dex
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$gstudio_key" --ks-pass "file:$gstudio_password" --out "build/G-studio-$gstudio_version.apk" build/aligned.apk
apksigner verify --verbose "build/G-studio-$gstudio_version.apk"
zipalign -c 4 "build/G-studio-$gstudio_version.apk"
