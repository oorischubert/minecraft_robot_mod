#!/bin/sh
# Starts Claude Code in robot mode: Claude gets the minebot tools and nothing else
# (no shell, no file access, no other MCP servers), and the minebot tools are pre-approved.
cd "$(dirname "$0")" || exit 1
exec claude --strict-mcp-config --mcp-config .mcp.json --tools "" --allowedTools "mcp__minebot" "$@"
