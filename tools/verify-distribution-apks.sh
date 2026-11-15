#!/usr/bin/env bash
# CI safety gate: a dual-flavor build must retain two distinct install identities,
# identical release versions, and no network permissions in either manifest.
set -euo pipefail

if [[ $# -ne 1 || ! -x "$1" ]]; then
  printf 'Usage: %s /path/to/android-sdk/build-tools/<version>/aapt\n' "$0" >&2
  exit 2
fi

aapt="$1"
play_debug='app/build/outputs/apk/play/debug/app-play-debug.apk'
fdroid_release='app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk'

verify_apk() {
  local apk="$1" expected_id="$2" expected_name="$3"
  local badging permissions
  if [[ ! -f "$apk" ]]; then
    printf 'Missing APK: %s\n' "$apk" >&2
    return 1
  fi

  badging="$("$aapt" dump badging "$apk")"
  permissions="$("$aapt" dump permissions "$apk")"
  if [[ "$badging" != *"package: name='$expected_id' versionCode='20' versionName='$expected_name'"* ]]; then
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
  printf 'Validated %s: %s, version 1.3.5/code 20, offline manifest and AGPL asset\n' "$apk" "$expected_id"
}

verify_apk "$play_debug" 'com.npfs.stars.kinetictorch.debug' '1.3.5-debug'
verify_apk "$fdroid_release" 'com.npfs.stars.kinetictorch.fdroid' '1.3.5'
