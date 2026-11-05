#!/bin/sh
# Build an installable preview ZIP without downloading the Rider SDK.
# Offline IDE signatures are ONLY compile-time stubs and are NEVER distributed.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD="$ROOT/build"
OUT="$BUILD/offline-export"
VERSION=1.1.1
# Do not update an existing ZIP in place: zip(1) keeps removed/stale entries.
rm -f "$ROOT/IDEle-$VERSION.zip"
# Always remove previous compiled output: renamed/removed classes must not survive.
rm -rf "$BUILD/offline-stubs" "$BUILD/classes/java/main" \
       "$BUILD/classes/java/test" "$OUT"
mkdir -p "$BUILD/offline-stubs" "$BUILD/classes/java/main" \
         "$BUILD/classes/java/test" "$OUT/IDEle/lib"
find "$ROOT/offline-stubs" -type f -name '*.java' | sort > "$BUILD/offline-stubs.lst"
find "$ROOT/src/main/java" -type f -name '*.java' | sort > "$BUILD/main-sources.lst"
find "$ROOT/src/test/java" -type f -name '*.java' | sort > "$BUILD/test-sources.lst"
javac --release 21 -encoding UTF-8 -d "$BUILD/offline-stubs" @"$BUILD/offline-stubs.lst"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$BUILD/offline-stubs" \
  -d "$BUILD/classes/java/main" @"$BUILD/main-sources.lst"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$BUILD/classes/java/main" \
  -d "$BUILD/classes/java/test" @"$BUILD/test-sources.lst"
java -Djava.awt.headless=true \
  -cp "$BUILD/classes/java/main:$BUILD/classes/java/test" \
  dev.riderlan.core.IntegrationTest
jar --create --file "$OUT/IDEle/lib/idele.jar" \
  -C "$BUILD/classes/java/main" . -C "$ROOT/src/main/resources" .
(cd "$OUT" && zip -q -r "$ROOT/IDEle-$VERSION.zip" IDEle)
jar --list --file "$OUT/IDEle/lib/idele.jar" | grep -q '^META-INF/plugin.xml$'
if jar --list --file "$OUT/IDEle/lib/idele.jar" | grep -q '^com/intellij/'; then
  echo 'ERROR: stubbed Rider/IntelliJ classes must not be distributed.' >&2
  exit 1
fi
printf '\nUnsigned preview ZIP (actual Rider SDK not verified): %s\n' "$ROOT/IDEle-$VERSION.zip"
