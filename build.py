#!/usr/bin/env python3
"""Build an offline native Android APK with the installed SDK; no Gradle or downloaded dependencies."""
from pathlib import Path
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parent
sdk = Path(os.environ.get("OPENLAMP_SDK", "/opt/android-sdk"))
tools = sdk / "build-tools" / "36.0.0"
android_jar = sdk / "platforms" / "android-36" / "android.jar"
build = root / "build"
classes = build / "classes"
dex = build / "dex"
keys = root / ".keys"
for directory in [build, classes, dex, keys]:
    directory.mkdir(parents=True, exist_ok=True)

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=root)

run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
    "-bootclasspath", str(tools / "core-lambda-stubs.jar") + os.pathsep + str(android_jar),
    "-d", classes, *sorted((root / "src").rglob("*.java")))
jar = build / "classes.jar"
with zipfile.ZipFile(jar, "w") as archive:
    for file in classes.rglob("*.class"):
        archive.write(file, file.relative_to(classes))
run(tools / "d8", "--min-api", "26", "--lib", android_jar, "--output", dex, jar)
unsigned = build / "unsigned.apk"
run(tools / "aapt2", "link", "-I", android_jar, "--manifest", root / "AndroidManifest.xml", "-o", unsigned)
with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(dex / "classes.dex", "classes.dex")
    for name in ["LICENSE", "NOTICE.md", "UPSTREAM-LICENSE"]:
        archive.write(root / name, "assets/" + name)
aligned = build / "aligned.apk"
run(tools / "zipalign", "-f", "4", unsigned, aligned)
keystore = keys / "debug.keystore"
if not keystore.exists():
    run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-dname", "CN=OpenLamp Local Debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650")
apk = build / "openlamp-local.apk"
run(tools / "apksigner", "sign", "--ks", keystore, "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", apk, aligned)
run(tools / "apksigner", "verify", "--verbose", apk)
print("APK:", apk)
