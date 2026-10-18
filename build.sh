#!/bin/bash
# ============================================================================
# 泉益通 KT-10R 写频助手 - 手动构建脚本 (无 Gradle)
# 依赖: JDK 21 + Android SDK (platform 34 + build-tools 34.0.0)
# ============================================================================
set -e

APP_HOME="C:/Users/Administrator/kt10r-build"
SDK="C:/Users/Administrator/androidsdk"
JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.9.10-hotspot"
OUT="$APP_HOME/out"
KEYSTORE="$APP_HOME/debug.keystore"

export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"

AAPT2="$SDK/build-tools/36.0.0/aapt2.exe"
D8="$SDK/build-tools/36.0.0/d8.bat"
APKSIGNER="$SDK/build-tools/36.0.0/apksigner.bat"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
ZIPALIGN="$SDK/build-tools/36.0.0/zipalign.exe"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "==> [1/7] 编译资源 (aapt2 compile)"
"$AAPT2" compile --dir "$APP_HOME/src/main/res" -o "$OUT/res.zip"

echo "==> [2/7] 链接资源+清单 (aapt2 link)"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP_HOME/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res.zip"

echo "==> [3/7] 编译 Java (javac)"
find "$APP_HOME/src/main/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -nowarn \
    -encoding UTF-8 \
    -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "==> [4/7] DEX 化 (d8)"
cd "$OUT/classes"
jar cf "$OUT/classes.jar" .
cd "$OUT"
"$D8" --release --lib "$ANDROID_JAR" --min-api 21 \
    --output "$OUT/dex" \
    "$OUT/classes.jar"

echo "==> [5/7] 打包 APK"
cd "$OUT"
cp base.apk app-unsigned.apk
cd "$OUT/dex"
jar uf "$OUT/app-unsigned.apk" classes.dex
cd "$OUT"

echo "==> [6/7] 生成调试签名"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android \
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> [7/7] 签名 + 对齐"
"$ZIPALIGN" -f 4 app-unsigned.apk app-aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --out "$APP_HOME/KT10R.apk" app-aligned.apk

echo ""
echo "构建完成: $APP_HOME/KT10R.apk"
ls -la "$APP_HOME/KT10R.apk"
"$APKSIGNER" verify --print-certs "$APP_HOME/KT10R.apk" | head -8
