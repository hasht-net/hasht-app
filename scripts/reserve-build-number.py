#!/usr/bin/env python3
"""Reserve a store build number in GitHub for a local or CI release build."""

import argparse
import json
import re
import subprocess
import sys


PREFIX = "refs/tags/build-number/"
# CI previously reached build 4; a local Play beta used build 5.
LAST_LEGACY_BUILD = 5
# Google Play's versionCode ceiling; iOS uses the same plain integer.
MAX_BUILD = 2_100_000_000


def gh_api(repository, endpoint, payload=None):
    command = ["gh", "api", f"repos/{repository}/{endpoint}"]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(
        command,
        input=json.dumps(payload) if payload is not None else None,
        text=True,
        capture_output=True,
        check=True,
    )
    return json.loads(result.stdout)


def reserve(repository, sha, api=gh_api):
    for _ in range(10):
        references = api(repository, "git/matching-refs/tags/build-number/")
        numbers = [LAST_LEGACY_BUILD]
        for reference in references:
            name = reference["ref"]
            if not name.startswith(PREFIX):
                continue
            suffix = name[len(PREFIX):]
            if not re.fullmatch(r"[1-9][0-9]*", suffix):
                raise ValueError(f"Invalid build-number reservation: {name}")
            numbers.append(int(suffix))
        number = max(numbers) + 1
        if number > MAX_BUILD:
            raise ValueError(f"Build number exceeds the shared store limit {MAX_BUILD}")
        try:
            api(repository, "git/refs", {"ref": f"{PREFIX}{number}", "sha": sha})
        except subprocess.CalledProcessError as error:
            # A competing local/CI build may have reserved this number first.
            # Verify the exact ref exists before retrying; don't hide API errors.
            try:
                api(repository, f"git/ref/tags/build-number/{number}")
            except subprocess.CalledProcessError:
                raise error
            continue
        return number
    raise RuntimeError("Could not reserve a build number after 10 concurrent attempts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="hasht-net/hasht-app")
    parser.add_argument("--sha", help="Published commit being built (defaults to HEAD)")
    args = parser.parse_args()
    sha = args.sha or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True
    ).strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha):
        parser.error("--sha must be a full commit SHA")
    try:
        print(reserve(args.repo, sha))
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Build-number reservation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
