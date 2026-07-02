#!/usr/bin/env bash
# Build TPC submission zip (and local release APK) for ATAK 5.6.0 only.
#
# The source zip gets atak.version=5.6.0 injected into gradle.properties inside
# the archive (required for TPC to compile against the correct ATAK SDK).
#
# Usage (from repo root):
#   ./tools/build-submission-zips.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"

echo "========== UV-PRO: ATAK 5.6.0 =========="
"${ROOT}/tools/use-atak-sdk.sh" 5.6.0
./gradlew :app:assembleCivRelease -Patak.version=5.6.0
ATAK_VERSION=5.6.0 "${ROOT}/tools/package-submission.sh"

echo ""
echo "Done. Submission zip is in Plugins/TAK Submissions/ (ATAK 5.6.0)."
