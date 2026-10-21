#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -module-cache-path "$out/modules" -o "$out/share-test" \
  ios/App/Shared/ShareSession.swift \
  ios/App/ShareExtension/ShareContent.swift \
  ios/App/ShareExtension/ShareAPI.swift scripts/share-ios-test/main.swift
"$out/share-test"
