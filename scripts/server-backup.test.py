#!/usr/bin/env python3
"""Pure-mock regression checks; no Docker, age, subprocesses or real disk writes."""

import contextlib
import importlib.util
import io
import json
import os
import stat
import subprocess
import tarfile
import unittest
from types import SimpleNamespace
from unittest import mock

SPEC = importlib.util.spec_from_file_location("server_backup", os.path.join(os.path.dirname(__file__), "server-backup.py"))
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)
SECRET = "NEVER_LOG_DATABASE_PASSWORD_OR_VAULT_KEY"


def config_archive(name="postgresql-custom/pgsodium_root.key", mode=0o600,
                   kind=tarfile.REGTYPE, include_key=True):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as archive:
        directory = tarfile.TarInfo("postgresql-custom")
        directory.type, directory.uid, directory.gid, directory.mode = tarfile.DIRTYPE, 100, 101, 0o700
        archive.addfile(directory)
        if include_key:
            key = tarfile.TarInfo(name)
            key.uid, key.gid, key.mode, key.type = 100, 101, mode, kind
            if kind == tarfile.REGTYPE:
                key.size = len(SECRET)
                archive.addfile(key, io.BytesIO(SECRET.encode("ascii")))
            else:
                key.linkname = "../../outside"
                archive.addfile(key)
    return output.getvalue()


class FakeFilesystem:
    def __init__(self):
        self.events = []
        self.files = {
            BACKUP.Config.root + "/deployment-receipt.json": json.dumps({
                "applicationCommit": "fbd11f1", "supabaseCommit": "f" * 40}).encode("utf-8"),
            BACKUP.Config.recipient: ("age1" + "q" * 58 + "\n").encode("ascii"),
        }
        self.directories = {BACKUP.Config.root + "/releases/fbd11f1"}
        self.manifest = None
        self.stream_failure = False
        self.cleanup_failure = False

    def prepare(self, config):
        self.events.append(("prepare",))

    def exists(self, path):
        return path in self.files or path in self.directories

    def check(self, path, directory=False, root_owned=True):
        if path not in (self.directories if directory else self.files):
            raise RuntimeError(SECRET)

    def read(self, path):
        return self.files[path]

    @contextlib.contextmanager
    def open(self, path, write=False):
        value = io.BytesIO() if write else io.BytesIO(self.files[path])
        try:
            yield value
        finally:
            if write:
                self.files[path] = value.getvalue()
                if path.endswith("/manifest.json"):
                    self.manifest = json.loads(value.getvalue().decode("utf-8"))
            value.close()

    def temporary(self, backups):
        self.temporary_path = backups + "/.incomplete-test"
        self.directories.add(self.temporary_path)
        self.events.append(("temporary", self.temporary_path))
        return self.temporary_path

    def mkdir(self, path):
        self.directories.add(path)

    def cleanup(self, path):
        self.events.append(("cleanup", path))
        self.files = {name: value for name, value in self.files.items() if not name.startswith(path + "/")}
        self.directories = {name for name in self.directories if name != path and not name.startswith(path + "/")}
        if self.cleanup_failure:
            raise RuntimeError(SECRET)

    def publish(self, source, destination):
        value = self.files[source]
        if not value.startswith(BACKUP.AGE_HEADER) or len(value) <= len(BACKUP.AGE_HEADER):
            raise RuntimeError(SECRET)
        self.events.append(("publish", destination))
        self.files[destination] = self.files.pop(source)

    def rotate(self, backups):
        self.events.append(("rotate", backups))

    def stream(self, path, output, deadline):
        self.events.append(("stream", path))
        deadline.remaining()
        if self.stream_failure:
            raise BrokenPipeError(SECRET)
        output.write(self.files[path])

    @contextlib.contextmanager
    def lock(self, path, deadline):
        deadline.remaining()
        self.events.append(("lock", path))
        try:
            yield
        finally:
            self.events.append(("unlock", path))


class FakeRunner:
    def __init__(self, filesystem, failure=None):
        self.fs, self.failure, self.calls = filesystem, failure, []
        self.containers = []
        for index, service in enumerate(("db", "kong", "auth", "storage", "supavisor"), 1):
            self.containers.append({"id": str(index) * 64, "name": "/supabase-db" if service == "db" else "/supabase-" + service,
                                    "image_id": "sha256:" + str(index) * 64, "image_reference": "repo/" + service + ":pinned",
                                    "project": "supabase", "service": service, "running": service != "supavisor"})

    def run(self, command, cwd, timeout, stdout=None, stdin=None):
        self.calls.append((list(command), cwd, timeout))
        self.fs.events.append(("command", list(command)))
        if command[:3] == ["docker", "compose", "ps"]:
            return ("\n".join(item["id"] for item in self.containers) + "\n").encode("ascii")
        if command[:2] == ["docker", "inspect"]:
            return ("\n".join(json.dumps(item) for item in self.containers if item["id"] in command[4:]) + "\n").encode("utf-8")
        if command[:3] == ["docker", "image", "inspect"]:
            return json.dumps(["repo/image@" + command[-1]]).encode("utf-8")
        if command[:3] == ["docker", "compose", "stop"]:
            for item in self.containers:
                if item["service"] in command[5:]:
                    item["running"] = False
            stage = "stop"
        elif command[:2] == ["docker", "start"]:
            if self.failure == "restart":
                raise RuntimeError(SECRET)
            for item in self.containers:
                if item["id"] in command[2:]:
                    item["running"] = True
            return b""
        elif "pg_dump" in command or "pg_dumpall" in command:
            stdout.write(SECRET.encode("ascii"))
            stage = "dump"
        elif command[:3] == ["docker", "exec", "supabase-db"]:
            if command[3:] != ["tar", "-cf", "-", "-C", "/etc", "postgresql-custom"]:
                raise AssertionError("Unexpected config archive command")
            stdout.write(SECRET.encode("ascii") if self.failure == "invalid_config" else config_archive())
            stage = "config_archive"
        elif command[0] == "tar":
            self.fs.files[command[3]] = ("tar containing .env " + SECRET).encode("ascii")
            stage = "archive"
        elif command[0] == BACKUP.Config.age:
            self.assert_plaintext_input = SECRET.encode("ascii") in stdin.read()
            stdout.write(BACKUP.AGE_HEADER + (b"ciphertext" if self.failure != "invalid_age" else b""))
            stage = "age"
        else:
            raise AssertionError("Unexpected command")
        if self.failure == stage:
            raise RuntimeError(SECRET)
        return b""


class SnapshotTests(unittest.TestCase):
    def fixture(self, failure=None):
        filesystem = FakeFilesystem()
        runner = FakeRunner(filesystem, failure)
        backup = BACKUP.Backup(runner=runner, filesystem=filesystem, clock=lambda: 100)
        return filesystem, runner, backup

    def invoke(self, backup, stream=False):
        output, errors = io.BytesIO(), io.StringIO()
        code = BACKUP.main(["--stream"] if stream else [], backup=backup, output=output,
                           error_output=errors, getuid=lambda: 0)
        self.assertNotIn(SECRET, errors.getvalue())
        self.assertNotIn(SECRET.encode("ascii"), output.getvalue())
        return code, output.getvalue(), errors.getvalue()

    def assert_clean(self, filesystem):
        self.assertFalse(any(".incomplete-test" in name for name in filesystem.files))
        self.assertEqual(len([event for event in filesystem.events if event[0] == "cleanup"]), 1)

    def test_default_receipt_is_only_owned_filename_and_snapshot_is_pinned(self):
        filesystem, runner, backup = self.fixture()
        code, output, errors = self.invoke(backup)
        self.assertEqual((code, errors), (0, "BACKUP_OK\n"))
        self.assertTrue(BACKUP.OWNED_NAME.fullmatch(output.decode("ascii").strip()))
        self.assertEqual(filesystem.manifest["application_commit"], BACKUP.BASELINE)
        self.assertEqual(len(filesystem.manifest["containers"]), 5)
        self.assertTrue(all(item["image_id"].startswith("sha256:") and item["repo_digests"] for item in filesystem.manifest["containers"]))
        self.assertIn("pgsodium_root.key", filesystem.manifest["restore_instructions"])
        self.assertIn("--numeric-owner --same-owner --same-permissions", filesystem.manifest["restore_instructions"])
        self.assertEqual(filesystem.manifest["postgresql_custom_key_metadata"], {"uid": 100, "gid": 101, "mode": 0o600})
        commands = [item[0] for item in runner.calls]
        stop = next(index for index, cmd in enumerate(commands) if cmd[:3] == ["docker", "compose", "stop"])
        dump = next(index for index, cmd in enumerate(commands) if "pg_dump" in cmd)
        age = next(index for index, cmd in enumerate(commands) if cmd[0] == BACKUP.Config.age)
        restart = next(index for index, cmd in enumerate(commands) if cmd[:2] == ["docker", "start"])
        self.assertLess(stop, dump)
        self.assertLess(age, restart)
        self.assertNotIn("db", commands[stop][5:])
        self.assertNotIn(runner.containers[0]["id"], commands[restart][2:])
        self.assertNotIn(runner.containers[-1]["id"], commands[restart][2:])
        self.assertFalse(runner.containers[-1]["running"])
        self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
        self.assertTrue(all(cwd == BACKUP.Config.stack and 0 < timeout <= 600 for unused, cwd, timeout in runner.calls))
        self.assertTrue(runner.assert_plaintext_input)
        self.assertEqual([event[1] for event in filesystem.events if event[0] == "lock"], [BACKUP.Config.root + "/backup.lock"])
        self.assert_clean(filesystem)

    def test_stream_is_only_age_bytes_and_lock_order_prevents_deploy_overlap(self):
        filesystem, runner, backup = self.fixture()
        code, output, errors = self.invoke(backup, stream=True)
        self.assertEqual((code, output, errors), (0, BACKUP.AGE_HEADER + b"ciphertext", "BACKUP_OK\n"))
        self.assertEqual([event[1] for event in filesystem.events if event[0] == "lock"],
                         [BACKUP.Config.root + "/deploy.lock", BACKUP.Config.root + "/backup.lock"])
        events = [event[0] for event in filesystem.events]
        self.assertLess(events.index("cleanup"), events.index("stream"))
        self.assertLess(events.index("stream"), events.index("rotate"))
        self.assert_clean(filesystem)

    def test_stop_dump_config_archive_age_failures_restart_and_clean_without_rotation(self):
        for stage in ("stop", "dump", "config_archive", "invalid_config", "archive", "age", "invalid_age"):
            with self.subTest(stage=stage):
                filesystem, runner, backup = self.fixture(stage)
                self.assertEqual(self.invoke(backup), (1, b"", "BACKUP_FAILED\n"))
                self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
                self.assertFalse(runner.containers[-1]["running"])
                self.assertFalse(any(event[0] in ("publish", "rotate") for event in filesystem.events))
                self.assert_clean(filesystem)

    def test_config_archive_headers_preserve_uid_gid_without_extracting_key(self):
        value = config_archive()
        metadata = BACKUP.validate_config_archive(io.BytesIO(value), BACKUP.Deadline(500, lambda: 100))
        self.assertEqual(metadata, {"uid": 100, "gid": 101, "mode": 0o600})
        with tarfile.open(fileobj=io.BytesIO(value), mode="r:") as archive:
            self.assertEqual(archive.getnames(), ["postgresql-custom", "postgresql-custom/pgsodium_root.key"])

    def test_config_archive_rejects_invalid_headers_missing_key_and_unsafe_members(self):
        cases = (b"invalid tar " + SECRET.encode("ascii"), config_archive()[:1024],
                 config_archive(include_key=False), config_archive(name="../../outside"),
                 config_archive(mode=0o644), config_archive(kind=tarfile.SYMTYPE))
        for value in cases:
            with self.subTest(length=len(value)):
                with self.assertRaises((BACKUP.BackupError, tarfile.TarError)):
                    BACKUP.validate_config_archive(io.BytesIO(value), BACKUP.Deadline(500, lambda: 100))

    def test_capture_deadline_reserves_restart_and_does_not_publish(self):
        filesystem, runner, backup = self.fixture()
        clock = mock.Mock(return_value=100)
        backup.clock = clock
        original = runner.run

        def expire_after_dump(command, **kwargs):
            result = original(command, **kwargs)
            if "pg_dump" in command:
                clock.return_value = 100 + BACKUP.TOTAL_SECONDS - BACKUP.RESTART_RESERVE + 1
            return result

        runner.run = expire_after_dump
        self.assertEqual(self.invoke(backup), (1, b"", "BACKUP_FAILED\n"))
        self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
        self.assertFalse(any(event[0] in ("publish", "rotate") for event in filesystem.events))
        self.assertTrue(all(timeout <= BACKUP.RESTART_RESERVE for cmd, unused, timeout in runner.calls if cmd[:2] == ["docker", "start"]))
        self.assert_clean(filesystem)

    def test_bad_recipient_restarts_and_emits_no_sensitive_diagnostic(self):
        filesystem, runner, backup = self.fixture()
        filesystem.files[BACKUP.Config.recipient] = SECRET.encode("ascii")
        self.assertEqual(self.invoke(backup), (1, b"", "BACKUP_FAILED\n"))
        self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
        self.assert_clean(filesystem)

    def test_failed_restart_attempts_each_container_and_prevents_publication(self):
        filesystem, runner, backup = self.fixture("restart")
        self.assertEqual(self.invoke(backup), (1, b"", "BACKUP_FAILED\n"))
        starts = [cmd for cmd, unused, timeout in runner.calls if cmd[:2] == ["docker", "start"]]
        self.assertEqual(len(starts), 4)
        self.assertEqual({cmd[2] for cmd in starts[1:]}, {item["id"] for item in runner.containers[1:4]})
        self.assertFalse(any(event[0] in ("publish", "rotate") for event in filesystem.events))
        self.assert_clean(filesystem)

    def test_transfer_or_cleanup_failure_never_rotates_old_snapshots(self):
        for failure in ("stream_failure", "cleanup_failure"):
            with self.subTest(failure=failure):
                filesystem, runner, backup = self.fixture()
                setattr(filesystem, failure, True)
                self.assertEqual(self.invoke(backup, stream=True), (1, b"", "BACKUP_FAILED\n"))
                self.assertFalse(any(event[0] == "rotate" for event in filesystem.events))
                self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
                self.assert_clean(filesystem)

    def test_pending_receipt_uses_previous_commit_not_unapplied_candidate(self):
        filesystem, runner, backup = self.fixture()
        previous = "a" * 40
        filesystem.directories.add(BACKUP.Config.root + "/releases/" + previous)
        filesystem.files[BACKUP.Config.root + "/deploy-receipt.json"] = json.dumps({
            "status": "pending", "commit": "b" * 40, "previous_commit": previous}).encode("utf-8")
        self.assertEqual(self.invoke(backup)[0], 0)
        self.assertEqual(filesystem.manifest["application_commit"], previous)

    def test_unresolved_marker_or_dangling_symlink_blocks_before_any_capture(self):
        marker = BACKUP.Config.root + "/deploy-unresolved.json"
        for symlink in (False, True):
            with self.subTest(symlink=symlink):
                filesystem, runner, backup = self.fixture()
                if symlink:
                    # Real exists helper must use lexists, not exists, so even
                    # a dangling symlink counts; no target/content is inspected.
                    filesystem.exists = BACKUP.Filesystem().exists
                else:
                    filesystem.files[marker] = SECRET.encode("ascii")
                with mock.patch.object(BACKUP.os.path, "lexists", return_value=True) as lexists, \
                        mock.patch.object(BACKUP.os.path, "exists", return_value=False):
                    self.assertEqual(self.invoke(backup), (1, b"", "BACKUP_FAILED\n"))
                    if symlink:
                        lexists.assert_called_once_with(marker)
                self.assertEqual(runner.calls, [])
                self.assertFalse(any(event[0] in ("temporary", "publish", "cleanup", "rotate") for event in filesystem.events))
                self.assertTrue(all(item["running"] for item in runner.containers[:-1]))
                self.assertFalse(runner.containers[-1]["running"])

    def test_archive_excludes_live_db_and_caches_but_keeps_env_storage_and_recovery(self):
        command = BACKUP.archive_command(BACKUP.Config(), "/private/stage", ["deployment-receipt.json", "deploy-receipt.json"])
        excludes = {value.split("=", 1)[1] for value in command if value.startswith("--exclude=")}
        self.assertEqual(excludes, {"supabase/docker/volumes/db/data", ".git", "cache", ".cache", "__pycache__"})
        self.assertNotIn("--dereference", command)
        self.assertNotIn("-h", command)
        self.assertIn("supabase/docker", command)
        self.assertIn("releases", command)
        self.assertIn("recovery-capsules", command)
        self.assertIn("database", command)
        self.assertIn("deploy-receipt.json", command)
        self.assertIn("link-vault-backup", command)
        self.assertTrue(all("flags=r;" in arg for arg in command if arg.startswith("--transform=")))
        self.assertNotIn(".env", excludes)
        self.assertNotIn("supabase/docker/volumes/storage", excludes)

    def test_cli_rejects_arguments_and_nonroot_without_execution(self):
        backup = mock.Mock()
        for argv, uid, expected in ((["--unsafe"], 0, "BACKUP_USAGE\n"), ([], 1000, "BACKUP_ROOT_REQUIRED\n")):
            output, errors = io.BytesIO(), io.StringIO()
            self.assertNotEqual(BACKUP.main(argv, backup, output, errors, lambda: uid), 0)
            self.assertEqual(output.getvalue(), b"")
            self.assertEqual(errors.getvalue(), expected)
        backup.execute.assert_not_called()

    def test_subprocess_errors_are_not_relayed_and_timeouts_remain_bounded(self):
        runner = BACKUP.Runner()
        for error in (subprocess.CalledProcessError(1, ["docker"], output=SECRET, stderr=SECRET),
                      subprocess.TimeoutExpired(["docker"], 12, output=SECRET, stderr=SECRET)):
            with mock.patch.object(BACKUP.subprocess, "run", side_effect=error) as run:
                with self.assertRaises(BACKUP.BackupError) as caught:
                    runner.run(["docker"], BACKUP.Config.stack, 12)
                self.assertNotIn(SECRET, str(caught.exception))
                self.assertEqual(run.call_args[1]["timeout"], 12)
                self.assertEqual(run.call_args[1]["stderr"], subprocess.PIPE)
        with self.assertRaises(BACKUP.BackupError):
            BACKUP.Deadline(5, lambda: 6).remaining()


class RetentionTests(unittest.TestCase):
    def test_rotation_removes_only_old_completed_root_owned_regular_single_link_files(self):
        names, entries = [], {}
        for index in range(10):
            name = "link-vault-20261007T120000Z-" + format(index, "016x") + ".tar.age"
            names.append(name)
            entries[name] = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1,
                                            st_mtime=index, st_dev=1, st_ino=index)
        for name, mode, uid, links in (
                ("other.tar.age", stat.S_IFREG, 0, 1),
                (".incomplete-example.tar.age", stat.S_IFREG, 0, 1),
                ("link-vault-20261007T120000Z-aaaaaaaaaaaaaaaa.tar.age", stat.S_IFLNK, 0, 1),
                ("link-vault-20261007T120000Z-bbbbbbbbbbbbbbbb.tar.age", stat.S_IFREG, 1000, 1),
                ("link-vault-20261007T120000Z-cccccccccccccccc.tar.age", stat.S_IFREG, 0, 2)):
            names.append(name)
            entries[name] = SimpleNamespace(st_mode=mode, st_uid=uid, st_nlink=links, st_mtime=-1, st_dev=1, st_ino=100)
        with mock.patch.object(BACKUP.os, "listdir", return_value=names), \
                mock.patch.object(BACKUP.os, "lstat", side_effect=lambda path: entries[os.path.basename(path)]), \
                mock.patch.object(BACKUP.os, "unlink") as unlink:
            BACKUP.Filesystem().rotate(BACKUP.Config.backups)
        removed = {os.path.basename(call[0][0]) for call in unlink.call_args_list}
        self.assertEqual(removed, set(names[:3]))

    def test_symlink_source_is_rejected_without_opening_target(self):
        def info(path):
            return SimpleNamespace(st_mode=stat.S_IFLNK if path == "/opt/link-vault/backups" else stat.S_IFDIR,
                                   st_uid=0)
        with mock.patch.object(BACKUP.os, "lstat", side_effect=info), mock.patch.object(BACKUP.os, "open") as opened:
            with self.assertRaises(BACKUP.BackupError):
                BACKUP.Filesystem().open("/opt/link-vault/backups/secret")
        opened.assert_not_called()


if __name__ == "__main__":
    unittest.main()
