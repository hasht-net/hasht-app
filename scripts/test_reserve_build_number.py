import importlib.util
import pathlib
import subprocess
import unittest


spec = importlib.util.spec_from_file_location(
    "build_number", pathlib.Path(__file__).with_name("reserve-build-number.py")
)
build_number = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_number)


class FakeGitHub:
    def __init__(self, numbers=()):
        self.refs = {f"{build_number.PREFIX}{number}": "old-sha" for number in numbers}
        self.created = []
        self.race = False
        self.fail_create = False

    def __call__(self, repository, endpoint, payload=None):
        if endpoint == "git/matching-refs/tags/build-number/":
            return [{"ref": ref} for ref in self.refs]
        if endpoint == "git/refs":
            ref = payload["ref"]
            if self.fail_create:
                raise subprocess.CalledProcessError(1, ["gh", "api"])
            if self.race:
                self.refs[ref] = "competing-sha"
                self.race = False
            if ref in self.refs:
                raise subprocess.CalledProcessError(1, ["gh", "api"])
            self.refs[ref] = payload["sha"]
            self.created.append(payload)
            return {"ref": ref}
        ref = "refs/" + endpoint.removeprefix("git/ref/")
        if ref not in self.refs:
            raise subprocess.CalledProcessError(1, ["gh", "api"])
        return {"ref": ref, "object": {"sha": self.refs[ref]}}


class ReservationTests(unittest.TestCase):
    def test_first_number_skips_uploaded_legacy_builds(self):
        api = FakeGitHub()
        self.assertEqual(build_number.reserve("owner/repo", "target-sha", api), 6)
        self.assertEqual(api.created, [{"ref": "refs/tags/build-number/6", "sha": "target-sha"}])

    def test_ordering_is_numeric_and_follows_local_reservations(self):
        api = FakeGitHub([9, 10, 6])
        self.assertEqual(build_number.reserve("owner/repo", "target-sha", api), 11)

    def test_rebuilding_same_commit_always_gets_a_fresh_number(self):
        api = FakeGitHub()
        first = build_number.reserve("owner/repo", "same-sha", api)
        second = build_number.reserve("owner/repo", "same-sha", api)
        self.assertEqual((first, second), (6, 7))

    def test_racing_local_and_ci_reservations_do_not_reuse_number(self):
        api = FakeGitHub([6])
        api.race = True
        self.assertEqual(build_number.reserve("owner/repo", "our-sha", api), 8)
        self.assertEqual(api.refs["refs/tags/build-number/7"], "competing-sha")
        self.assertEqual(api.refs["refs/tags/build-number/8"], "our-sha")

    def test_noncollision_api_failure_stops_the_build(self):
        api = FakeGitHub()
        api.fail_create = True
        with self.assertRaises(subprocess.CalledProcessError):
            build_number.reserve("owner/repo", "target-sha", api)
        self.assertEqual(api.created, [])

    def test_read_failure_never_falls_back_to_legacy_number(self):
        def api(*args):
            raise subprocess.CalledProcessError(1, ["gh", "api"])

        with self.assertRaises(subprocess.CalledProcessError):
            build_number.reserve("owner/repo", "target-sha", api)

    def test_bad_reservations_stop_instead_of_silently_lowering_floor(self):
        for suffix in ("bogus", "06", "0", "-1", "7/extra"):
            with self.subTest(suffix=suffix):
                api = FakeGitHub([suffix])
                with self.assertRaises(ValueError):
                    build_number.reserve("owner/repo", "target-sha", api)
                self.assertEqual(api.created, [])

    def test_shared_store_limit(self):
        api = FakeGitHub([build_number.MAX_BUILD - 1])
        self.assertEqual(
            build_number.reserve("owner/repo", "target-sha", api), build_number.MAX_BUILD
        )
        with self.assertRaises(ValueError):
            build_number.reserve("owner/repo", "target-sha", api)

    def test_repeated_races_have_a_bounded_retry_count(self):
        api = FakeGitHub()

        def racing_api(repository, endpoint, payload=None):
            if payload is not None:
                api.race = True
            return api(repository, endpoint, payload)

        with self.assertRaises(RuntimeError):
            build_number.reserve("owner/repo", "target-sha", racing_api)
        self.assertEqual(len(api.refs), 10)
        self.assertEqual(api.created, [])


if __name__ == "__main__":
    unittest.main()
