#!/usr/bin/env bash
# Builds a native app bundle with jpackage, one per OS.
#
# `mvn package` produces target/gesturtle-*.jar with a Class-Path manifest
# pointing at target/libs/*.jar (staged there by maven-dependency-plugin).
# jpackage bundles that jar plus a full JDK runtime image and runs it via
# the classpath, so this works without needing javafx jmods.
#
# Usage: ./jpackage.sh [jpackage-type]
#   jpackage-type defaults to "app-image" (a runnable app folder, no
#   installer, works on every platform with no extra tooling). Pass an
#   installer type your platform supports instead, e.g.:
#     Linux:   deb | rpm
#     macOS:   dmg | pkg
#     Windows: msi | exe
set -euo pipefail
cd "$(dirname "$0")"

TYPE="${1:-app-image}"
APP_NAME="Gesturtle"
# jpackage copies everything under --input into the app image, so only the
# jar and libs/ are staged there; passing target/ itself would ship
# test-classes/, surefire-reports/ and the rest of the build's leftovers.
STAGE="target/jpackage-input"
# Must NOT be nested inside the --input folder: jpackage would keep
# re-discovering and re-copying its own output as it writes it.
DEST="dist"

echo "==> mvn clean package"
# clean, so target/libs holds only this version's dependencies (copy-dependencies never removes old jars).
mvn -q clean package -DskipTests

MAIN_JAR=$(find target -maxdepth 1 -name 'gesturtle-*.jar' ! -name '*sources*' | head -1)
if [ -z "$MAIN_JAR" ]; then
  echo "Could not find target/gesturtle-*.jar. Did 'mvn package' succeed?" >&2
  exit 1
fi

VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]*)<\/version>.*/\1/' | sed -E 's/-.*//')
# jpackage wants plain numbers, so a "-beta" or "-SNAPSHOT" suffix is dropped.
[ -z "$VERSION" ] && VERSION="1.0.0"

# Each OS wants its own icon format; all three come from packaging/MakeIcons.java.
case "$(uname -s)" in
  Darwin) ICON="packaging/gesturtle.icns" ;;
  MINGW*|MSYS*|CYGWIN*) ICON="packaging/gesturtle.ico" ;;
  *) ICON="packaging/gesturtle.png" ;;
esac

echo "==> jpackage --type $TYPE (app version $VERSION)"
rm -rf "$DEST" "$STAGE"
mkdir -p "$DEST" "$STAGE"
cp "$MAIN_JAR" "$STAGE/"
# GPL: the license travels with every copy.
cp LICENSE "$STAGE/"
cp -r target/libs "$STAGE/libs"

jpackage \
  --input "$STAGE" \
  --main-jar "$(basename "$MAIN_JAR")" \
  --main-class com.gesturtle.Launcher \
  --name "$APP_NAME" \
  --app-version "$VERSION" \
  --icon "$ICON" \
  --dest "$DEST" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --type "$TYPE"

echo "==> done: $DEST"
