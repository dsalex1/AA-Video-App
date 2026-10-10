#!/usr/bin/env bash
# Builds the vendored Skip Silence extension into app/src/main/assets/skipsilence.
set -euo pipefail
cd "$(dirname "$0")"
npm ci
TARGET=popup npx vite build
TARGET=content npx vite build
TARGET=processor npx vite build
