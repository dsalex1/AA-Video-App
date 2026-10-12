#!/usr/bin/env bash
# Guided setup for the first Google Play internal-testing release.
#   1. creates the upload key (outside the repo)   2. stores it as GitHub secrets
#   3. lets CI build the signed bundle and downloads it   4. walks through Play Console
#   5. swaps the sideloaded app on the phone for the Play version
# Run from anywhere: bash scripts/play-setup.sh   (Git Bash on Windows works). Safe to re-run; done steps are skipped.
set -euo pipefail

PACKAGE=de.dsalex.akademieauto
KEY_DIR="$HOME/.akademie-auto"
KEYSTORE="$KEY_DIR/release.jks"
ALIAS=upload
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_DIR/play-upload"

bold() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
info() { printf '%s\n' "$*"; }
die() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }
pause() { read -rp "→ $* Press Enter when done… " _; }
ask() { local a; read -rp "$1 [y/N] " a; [[ $a == [yYjJ]* ]]; }

# Java tools: PATH, JAVA_HOME, or the JDK bundled with Android Studio.
java_tool() {
  local name=$1 dir
  command -v "$name" >/dev/null 2>&1 && { command -v "$name"; return; }
  for dir in "${JAVA_HOME:-}/bin" "/c/Program Files/Android/Android Studio/jbr/bin" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin" "/opt/android-studio/jbr/bin"; do
    for f in "$dir/$name" "$dir/$name.exe"; do [[ -x $f ]] && { echo "$f"; return; }; done
  done
  die "$name not found. Install Android Studio or a JDK, or set JAVA_HOME."
}

bold "0/5 Checking tools"
KEYTOOL=$(java_tool keytool)
JARSIGNER=$(java_tool jarsigner)
command -v gh >/dev/null || die "GitHub CLI (gh) not found: https://cli.github.com"
gh auth status >/dev/null 2>&1 || die "Not logged in to GitHub. Run: gh auth login"
command -v base64 >/dev/null || die "base64 not found"
cd "$REPO_DIR"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
info "keytool: $KEYTOOL"
info "GitHub repo: $REPO"

bold "1/5 Upload key"
if [[ -f $KEYSTORE ]]; then
  info "Using the existing key $KEYSTORE"
  read -rsp "Its password: " PASS; echo
  export PASS
  "$KEYTOOL" -list -keystore "$KEYSTORE" -alias "$ALIAS" -storepass:env PASS >/dev/null 2>&1 \
    || die "Wrong password (or no '$ALIAS' key in $KEYSTORE)."
else
  info "Creating $KEYSTORE (kept outside the repo). Keep the file and its password safe:"
  info "every future update must be signed with this key."
  while :; do
    read -rsp "New password (at least 6 characters): " PASS; echo
    read -rsp "Repeat it: " PASS2; echo
    [[ ${#PASS} -ge 6 && $PASS == "$PASS2" ]] && break
    info "Too short or not identical, try again."
  done
  export PASS
  read -rp "Your name for the certificate (e.g. Alex D.): " NAME
  mkdir -p "$KEY_DIR"
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:env PASS -keypass:env PASS -dname "CN=${NAME:-Akademie Auto}, O=Akademie Auto" >/dev/null
  info "Created. Back up $KEYSTORE now (e.g. in your password manager)."
fi

bold "2/5 GitHub secrets"
if gh secret list | grep -q '^SIGNING_KEYSTORE_BASE64' && ! ask "Signing secrets already exist. Overwrite them with this key?"; then
  info "Keeping the existing secrets."
else
  base64 -w0 "$KEYSTORE" | gh secret set SIGNING_KEYSTORE_BASE64
  printf %s "$PASS" | gh secret set SIGNING_STORE_PASSWORD
  printf %s "$PASS" | gh secret set SIGNING_KEY_PASSWORD
  printf %s "$ALIAS" | gh secret set SIGNING_KEY_ALIAS
  info "Secrets set."
fi

bold "3/5 Signed bundle from CI"
BEFORE=$(gh run list --workflow android.yml --branch master --limit 1 --json databaseId -q '.[0].databaseId' || true)
gh workflow run android.yml --ref master
info "Waiting for the build to start…"
for _ in $(seq 30); do
  RUN=$(gh run list --workflow android.yml --branch master --limit 1 --json databaseId -q '.[0].databaseId')
  [[ $RUN != "$BEFORE" ]] && break
  sleep 3
done
[[ $RUN != "$BEFORE" ]] || die "The build didn't start; check https://github.com/$REPO/actions"
gh run watch "$RUN" --exit-status || die "The build failed: https://github.com/$REPO/actions/runs/$RUN"
rm -rf "$OUT_DIR"
gh run download "$RUN" -n akademie-auto-release-aab -D "$OUT_DIR"
AAB="$OUT_DIR/app-release.aab"
[[ -f $AAB ]] || die "No app-release.aab in the build artifacts."
# The bundle must be signed with this upload key.
"$JARSIGNER" -verify "$AAB" >/dev/null || die "The bundle isn't signed."
want=$("$KEYTOOL" -list -keystore "$KEYSTORE" -alias "$ALIAS" -storepass:env PASS -v | grep -m1 'SHA256:' | tr -d ' \t')
got=$("$KEYTOOL" -printcert -jarfile "$AAB" | grep -m1 'SHA256:' | tr -d ' \t')
[[ -n $want && $want == "$got" ]] || die "The bundle is signed with a different key than $KEYSTORE."
info "Signed bundle: $AAB"

bold "4/5 Play Console (https://play.google.com/console)"
info "1. Create app → name 'Akademie Auto', App, Free, accept the declarations."
pause "App created?"
info "2. Test and release → Advanced settings → Form factors → '+ Add form factor' → Android Auto → save."
pause "Android Auto added?"
info "3. Test and release → Testing → Internal testing → Testers: create an email list with your Google account."
pause "Tester list created?"
info "4. Internal testing → Create new release. Accept 'Play App Signing' if asked."
info "   Upload: $AAB"
info "   Release name: anything (e.g. 1.0). Next → Save and publish / Start rollout."
info "   If Play shows required 'App content' tasks (privacy policy, data safety, …), complete those first."
pause "Release rolled out?"
info "5. Internal testing → Testers → 'Copy link' and open that link on the phone; accept the invitation."
pause "Invitation accepted on the phone?"

bold "5/5 Phone"
if ! command -v adb >/dev/null || ! adb get-state >/dev/null 2>&1; then
  info "No phone on adb. Do it by hand: uninstall the old Akademie Auto, then install it from the Play link."
else
  installer=$(adb shell pm list packages -i 2>/dev/null | tr -d '\r' | grep "package:$PACKAGE " | sed 's/.*installer=//' || true)
  if [[ -n $installer && $installer != com.android.vending ]]; then
    info "The phone has the sideloaded app (installer: $installer). Play can't update it (different key)."
    if ask "Uninstall it now? (Its WebView login and settings are removed too.)"; then
      adb uninstall "$PACKAGE" >/dev/null && info "Uninstalled."
    fi
  fi
  info "Install 'Akademie Auto' from the Play link (the first release can take a few minutes to appear)."
  pause "Installed from Play?"
  installer=$(adb shell pm list packages -i 2>/dev/null | tr -d '\r' | grep "package:$PACKAGE " | sed 's/.*installer=//' || true)
  if [[ $installer == com.android.vending ]]; then
    info "✓ Installed from Google Play. Android Auto now treats it as a Play app: 'Akademie Web' should appear in the car."
  else
    info "Not from Play yet (installer: ${installer:-not installed}). Re-run this script once it is."
  fi
fi

bold "Done"
info "Next updates: push to master, run the workflow again (or this script's step 3), upload the new bundle to Internal testing."
