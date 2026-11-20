#!/usr/bin/env bash
# wear.sh - set up tools and build a Wear OS app from the Codespaces terminal.
#
#   bash wear.sh check          show what is installed (changes nothing)
#   bash wear.sh setup          install ONLY what is missing (JDK 17, Android SDK, platform-tools)
#   bash wear.sh build [dir]    build the debug APK (finds your project automatically)
#
# Safe to re-run any time.

set -u

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"
ARCH="$(dpkg --print-architecture 2>/dev/null || echo amd64)"
JDK17="${JDK17:-/usr/lib/jvm/java-17-openjdk-$ARCH}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
ENV_FILE="$HOME/.wear_env"

ok()   { printf '  [OK]   %s\n' "$*"; }
info() { printf '  [..]   %s\n' "$*"; }
todo() { printf '  [TODO] %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; }
head_() { printf '\n== %s ==\n' "$*"; }

as_root() { if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi; }

have_jdk17()    { [ -x "$JDK17/bin/javac" ]; }
have_cmdline()  { [ -x "$SDKMANAGER" ]; }
have_platform_tools() { [ -x "$ANDROID_HOME/platform-tools/adb" ]; }
have_platform() { [ -d "$ANDROID_HOME/platforms/$PLATFORM" ]; }
have_buildtools() { [ -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS" ]; }
have_cmd()      { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- check
do_check() {
  head_ "Environment"
  info "OS: $(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME") ($ARCH)"
  info "Disk free in home: $(df -h "$HOME" | awk 'NR==2{print $4}')"
  if have_jdk17; then ok "JDK 17: $JDK17"; else todo "JDK 17 missing (will install openjdk-17)"; fi
  if have_cmd java; then info "Default java on PATH: $(java -version 2>&1 | grep -m1 version) (builds use JDK 17 anyway)"; fi
  have_cmd unzip && ok "unzip" || todo "unzip missing"
  have_cmd curl  && ok "curl"  || todo "curl missing"
  if have_cmdline; then ok "Android command-line tools: $ANDROID_HOME/cmdline-tools/latest"; else todo "Android command-line tools missing"; fi
  have_platform_tools && ok "platform-tools (adb)" || todo "platform-tools missing"
  have_platform && ok "SDK platform $PLATFORM" || todo "SDK platform $PLATFORM missing"
  have_buildtools && ok "build-tools $BUILD_TOOLS" || todo "build-tools $BUILD_TOOLS missing"
  if [ -d "$ANDROID_HOME/licenses" ] && [ -n "$(ls -A "$ANDROID_HOME/licenses" 2>/dev/null)" ]; then
    ok "SDK licenses accepted"
  else
    todo "SDK licenses not accepted yet"
  fi
}

# ---------------------------------------------------------------- setup
do_setup() {
  head_ "Installing only what is missing"

  # 1. apt packages
  apt_needed=()
  have_jdk17 || apt_needed+=(openjdk-17-jdk-headless)
  have_cmd unzip || apt_needed+=(unzip)
  have_cmd curl || apt_needed+=(curl)
  if [ "${#apt_needed[@]}" -gt 0 ]; then
    info "apt install: ${apt_needed[*]}"
    as_root apt-get update -qq || true
    if ! as_root apt-get install -y -qq "${apt_needed[@]}"; then
      bad "apt install failed. Check the messages above."; return 1
    fi
  fi
  have_jdk17 && ok "JDK 17 ready" || { bad "JDK 17 not found at $JDK17"; return 1; }

  # 2. command-line tools
  if have_cmdline; then
    ok "Android command-line tools already installed"
  else
    info "Downloading Android command-line tools..."
    tmp="$(mktemp -d)"
    if ! curl -fL --retry 3 -o "$tmp/clt.zip" "$CLT_URL"; then
      bad "Download failed: $CLT_URL"; rm -rf "$tmp"; return 1
    fi
    unzip -q "$tmp/clt.zip" -d "$tmp/x" || { bad "unzip failed"; rm -rf "$tmp"; return 1; }
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/x/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
    have_cmdline && ok "command-line tools installed" || { bad "sdkmanager not found after install"; return 1; }
  fi

  # 3. licenses + SDK packages
  export JAVA_HOME="$JDK17"
  need_pkgs=()
  have_platform_tools || need_pkgs+=("platform-tools")
  have_platform || need_pkgs+=("platforms;$PLATFORM")
  have_buildtools || need_pkgs+=("build-tools;$BUILD_TOOLS")
  if [ ! -d "$ANDROID_HOME/licenses" ] || [ -z "$(ls -A "$ANDROID_HOME/licenses" 2>/dev/null)" ] || [ "${#need_pkgs[@]}" -gt 0 ]; then
    info "Accepting SDK licenses (the Android SDK terms; this is the unavoidable 'yes')..."
    yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  fi
  if [ "${#need_pkgs[@]}" -gt 0 ]; then
    info "Installing: ${need_pkgs[*]}"
    if ! "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${need_pkgs[@]}"; then
      bad "sdkmanager failed"; return 1
    fi
  else
    ok "All SDK packages already present"
  fi

  # 4. environment for future terminals
  cat > "$ENV_FILE" <<EOF
export JAVA_HOME="$JDK17"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF
  if ! grep -q "$ENV_FILE" "$HOME/.bashrc" 2>/dev/null; then
    echo "[ -f $ENV_FILE ] && . $ENV_FILE" >> "$HOME/.bashrc"
  fi
  ok "Environment saved ($ENV_FILE). New terminals pick it up automatically."
  do_check
  printf '\nSetup finished. Next: bash wear.sh build\n'
}

# ---------------------------------------------------------------- build
find_project() {
  mapfile -t found < <(find /workspaces "$PWD" -maxdepth 4 -name gradlew -not -path '*/node_modules/*' -not -path '*/build/*' 2>/dev/null | sort -u)
  if [ "${#found[@]}" -eq 0 ]; then bad "No gradlew found. Pass your project folder: bash wear.sh build /workspaces/<repo>/<app>" >&2; return 1; fi
  if [ "${#found[@]}" -gt 1 ]; then
    bad "Several projects found. Pick one:" >&2
    for f in "${found[@]}"; do echo "         bash wear.sh build \"$(dirname "$f")\"" >&2; done
    return 1
  fi
  dirname "${found[0]}"
}

do_build() {
  head_ "Building"
  if ! have_jdk17 || ! have_cmdline || ! have_platform_tools; then
    bad "Tools are missing. Run: bash wear.sh setup"; return 1
  fi
  dir="${1:-}"
  if [ -z "$dir" ]; then dir="$(find_project)" || return 1; fi
  [ -f "$dir/gradlew" ] || { bad "No gradlew in $dir"; return 1; }
  dir="$(cd "$dir" && pwd)"
  info "Project: $dir"
  chmod +x "$dir/gradlew"

  # point Gradle at our SDK
  lp="$dir/local.properties"
  { [ -f "$lp" ] && grep -v '^sdk.dir=' "$lp"; echo "sdk.dir=$ANDROID_HOME"; } > "$lp.new" && mv "$lp.new" "$lp"

  export JAVA_HOME="$JDK17" ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
  info "Running Gradle (first build downloads Gradle and libraries, several minutes)..."
  if ! ( cd "$dir" && ./gradlew assembleDebug --console=plain ); then
    bad "Build failed. The error is near the end of the output above."
    echo "         Common fixes: 'SDK location not found' -> bash wear.sh setup; 'Unsupported class file / Java version'"
    echo "         -> tell Claude your gradle/wrapper/gradle-wrapper.properties and build.gradle(.kts) versions."
    return 1
  fi

  apk="$(find "$dir" -path '*/build/outputs/apk/debug/*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-)"
  if [ -z "$apk" ]; then bad "Build succeeded but no debug APK was found."; return 1; fi
  mkdir -p "$dir/dist"
  out="$dir/dist/$(basename "$apk")"
  cp -f "$apk" "$out"

  head_ "Result"
  ok "APK: $out"
  info "Size: $(du -h "$out" | cut -f1)   SHA-256: $(sha256sum "$out" | cut -c1-16)..."
  aapt2="$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"
  if [ -x "$aapt2" ]; then info "$("$aapt2" dump badging "$out" 2>/dev/null | head -1)"; fi
  ks="$HOME/.android/debug.keystore"
  if [ -f "$ks" ] && [ -x "$JDK17/bin/keytool" ]; then
    fp="$("$JDK17/bin/keytool" -list -keystore "$ks" -storepass android 2>/dev/null | grep -i -m1 'fingerprint' | sed 's/.*: //')"
    info "Debug signing key fingerprint: ${fp:-unknown}"
    info "Updates only install over an older build if the signing key is the same."
    info "Keep a copy of $ks (a deleted Codespace loses it, and the watch would then need an uninstall first)."
  fi

  cat <<EOT

NEXT - install on the watch (run these on your WINDOWS laptop, not here):
  1. In VS Code Explorer: right-click  dist/$(basename "$out")  ->  Download
  2. One-time on Windows PowerShell:   winget install Google.PlatformTools
     (then open a NEW terminal)
  3. Watch: Settings > About watch > Software information > tap Software version 7 times,
     then Settings > Developer options > turn on ADB debugging and Wireless debugging.
  4. Watch: Wireless debugging > Pair new device (shows IP:PORT and a 6-digit code):
       adb pair <IP>:<PAIR_PORT>        (type the code)
       adb connect <IP>:<CONNECT_PORT>  (the port shown on the Wireless debugging screen)
  5. Install / update:
       cd \$env:USERPROFILE\Downloads
       adb install -r $(basename "$out")
EOT
}

case "${1:-}" in
  check) do_check ;;
  setup) do_check; do_setup ;;
  build) shift; do_build "${1:-}" ;;
  *) sed -n '2,8p' "$0" ;;
esac
