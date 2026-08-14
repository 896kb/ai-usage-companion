#!/bin/sh
# Export a Claude Code credential from the macOS keychain and push it to the phone.
#
#   ./tools/export-work-token.sh                      # work  (~/.claude-work)
#   ./tools/export-work-token.sh "$HOME/.claude"      # personal
#
# The keychain service name is derived from CLAUDE_CONFIG_DIR:
#   Claude Code-credentials-<sha256(dir)[:8]>, or the unsuffixed name for ~/.claude.
set -e
ADB=/opt/homebrew/bin/adb
DEVICE=f940914b
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

TMP=$(mktemp)
printf '%s' "$BLOB" > "$TMP"
"$ADB" -s "$DEVICE" push "$TMP" /sdcard/Download/claude-token.json >/dev/null
rm -f "$TMP"
echo "✓ pushed to /sdcard/Download/claude-token.json"
echo "  Now open Claude Status and tap 'Import work token'."
