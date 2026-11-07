#!/usr/bin/env bash
# Verifies signed release-candidate APKs before they are uploaded as CI artifacts.
#
#   verify-rc-apk.sh <brand> <versionName> <versionCode> <apk>...
#
# Fails unless every APK
#   - has the brand's package id and exactly the expected versionName/versionCode,
#   - is not debug-signed,
#   - has exactly one signer whose certificate SHA-256 equals the brand's pinned production certificate.
#
# Pins are the public certificate fingerprints of each brand's published production APKs (not secrets),
# verified from the GitHub releases v4.4.23, allaccess-v4.4.24, 5star-v4.4.24 and supreme-v4.4.24.
# An unknown brand or a missing pin fails: a release candidate is never accepted unpinned.
#
# Tools: $BT is the Android build-tools directory; APKSIGNER / AAPT2 override the binaries.
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <brand> <versionName> <versionCode> <apk>..." >&2
  exit 2
fi
BRAND="$1"; NAME="$2"; CODE="$3"; shift 3

APKSIGNER="${APKSIGNER:-${BT:?set BT to the Android build-tools directory}/apksigner}"
AAPT2="${AAPT2:-$BT/aapt2}"

case "$BRAND" in
  goat)      PKG="tv.own.owntv";      CERT="fd5f1a7ca403ecf7859744d4e3c4a7578f945ea720b4947be2a24efc5e706528" ;;
  allaccess) PKG="tv.allaccess.app";  CERT="f91dae1290d62bcc3cb12968e08f775b75dacd825bc42736ad3369d75829c392" ;;
  fivestar)  PKG="tv.fivestar.ultra"; CERT="de6dfddaf31a831b4ddc5d70107ee3b2523d7fc7f6173d3da295893f62c3832a" ;;
  supreme)   PKG="tv.supreme.app";    CERT="9c1fda1cd1c8e1082506ed4e535b276cd12201593dda3dd30d9f58ed87d576a2" ;;
  *) echo "::error::unknown brand '$BRAND'"; exit 1 ;;
esac
if ! [[ "$CERT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::no valid certificate pin for $BRAND"; exit 1
fi

for APK in "$@"; do
  BADGING=$("$AAPT2" dump badging "$APK" | head -n1 | tr -d '\r')
  echo "$APK: $BADGING"
  [[ "$BADGING" == *"name='$PKG' versionCode='$CODE' versionName='$NAME'"* ]] || {
    echo "::error::$APK package/version does not match $PKG $NAME ($CODE)"; exit 1; }

  CERTS=$("$APKSIGNER" verify --print-certs "$APK" | tr -d '\r')
  echo "$CERTS" | grep -E "certificate (DN|SHA-256)" || true
  if echo "$CERTS" | grep -q "CN=Android Debug"; then
    echo "::error::$APK is debug-signed"; exit 1
  fi
  SIGNERS=$(echo "$CERTS" | grep -c "certificate SHA-256 digest:" || true)
  if [ "$SIGNERS" != "1" ]; then
    echo "::error::$APK has $SIGNERS signers; expected exactly 1"; exit 1
  fi
  if ! echo "$CERTS" | grep -Fxq "Signer #1 certificate SHA-256 digest: $CERT"; then
    echo "::error::$APK is not signed with the published $BRAND production certificate"; exit 1
  fi
  echo "$APK: certificate matches the $BRAND production pin"
done
