#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -module-cache-path "$out/modules" -o "$out/signup-prefill-test" \
  ios/App/App/SignupPrefill.swift scripts/signup-prefill-ios-test/main.swift
"$out/signup-prefill-test"
