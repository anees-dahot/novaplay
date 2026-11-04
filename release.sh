#!/usr/bin/env bash
# Build signed APKs and publish a GitHub release. Phones running NovaPlay
# see the new version on next launch and can update in one tap.
#   ./release.sh 1.0.1 "What changed"
set -euo pipefail
VERSION="$1"; NOTES="${2:-NovaPlay v$VERSION}"
cd "$(dirname "$0")"
sed -i '' "s/^val appVersion = \".*\"/val appVersion = \"$VERSION\"/" app/build.gradle.kts
./gradlew assembleRelease --console=plain -q
OUT=app/build/outputs/apk/release; DIST=dist/v$VERSION
rm -rf "$DIST"; mkdir -p "$DIST"
for abi in arm64-v8a; do
  cp "$OUT/app-$abi-release.apk" "$DIST/NovaPlay-v$VERSION-$abi.apk"
done
git add -A && git commit -m "Release v$VERSION" || true
git tag "v$VERSION" && git push origin HEAD --tags
gh release create "v$VERSION" "$DIST"/*.apk --title "NovaPlay v$VERSION" --notes "$NOTES"
