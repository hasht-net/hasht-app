#!/bin/sh
# Runs the RFC 8291 test vector against the Swift decryptor the iOS
# Notification Service Extension uses.
set -e
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
swiftc -o "$out/webpush-test" ios/App/Shared/WebPush.swift scripts/webpush-test/main.swift
"$out/webpush-test"
