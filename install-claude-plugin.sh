#!/bin/bash
# by Claude - Install Kotlin LSP plugin for Claude Code

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$HOME/.claude-plugins/kotlin-lsp"
LSP_BINARY="$SCRIPT_DIR/build/install/kmp-lsp/bin/kmp-lsp"

echo "=== Kotlin LSP Plugin Installer for Claude Code ==="
echo

# Check if LSP binary exists
if [ ! -f "$LSP_BINARY" ]; then
    echo "LSP binary not found. Building..."
    cd "$SCRIPT_DIR"
    ./gradlew installDist
    echo
fi

# Create plugin directory
echo "Creating plugin directory at: $PLUGIN_DIR"
mkdir -p "$PLUGIN_DIR"

# Create plugin.json
cat > "$PLUGIN_DIR/plugin.json" << EOF
{
  "name": "kotlin-lsp",
  "version": "1.0.0",
  "description": "Kotlin Multiplatform Language Server",
  "author": "local"
}
EOF

# Create .lsp.json with absolute path
cat > "$PLUGIN_DIR/.lsp.json" << EOF
{
  "kotlin": {
    "command": "$LSP_BINARY",
    "args": [],
    "extensionToLanguage": {
      ".kt": "kotlin",
      ".kts": "kotlin"
    },
    "transport": "stdio",
    "restartOnCrash": true,
    "maxRestarts": 3,
    "startupTimeout": 30000,
    "shutdownTimeout": 5000
  }
}
EOF

echo "Plugin installed successfully!"
echo
echo "Files created:"
echo "  - $PLUGIN_DIR/plugin.json"
echo "  - $PLUGIN_DIR/.lsp.json"
echo
echo "To enable the plugin, add to ~/.claude/settings.json:"
echo
echo '  {'
echo '    "enabledPlugins": {'
echo '      "kotlin-lsp": true'
echo '    }'
echo '  }'
echo
echo "Or test it with:"
echo "  claude --plugin-dir $PLUGIN_DIR"
echo
