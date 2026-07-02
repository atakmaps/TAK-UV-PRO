#!/usr/bin/env bash
#
# TPP-style source archive — TPC portal requirements:
#   - Single root folder at zip root (name used for TPC-built APK labels)
#   - Gradle + gradlew at that root; target assembleCivRelease
#   - atak-gradle-takdev resolves SDK from TPC maven (vendored jar in gradle/takdev/)
#   - atak.version=<target> in gradle.properties tells TPC which ATAK SDK to compile against
#
# Layout:
#   - Zip name:  UV-PRO-<version>-ATAK-<atak>-source.zip
#   - Root folder:  UV-PRO-<version>/
#
# Outputs default to TAK Submissions under ATAK Plugins ($HOME/Documents/ATAK/...).
# Override: PLUGINS_DIR=/path ./tools/package-submission.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/.*ext\.PLUGIN_VERSION *= *"\([^"]*\)".*/\1/p' build.gradle | head -1)"
if [[ -z "${VERSION}" ]]; then
  echo "Could not read PLUGIN_VERSION from build.gradle" >&2
  exit 1
fi

# ATAK target for zip filename + gradle.properties injection.
# Must match the release APK you built. Prefer explicit ATAK_VERSION= from build-submission-zips.sh.
resolve_atak_version() {
  if [[ -n "${ATAK_VERSION:-}" ]]; then
    echo "${ATAK_VERSION}"
    return
  fi
  local apk
  shopt -s nullglob
  for apk in "${ROOT}"/app/build/outputs/apk/civ/release/ATAK-Plugin-UVPro-"${VERSION}"-*-civ-release.apk; do
    if [[ "${apk}" =~ -([0-9]+\.[0-9]+\.[0-9]+)-civ-release\.apk$ ]]; then
      echo "${BASH_REMATCH[1]}"
      return
    fi
  done
  shopt -u nullglob
  echo "5.6.0"
}
ATAK_VER="$(resolve_atak_version)"

TPP_ROOT="${TPP_ROOT:-UV-PRO-${VERSION}}"
SHA="$(git rev-parse --short HEAD)"
FULLSHA="$(git rev-parse HEAD)"
STAMP="$(date -u +%Y%m%dT%H%MZ)"

PLUGINS_DIR="${PLUGINS_DIR:-${HOME}/Documents/ATAK/Plugins/TAK Submissions}"
mkdir -p "${PLUGINS_DIR}"

SOURCE_ZIP="UV-PRO-${VERSION}-ATAK-${ATAK_VER}-source.zip"
SOURCE_PATH="${PLUGINS_DIR}/${SOURCE_ZIP}"

git archive --format=zip --prefix="${TPP_ROOT}/" -o "${SOURCE_PATH}" HEAD

# TPC compiles from the archived source only — inject ATAK target so devkitVersion
# matches the zip name (build.gradle defaults to 5.6.0 without this).
inject_atak_version_into_zip() {
  local zip_path="$1" tpp_root="$2" atak_ver="$3"
  local tmpdir gradle_props
  tmpdir="$(mktemp -d)"
  unzip -q "${zip_path}" -d "${tmpdir}"
  gradle_props="${tmpdir}/${tpp_root}/gradle.properties"
  if [[ ! -f "${gradle_props}" ]]; then
    echo "Missing ${tpp_root}/gradle.properties in submission zip" >&2
    rm -rf "${tmpdir}"
    exit 1
  fi
  grep -v '^atak\.version=' "${gradle_props}" > "${gradle_props}.tmp" || true
  mv "${gradle_props}.tmp" "${gradle_props}"
  printf '\n# TPC build target (set by tools/package-submission.sh)\natak.version=%s\n' "${atak_ver}" >> "${gradle_props}"
  rm -f "${zip_path}"
  (cd "${tmpdir}" && zip -qr "${zip_path}" "${tpp_root}")
  rm -rf "${tmpdir}"
}
inject_atak_version_into_zip "${SOURCE_PATH}" "${TPP_ROOT}" "${ATAK_VER}"

verify_submission_zip() {
  local zip_path="$1" tpp_root="$2" atak_ver="$3"
  local actual
  actual="$(unzip -p "${zip_path}" "${tpp_root}/gradle.properties" | grep '^atak\.version=' | head -1 | cut -d= -f2- | tr -d '\r')"
  if [[ "${actual}" != "${atak_ver}" ]]; then
    echo "ERROR: ${zip_path} has atak.version=${actual:-<missing>}, expected ${atak_ver}" >&2
    exit 1
  fi
  echo "Verified TPC target in zip: ${tpp_root}/gradle.properties atak.version=${atak_ver}"
}
verify_submission_zip "${SOURCE_PATH}" "${TPP_ROOT}" "${ATAK_VER}"

APK=""
shopt -s nullglob
for f in "${ROOT}"/app/build/outputs/apk/civ/release/ATAK-Plugin-UVPro-"${VERSION}"-*-"${ATAK_VER}"-civ-release.apk; do
  APK="$f"
  break
done
if [[ -z "${APK}" ]]; then
  echo "WARNING: no ${ATAK_VER} release APK found; run assembleCivRelease -Patak.version=${ATAK_VER} first" >&2
fi
shopt -u nullglob

APK_NAME=""
if [[ -n "${APK}" && -f "${APK}" ]]; then
  APK_NAME="$(basename "${APK}")"
  cp -f "${APK}" "${PLUGINS_DIR}/${APK_NAME}"
fi

MANIFEST="${PLUGINS_DIR}/UV-PRO-${VERSION}-ATAK-${ATAK_VER}-submission-MANIFEST.txt"
cat > "${MANIFEST}" << EOF
UV-PRO ${VERSION} submission pack (local)
ATAK target: ${ATAK_VER}
Git: ${FULLSHA} (${SHA})
UTC: ${STAMP}
Output directory: ${PLUGINS_DIR}/

  - ${SOURCE_ZIP}
      TPP source archive; root folder ${TPP_ROOT}/
      gradle.properties includes atak.version=${ATAK_VER} for TPC SDK resolution.
      Includes gradle/takdev/atak-gradle-takdev.jar so TPC can use classpath files(...)
      without Artifactory init.d (see root build.gradle).
EOF
if [[ -n "${APK_NAME}" ]]; then
  cat >> "${MANIFEST}" << EOF
  - ${APK_NAME}
      Local assembleCivRelease (not TPC-signed). Replace with TPC APK when returned.
EOF
else
  cat >> "${MANIFEST}" << EOF
  - (no APK — run ./gradlew assembleCivRelease first)
EOF
fi

echo "Wrote:"
echo "  ${SOURCE_PATH}"
[[ -n "${APK_NAME}" ]] && echo "  ${PLUGINS_DIR}/${APK_NAME}"
echo "  ${MANIFEST}"
ls -la "${SOURCE_PATH}" "${PLUGINS_DIR}/${APK_NAME}" 2>/dev/null || ls -la "${SOURCE_PATH}"
