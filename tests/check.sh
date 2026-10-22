#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")/.."
gstudio_sdk=$(python3 -c 'import os;print(os.getenv("GSTUDIO_SDK_DIR","../pulse-player/tools"))')
mkdir -p build/test-classes build/test-dex
find build/test-classes -type f -name "*.class" -delete
javac --release 8 -encoding UTF-8 -classpath "$gstudio_sdk/android.jar:build/classes" -d build/test-classes tests/*.java
jar cf build/tests.jar -C build/test-classes .
[ ! -f build/test-dex/classes.dex ] || chmod 600 build/test-dex/classes.dex
java -Xmx512m -cp "$gstudio_sdk/r8.jar" com.android.tools.r8.D8 --lib "$gstudio_sdk/android.jar" --min-api 26 --output build/test-dex build/classes.jar build/tests.jar
chmod 400 build/test-dex/classes.dex
CLASSPATH="$PWD/build/test-dex/classes.dex" /system/bin/app_process /system/bin cn.g.studio.CoreCheck "$PWD/build"
python3 tests/package-check.py
