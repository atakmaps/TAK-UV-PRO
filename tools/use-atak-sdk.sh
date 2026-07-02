#!/usr/bin/env bash
# Install ATAK 5.6.0 SDK jars into app/libs/atak-civ/ for local builds.
#
# Usage:
#   ./tools/use-atak-sdk.sh status
#   ./tools/use-atak-sdk.sh 5.6.0
#
# Override 5.6 SDK path:
#   ATAK_560_SDK=/path/to/ATAK-CIV-5.6.0.18-SDK ./tools/use-atak-sdk.sh 5.6.0
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${ROOT}/app/libs/atak-civ"
SDK_560="${ATAK_560_SDK:-${HOME}/Documents/ATAK/Versions/ATAK-CIV-5.6.0.18-SDK}"

usage() {
  cat <<'EOF'
Usage: ./tools/use-atak-sdk.sh <status|5.6.0>

  status   Show active SDK and configured 5.6.0 path
  5.6.0    Use ATAK-CIV-5.6.0.18-SDK main.jar + android_keystore
EOF
}

activate_560() {
  if [[ ! -f "${SDK_560}/main.jar" ]] || [[ ! -f "${SDK_560}/android_keystore" ]]; then
    echo "5.6 SDK not found at ${SDK_560} (need main.jar + android_keystore)" >&2
    exit 1
  fi
  mkdir -p "${LIB}"
  cp -f "${SDK_560}/main.jar" "${SDK_560}/android_keystore" "${LIB}/"
  echo "Active SDK: 5.6.0 (${SDK_560} -> ${LIB})"
}

sdk_fingerprint() {
  if [[ ! -f "${LIB}/main.jar" ]]; then
    echo "missing"
    return
  fi
  stat -c '%s' "${LIB}/main.jar" 2>/dev/null || stat -f '%z' "${LIB}/main.jar"
}

status() {
  local active_size sdk560_size
  active_size="$(sdk_fingerprint)"
  sdk560_size="$([[ -f "${SDK_560}/main.jar" ]] && stat -c '%s' "${SDK_560}/main.jar" 2>/dev/null || stat -f '%z' "${SDK_560}/main.jar" 2>/dev/null || echo missing)"
  echo "lib:     ${LIB}/main.jar (${active_size} bytes)"
  echo "5.6 SDK: ${SDK_560}/main.jar (${sdk560_size} bytes)"
  if [[ "${active_size}" == "${sdk560_size}" ]] && [[ "${active_size}" != "missing" ]]; then
    echo "active:  5.6.0"
  else
    echo "active:  unknown (run ./tools/use-atak-sdk.sh 5.6.0)"
  fi
}

cmd="${1:-5.6.0}"
case "${cmd}" in
  status) status ;;
  5.6.0|560) activate_560 ;;
  -h|--help|help) usage ;;
  *)
    echo "Unsupported ATAK version: ${cmd} (5.6.0 only)" >&2
    usage
    exit 1
    ;;
esac
