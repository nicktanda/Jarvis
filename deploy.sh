#!/bin/bash
# Build and deploy Adam APK to GitHub Releases
# Usage: ./deploy.sh

set -e

echo "Building APK..."
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
COMMIT=$(git rev-parse HEAD)
MESSAGE=$(git log -1 --pretty=%B)

echo "Uploading to GitHub release (commit: ${COMMIT:0:7})..."

# Delete existing 'latest' release and recreate
gh release delete latest --yes 2>/dev/null || true
gh release create latest "$APK" \
  --title "Latest Build" \
  --notes "Auto-built from commit $COMMIT
$MESSAGE" \
  --prerelease

echo "Done. Devices will pick up the update within an hour."
echo "Or tap 'Check for Update' in the app."
