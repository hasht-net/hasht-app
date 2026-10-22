#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -module-cache-path "$out/modules" -o "$out/deeplinks-test" \
  ios/App/App/DeepLinks.swift scripts/deeplinks-ios-test/main.swift
"$out/deeplinks-test"
