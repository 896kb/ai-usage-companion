#!/bin/sh
# Copy a Claude Code credential from the macOS keychain to the CLIPBOARD.
#
#   ./tools/export-work-token.sh                      # work  (~/.claude-work)
#   ./tools/export-work-token.sh "$HOME/.claude"      # personal
#
# The keychain service name is derived from CLAUDE_CONFIG_DIR:
#   Claude Code-credentials-<sha256(dir)[:8]>, or the unsuffixed name for ~/.claude.
#
# SECURITY, deliberate and load-bearing:
#   * The credential goes to the clipboard, NOT to a file, and NOT to the phone's
#     filesystem. An earlier revision pushed it to /sdcard/Download, which is
#     readable by any app holding storage permission — that would leave a live
#     enterprise refresh token world-readable on the device indefinitely.
#   * It is never echoed to the terminal, which would persist it in shell
#     scrollback and any terminal logging.
#   * No temp file is written, so nothing survives a crash mid-run — the
#     credential lives only in the $BLOB shell variable, in memory.
#   * The keychain read is captured with $(...) and checked with `||` as its
#     own command, not piped straight into pbcopy. Piping
#     `security ... | tr -d '\n' | pbcopy` would hide a `security` failure:
#     under #!/bin/sh (no pipefail assumed), a pipeline's exit status is the
#     *last* command's, and pbcopy exits 0 on empty input too — so `if !
#     security | pbcopy` would report success even when the keychain read
#     failed, leaving the user with a false "copied" message and a stale or
#     empty clipboard.
set -e
DIR="${1:-$HOME/.claude-work}"

if [ "$DIR" = "$HOME/.claude" ]; then
  SERVICE="Claude Code-credentials"
else
  HASH=$(printf '%s' "$DIR" | shasum -a 256 | cut -c1-8)
  SERVICE="Claude Code-credentials-$HASH"
fi
echo "→ keychain service: $SERVICE"

BLOB=$(security find-generic-password -s "$SERVICE" -w) || {
  echo "✗ could not read $SERVICE — is that config dir logged in?"; exit 1; }

# Extract ONLY claudeAiOauth. The keychain entry also holds `mcpOAuth`, which
# carries OAuth state for whatever MCP servers are configured (Atlassian, etc.) —
# credentials for unrelated third-party services. Copying the whole blob would put
# those on the clipboard and into the app's paste field for no reason; the app reads
# four fields from claudeAiOauth and ignores everything else.
#
# plutil is built into macOS, so this needs no extra dependency.
CLAUDE_OAUTH=$(printf '%s' "$BLOB" | plutil -extract claudeAiOauth json -o - - 2>/dev/null) || {
  echo "✗ no claudeAiOauth in $SERVICE — unexpected keychain shape"; exit 1; }

# Write straight into the app's PRIVATE storage over adb.
#
# An earlier revision copied to the macOS clipboard and asked the user to paste —
# which cannot work, because macOS and Android do not share a clipboard. Before
# that it pushed to /sdcard/Download, which any app holding storage permission can
# read. This route has neither problem:
#   * /data/data/<pkg>/files is app-private; even the shell user gets
#     "Permission denied" listing the parent, so no other app can read it.
#   * It never touches shared storage and never goes through an intent extra.
#   * It travels only over the USB debug link, which is already trusted.
# The app deletes the file as soon as it has imported and encrypted the contents.
ADB=/opt/homebrew/bin/adb
DEVICE=f940914b
PKG=dev.rgarjoaba.claudestatus

if ! "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
  echo "✗ device $DEVICE not connected"; exit 1
fi

printf '%s' "$CLAUDE_OAUTH" \
  | "$ADB" -s "$DEVICE" shell "run-as $PKG sh -c 'cat > files/work-token.json && chmod 600 files/work-token.json'" \
  || { echo "✗ could not write to the app's private storage — is it installed?"; exit 1; }

echo "✓ credential written to the app's private storage"
echo "  Open Claude Status and tap 'Import work token'."
echo "  The app deletes the file once it has encrypted the contents."
