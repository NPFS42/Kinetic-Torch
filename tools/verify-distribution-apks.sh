#!/usr/bin/env bash
# CI safety gate: a dual-flavor build must retain two distinct install identities,
# identical release versions, and no network permissions in either manifest.
set -euo pipefail

if [[ $# -ne 1 || ! -x "$1" ]]; then
  printf 'Usage: %s /path/to/android-sdk/build-tools/<version>/aapt\n' "$0" >&2
  exit 2
fi

aapt="$1"
gradle_config='app/build.gradle.kts'
play_debug='app/build/outputs/apk/play/debug/app-play-debug.apk'
fdroid_debug='app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk'
fdroid_release='app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk'

# Read the canonical version once, so the package-identity check stays in sync
# when release versions change. Reject ambiguous or missing declarations.
mapfile -t version_codes < <(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$gradle_config")
mapfile -t version_names < <(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)"[[:space:]]*$/\1/p' "$gradle_config")
if [[ ${#version_codes[@]} -ne 1 || ${#version_names[@]} -ne 1 ]]; then
  printf 'Expected exactly one versionCode and versionName in %s\n' "$gradle_config" >&2
  exit 1
fi
version_code="${version_codes[0]}"
version_name="${version_names[0]}"

verify_apk() {
  local apk="$1" expected_id="$2" expected_name="$3"
  local badging permissions
  if [[ ! -f "$apk" ]]; then
    printf 'Missing APK: %s\n' "$apk" >&2
    return 1
  fi

  badging="$("$aapt" dump badging "$apk")"
  permissions="$("$aapt" dump permissions "$apk")"
  if [[ "$badging" != *"package: name='$expected_id' versionCode='$version_code' versionName='$expected_name'"* ]]; then
    printf 'Unexpected application ID or version in %s\n' "$apk" >&2
    printf '%s\n' "${badging%%$'\n'*}" >&2
    return 1
  fi
  if [[ "$permissions" == *"android.permission.INTERNET"* ||
        "$permissions" == *"android.permission.ACCESS_NETWORK_STATE"* ||
        "$permissions" == *"android.permission.USE_FULL_SCREEN_INTENT"* ]]; then
    printf 'Unexpected network or deprecated full-screen permission in %s\n' "$apk" >&2
    return 1
  fi
  if ! unzip -p "$apk" assets/COPYING | cmp -s app/src/main/assets/COPYING -; then
    printf 'AGPLv3 license asset missing or mismatched in %s\n' "$apk" >&2
    return 1
  fi
  printf 'Validated %s: %s, version %s/code %s, offline manifest and AGPL asset\n' "$apk" "$expected_id" "$version_name" "$version_code"
}

verify_apk "$play_debug" 'com.npfs.stars.kinetictorch.debug' "${version_name}-debug"
verify_apk "$fdroid_debug" 'com.npfs.stars.kinetictorch.fdroid.debug' "${version_name}-debug"
verify_apk "$fdroid_release" 'com.npfs.stars.kinetictorch.fdroid' "$version_name"
