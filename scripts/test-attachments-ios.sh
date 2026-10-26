#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
# Compile the production URL/name validator without UIKit for a fast host test.
python3 - "$out/main.swift" <<'PYTHON'
from pathlib import Path
import sys
source = Path("ios/App/App/ServerConfigPlugin.swift").read_text()
validator = source[source.index("final class AttachmentActionGeneration {"):source.index("/// URLSession writes directly to disk")]
tests = Path("scripts/attachments-ios-test.swift").read_text()
Path(sys.argv[1]).write_text("import Foundation\n" + validator + tests)
PYTHON
swiftc -module-cache-path "$out/modules" -o "$out/attachments-test" "$out/main.swift"
"$out/attachments-test"
