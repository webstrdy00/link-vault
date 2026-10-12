#!/usr/bin/env python3
"""Pure-mock regression checks; no Docker, age, subprocesses or real disk writes."""

import contextlib
import datetime
import hashlib
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


def file_info(inode=1, mode=stat.S_IFREG | 0o600, size=100, uid=0, links=1):
    return SimpleNamespace(st_mode=mode, st_uid=uid, st_nlink=links, st_dev=1,
                           st_ino=inode, st_size=size, st_mtime_ns=1, st_ctime_ns=1)


class VerificationDisk:
    """In-memory POSIX fixtures exercising the real receipt/hash/prune code."""
    def __init__(self):
        self.files, self.infos, self.handles, self.read_sizes = {}, {}, {}, []
        self.removed = []
        for path in ("/", "/opt", BACKUP.Config.root):
            self.infos[path] = file_info(mode=stat.S_IFDIR | 0o755)
        self.infos[BACKUP.Config.backups] = file_info(mode=stat.S_IFDIR | 0o700)

    def add(self, name, value, **kwargs):
        path = BACKUP.Config.backups + "/" + name
        self.files[path] = value
        self.infos[path] = file_info(inode=len(self.infos) + 1, size=len(value), **kwargs)
        return path

    def lstat(self, path):
        if path not in self.infos:
            raise FileNotFoundError()
        return SimpleNamespace(**vars(self.infos[path]))

    def open(self, path, flags, mode=0o600):
        self.lstat(path)
        descriptor = len(self.handles) + 100
        self.handles[descriptor] = path
        return descriptor

    def fdopen(self, descriptor, mode):
        disk, path = self, self.handles[descriptor]

        class Stream(io.BytesIO):
            def fileno(self):
                return descriptor

            def read(self, size=-1):
                if size < 0:
                    raise AssertionError("Unbounded fixture read")
                disk.read_sizes.append(size)
                return super().read(size)

            def close(self):
                if "w" in mode and not self.closed:
                    disk.files[path] = self.getvalue()
                    disk.infos[path].st_size = len(disk.files[path])
                super().close()

        return Stream(b"" if "w" in mode else self.files[path])

    def unlink(self, path):
        self.removed.append(path)
        del self.files[path]
        del self.infos[path]

    def link(self, source, destination, **kwargs):
        if destination in self.infos:
            raise FileExistsError()
        self.files[destination], self.infos[destination] = self.files[source], self.infos[source]

    def replace(self, source, destination):
        self.files[destination], self.infos[destination] = self.files[source], self.infos[source]
        self.unlink(source)

    def mkstemp(self, prefix, dir):
        path = self.add(prefix + "fixture", b"")
        return self.open(path, os.O_WRONLY), path

    @contextlib.contextmanager
    def scandir(self, path):
        yield iter(SimpleNamespace(name=name[len(path) + 1:]) for name in list(self.files) if os.path.dirname(name) == path)

    @contextlib.contextmanager
    def patched(self):
        with contextlib.ExitStack() as patches:
            replacements = {"lstat": self.lstat, "open": self.open, "fdopen": self.fdopen,
                            "fstat": lambda descriptor: self.lstat(self.handles[descriptor]),
                            "scandir": self.scandir, "unlink": self.unlink, "link": self.link,
                            "replace": self.replace, "fsync": lambda descriptor: None,
                            "close": lambda descriptor: None}
            for name, value in replacements.items():
                patches.enter_context(mock.patch.object(BACKUP.os, name, side_effect=value))
            for name, value in (("O_NOFOLLOW", 0x100000), ("O_DIRECTORY", 0x200000), ("O_NONBLOCK", 0x400000)):
                patches.enter_context(mock.patch.object(BACKUP.os, name, value, create=True))
            patches.enter_context(mock.patch.object(BACKUP.os.path, "abspath", side_effect=lambda path: path))
            patches.enter_context(mock.patch.object(BACKUP.os.path, "join", side_effect=lambda *parts: "/".join(parts)))
            patches.enter_context(mock.patch.object(BACKUP.os.path, "lexists", side_effect=lambda path: path in self.infos))
            patches.enter_context(mock.patch.object(BACKUP.tempfile, "mkstemp", side_effect=self.mkstemp))
            yield self


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
        self.receipt = None

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

    def recovery_receipt(self, backups, deadline, now):
        deadline.remaining()
        return self.receipt

    def rotate(self, backups, receipt, deadline, now):
        deadline.remaining()
        if receipt != self.receipt:
            raise BACKUP.BackupError()
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
        lines = errors.getvalue().splitlines(True)
        self.assertEqual(lines.pop(0), "BACKUP_RECOVERY_UNVERIFIED\n")
        if stream and code == 0:
            line = lines.pop(0)
            self.assertTrue(line.startswith("BACKUP_SNAPSHOT_NAME="))
            name = line[len("BACKUP_SNAPSHOT_NAME="):].strip()
            self.assertTrue(BACKUP.OWNED_NAME.fullmatch(name))
            self.assertIn(BACKUP.Config.backups + "/" + name, backup.fs.files)
        return code, output.getvalue(), "".join(lines)

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
        self.assertIn("including deletions requested after capture", filesystem.manifest["restore_instructions"])
        self.assertIn("MUST block service restoration", filesystem.manifest["restore_instructions"])
        self.assertIn("Keep application traffic and cron/worker writers disabled", filesystem.manifest["restore_instructions"])
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

    def test_expired_verified_status_precedes_capture_prepare_and_unresolved_failures(self):
        for failure in ("dump", "prepare", "unresolved"):
            with self.subTest(failure=failure):
                filesystem, runner, backup = self.fixture("dump" if failure == "dump" else None)
                backup.wallclock = lambda: datetime.datetime(2030, 1, 31, 12)
                filesystem.receipt = {"snapshot": "link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age"}
                if failure == "prepare":
                    filesystem.prepare = mock.Mock(side_effect=RuntimeError(SECRET))
                if failure == "unresolved":
                    filesystem.files[BACKUP.Config.root + "/deploy-unresolved.json"] = b"unresolved"
                output, errors = io.BytesIO(), io.StringIO()
                self.assertEqual(BACKUP.main([], backup, output, errors, lambda: 0), 1)
                self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_EXPIRED\nBACKUP_FAILED\n")
                self.assertEqual(output.getvalue(), b"")
                self.assertFalse(any(event[0] == "rotate" for event in filesystem.events))
                if failure != "dump":
                    self.assertEqual(runner.calls, [])

    def test_invalid_verification_blocks_capture_and_pruning(self):
        filesystem, runner, backup = self.fixture()
        filesystem.recovery_receipt = mock.Mock(side_effect=RuntimeError(SECRET))
        output, errors = io.BytesIO(), io.StringIO()
        self.assertEqual(BACKUP.main(["--stream"], backup, output, errors, lambda: 0), 1)
        self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_INVALID\nBACKUP_FAILED\n")
        self.assertEqual(output.getvalue(), b"")
        self.assertEqual(runner.calls, [])
        self.assertFalse(any(event[0] in ("prepare", "publish", "rotate") for event in filesystem.events))

    def test_capture_failure_crossing_exact_age_boundary_still_reports_expiry(self):
        filesystem, runner, backup = self.fixture("dump")
        now = datetime.datetime(2030, 1, 31, 11, 59, 59)
        filesystem.receipt = {"snapshot": "link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age"}
        backup.wallclock = lambda: now
        original = runner.run

        def fail_after_expiry(command, **kwargs):
            nonlocal now
            if "pg_dump" in command:
                now += datetime.timedelta(seconds=1)
            return original(command, **kwargs)

        runner.run = fail_after_expiry
        output, errors = io.BytesIO(), io.StringIO()
        self.assertEqual(BACKUP.main([], backup, output, errors, lambda: 0), 1)
        self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_VERIFIED\nBACKUP_RECOVERY_EXPIRED\nBACKUP_FAILED\n")
        self.assertEqual(output.getvalue(), b"")
        self.assertFalse(any(event[0] == "rotate" for event in filesystem.events))

    def test_new_capture_leaves_expired_verification_unchanged(self):
        filesystem, runner, backup = self.fixture()
        backup.wallclock = lambda: datetime.datetime(2030, 1, 31, 12)
        receipt = {"snapshot": "link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age"}
        filesystem.receipt = receipt
        output, errors = io.BytesIO(), io.StringIO()
        self.assertEqual(BACKUP.main([], backup, output, errors, lambda: 0), 0)
        self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_EXPIRED\nBACKUP_OK\n")
        self.assertTrue(BACKUP.OWNED_NAME.fullmatch(output.getvalue().decode("ascii").strip()))
        self.assertIs(filesystem.receipt, receipt)
        self.assertTrue(any(event[0] == "rotate" for event in filesystem.events))

    def test_operator_attestation_locks_deploy_then_backup_without_capture(self):
        filesystem, runner, backup = self.fixture()
        now = datetime.datetime(2030, 1, 31, 12)
        name = "link-vault-20300131T120000Z-aaaaaaaaaaaaaaaa.tar.age"
        backup.wallclock = lambda: now
        filesystem.receipt = {"snapshot": name}
        filesystem.attest = mock.Mock()
        errors = io.StringIO()
        self.assertEqual(BACKUP.main(["--attest-offserver-verified", name, "a" * 64], backup, io.BytesIO(), errors, lambda: 0), 0)
        args = filesystem.attest.call_args[0]
        self.assertEqual(args[:3], (BACKUP.Config.backups, name, "a" * 64))
        self.assertEqual(args[3].remaining(), BACKUP.VERIFICATION_SECONDS)
        self.assertEqual(args[4], now)
        self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_VERIFIED\nBACKUP_ATTESTED\n")
        self.assertEqual([event[1] for event in filesystem.events if event[0] == "lock"],
                         [BACKUP.Config.root + "/deploy.lock", BACKUP.Config.root + "/backup.lock"])
        self.assertEqual(runner.calls, [])
        self.assertFalse(any(event[0] in ("prepare", "temporary", "publish", "rotate") for event in filesystem.events))

    def test_attestation_cli_is_explicit_root_only_and_does_not_capture(self):
        backup = mock.Mock()
        name = "link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age"
        for args, uid, expected in ((["--attest-offserver-verified", name], 0, "BACKUP_USAGE\n"),
                                    (["--attest-offserver-verified", name, "a" * 64], 1000, "BACKUP_ROOT_REQUIRED\n")):
            errors = io.StringIO()
            self.assertNotEqual(BACKUP.main(args, backup, io.BytesIO(), errors, lambda: uid), 0)
            self.assertEqual(errors.getvalue(), expected)
        backup.attest.assert_not_called()
        errors = io.StringIO()
        self.assertEqual(BACKUP.main(["--attest-offserver-verified", name, "a" * 64], backup, io.BytesIO(), errors, lambda: 0), 0)
        backup.attest.assert_called_once_with(name, "a" * 64, errors)
        backup.execute.assert_not_called()
        self.assertEqual(errors.getvalue(), "BACKUP_ATTESTED\n")


class VerificationTests(unittest.TestCase):
    def fixture(self):
        disk, fs = VerificationDisk(), BACKUP.Filesystem()
        name = "link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age"
        path = disk.add(name, BACKUP.AGE_HEADER + b"verified ciphertext")
        digest = hashlib.sha256(disk.files[path]).hexdigest()
        return disk, fs, name, path, digest, datetime.datetime(2030, 1, 31, 12)

    def deadline(self):
        return BACKUP.Deadline(100, lambda: 0)

    def test_attestation_round_trip_binds_exact_name_hash_inode_and_capture_expiry(self):
        disk, fs, name, path, digest, now = self.fixture()
        with disk.patched():
            receipt = fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
            self.assertEqual(fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now), receipt)
            self.assertEqual(receipt["sha256"], digest)
            self.assertEqual(receipt["snapshot"], name)
            self.assertEqual((receipt["device"], receipt["inode"]), (disk.infos[path].st_dev, disk.infos[path].st_ino))
            errors = io.StringIO()
            BACKUP.Backup(filesystem=fs).recovery_status(errors, self.deadline(), now)
            self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_EXPIRED\n")
            errors = io.StringIO()
            # A newer attestation does not renew the old snapshot's capture age.
            BACKUP.Backup(filesystem=fs).recovery_status(errors, self.deadline(), now + datetime.timedelta(days=1))
            self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_EXPIRED\n")
        self.assertTrue(all(size <= 1024 * 1024 for size in disk.read_sizes))
        self.assertEqual(disk.infos[BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME].st_mode, stat.S_IFREG | 0o600)
        self.assertFalse(any(".verification-" in path for path in disk.files))

    def test_replacing_receipt_requires_new_explicit_attestation_not_new_capture(self):
        disk, fs, name, path, digest, now = self.fixture()
        fresh_name = "link-vault-20300131T120000Z-bbbbbbbbbbbbbbbb.tar.age"
        fresh_path = disk.add(fresh_name, BACKUP.AGE_HEADER + b"fresh ciphertext")
        with disk.patched():
            old = fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
            self.assertEqual(fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now), old)
            fresh = fs.attest(BACKUP.Config.backups, fresh_name, hashlib.sha256(disk.files[fresh_path]).hexdigest(), self.deadline(), now)
            errors = io.StringIO()
            BACKUP.Backup(filesystem=fs).recovery_status(errors, self.deadline(), now)
            self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_VERIFIED\n")
            fs.rotate(BACKUP.Config.backups, fresh, self.deadline(), now)
        self.assertNotIn(path, disk.files)
        self.assertIn(fresh_path, disk.files)

    def test_absence_is_unverified_even_with_age_files_and_receipt_symlink_is_invalid(self):
        disk, fs, name, path, digest, now = self.fixture()
        with disk.patched():
            self.assertIsNone(fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now))
            receipt_path = disk.add(BACKUP.RECEIPT_NAME, b"outside", mode=stat.S_IFLNK | 0o600)
            with self.assertRaises(BACKUP.BackupError):
                fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now)
            with self.assertRaises(BACKUP.BackupError):
                fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
        self.assertEqual(disk.files[receipt_path], b"outside")

    def test_receipt_schema_size_duplicates_future_date_inode_and_hash_fail_closed(self):
        alterations = ({"sha256": "0" * 64}, {"inode": 999}, {"device": 999},
                       {"snapshot": "../../outside"}, {"format_version": True}, {"inode": True},
                       {"verified_at": "2030-02-01T12:00:00Z"}, {"verified_at": "2029-12-31T12:00:00Z"},
                       {"unexpected": SECRET})
        for alteration in alterations:
            disk, fs, name, path, digest, now = self.fixture()
            with disk.patched():
                receipt = fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
                receipt.update(alteration)
                disk.add(BACKUP.RECEIPT_NAME, json.dumps(receipt).encode("ascii"))
                with self.assertRaises((BACKUP.BackupError, ValueError)):
                    fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now)
        for raw in (b"{}", b"x" * 4097, b'{"snapshot":"one","snapshot":"two"}', b"[]", b"\xff"):
            disk, fs, name, path, digest, now = self.fixture()
            disk.add(BACKUP.RECEIPT_NAME, raw)
            with disk.patched():
                with self.assertRaises((BACKUP.BackupError, ValueError)):
                    fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now)
                with self.assertRaises((BACKUP.BackupError, ValueError)):
                    fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
            self.assertEqual(disk.files[BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME], raw)

    def test_symlink_hardlink_nonprivate_nonroot_and_writable_ancestor_rejected(self):
        for target, field, value in (("snapshot", "st_mode", stat.S_IFLNK | 0o600),
                                     ("snapshot", "st_nlink", 2), ("snapshot", "st_uid", 1000),
                                     ("snapshot", "st_mode", stat.S_IFREG | 0o644),
                                     ("receipt", "st_mode", stat.S_IFREG | 0o640),
                                     ("receipt", "st_nlink", 2), ("receipt", "st_uid", 1000),
                                     ("backups", "st_mode", stat.S_IFDIR | 0o755),
                                     ("ancestor", "st_mode", stat.S_IFLNK | 0o755),
                                     ("ancestor", "st_mode", stat.S_IFDIR | 0o777)):
            with self.subTest(target=target, field=field, value=value):
                disk, fs, name, path, digest, now = self.fixture()
                with disk.patched():
                    fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
                    target_path = {"snapshot": path, "receipt": BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME,
                                   "backups": BACKUP.Config.backups, "ancestor": "/opt"}[target]
                    setattr(disk.infos[target_path], field, value)
                    with self.assertRaises(BACKUP.BackupError):
                        fs.recovery_receipt(BACKUP.Config.backups, self.deadline(), now)

    def test_attestation_refuses_traversal_wrong_digest_invalid_date_or_future_capture(self):
        disk, fs, name, path, digest, now = self.fixture()
        for candidate, checksum in (("../../outside", digest), (path, digest), ("other.tar.age", digest),
                                    ("link-vault-20300230T120000Z-aaaaaaaaaaaaaaaa.tar.age", digest),
                                    ("link-vault-20300201T120000Z-aaaaaaaaaaaaaaaa.tar.age", digest),
                                    (name, "0" * 64), (name, digest.upper()), (name, "x")):
            with disk.patched():
                with self.assertRaises((BACKUP.BackupError, ValueError)):
                    fs.attest(BACKUP.Config.backups, candidate, checksum, self.deadline(), now)
            self.assertNotIn(BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME, disk.files)

    def test_hash_size_and_deadline_limits_and_non_age_file_fail_before_receipt_write(self):
        for limit, content, deadline in ((30, BACKUP.AGE_HEADER + b"large ciphertext", self.deadline()),
                                         (100, b"not-an-age-header" * 3, self.deadline()),
                                         (100, BACKUP.AGE_HEADER + b"ciphertext", BACKUP.Deadline(0, lambda: 1))):
            disk, fs, name, path, digest, now = self.fixture()
            disk.files[path] = content
            disk.infos[path].st_size = len(content)
            with disk.patched(), mock.patch.object(BACKUP, "MAX_SNAPSHOT_BYTES", limit):
                with self.assertRaises(BACKUP.BackupError):
                    fs.attest(BACKUP.Config.backups, name, hashlib.sha256(content).hexdigest(), deadline, now)
            self.assertNotIn(BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME, disk.files)

    def test_inode_replacement_between_check_and_open_rejected_without_reading(self):
        disk, fs, name, path, digest, now = self.fixture()
        original = disk.open

        def replace_before_open(candidate, flags, mode=0o600):
            if candidate == path:
                disk.infos[path].st_ino += 1
            return original(candidate, flags, mode)

        with disk.patched(), mock.patch.object(BACKUP.os, "open", side_effect=replace_before_open):
            with self.assertRaises(BACKUP.BackupError):
                fs.snapshot_hash(BACKUP.Config.backups, name, self.deadline())
        self.assertEqual(disk.read_sizes, [])

    def test_atomic_receipt_write_failure_preserves_prior_receipt_and_all_snapshots(self):
        disk, fs, name, path, digest, now = self.fixture()
        fresh_name = "link-vault-20300131T120000Z-bbbbbbbbbbbbbbbb.tar.age"
        fresh_path = disk.add(fresh_name, BACKUP.AGE_HEADER + b"fresh ciphertext")
        with disk.patched():
            fs.attest(BACKUP.Config.backups, name, digest, self.deadline(), now)
            old = disk.files[BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME]
            with mock.patch.object(BACKUP.os, "replace", side_effect=OSError(SECRET)):
                with self.assertRaises(OSError):
                    fs.attest(BACKUP.Config.backups, fresh_name, hashlib.sha256(disk.files[fresh_path]).hexdigest(), self.deadline(), now)
        self.assertEqual(disk.files[BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME], old)
        self.assertIn(path, disk.files)
        self.assertIn(fresh_path, disk.files)
        self.assertFalse(any(".verification-" in path for path in disk.files))


class RetentionTests(unittest.TestCase):
    def test_rotation_removes_only_old_completed_root_owned_regular_single_link_files(self):
        disk, names = VerificationDisk(), []
        for index in range(10):
            name = "link-vault-20261007T120000Z-" + format(index, "016x") + ".tar.age"
            names.append(name)
            disk.add(name, BACKUP.AGE_HEADER + b"ciphertext")
        for name, mode, uid, links in (
                ("other.tar.age", stat.S_IFREG | 0o600, 0, 1),
                (".incomplete-example.tar.age", stat.S_IFREG | 0o600, 0, 1),
                ("link-vault-20260230T120000Z-dddddddddddddddd.tar.age", stat.S_IFREG | 0o600, 0, 1),
                ("link-vault-20261007T120000Z-aaaaaaaaaaaaaaaa.tar.age", stat.S_IFLNK, 0, 1),
                ("link-vault-20261007T120000Z-bbbbbbbbbbbbbbbb.tar.age", stat.S_IFREG | 0o600, 1000, 1),
                ("link-vault-20261007T120000Z-cccccccccccccccc.tar.age", stat.S_IFREG | 0o600, 0, 2),
                ("link-vault-20261007T120000Z-eeeeeeeeeeeeeeee.tar.age", stat.S_IFREG | 0o644, 0, 1)):
            disk.add(name, b"user-data", mode=mode, uid=uid, links=links)
        fs, deadline, now = BACKUP.Filesystem(), BACKUP.Deadline(100, lambda: 0), datetime.datetime(2026, 10, 8)
        with disk.patched():
            verified = BACKUP.Config.backups + "/" + names[-1]
            receipt = fs.attest(BACKUP.Config.backups, names[-1], hashlib.sha256(disk.files[verified]).hexdigest(), deadline, now)
            disk.removed.clear()
            fs.rotate(BACKUP.Config.backups, receipt, deadline, now)
        removed = {os.path.basename(path) for path in disk.removed}
        self.assertEqual(removed, set(names[:3]))

    def test_age_boundary_and_unrecognized_data_are_not_an_exception_invention(self):
        disk = VerificationDisk()
        old = disk.add("link-vault-20300101T120000Z-aaaaaaaaaaaaaaaa.tar.age", BACKUP.AGE_HEADER + b"old")
        fresh_name = "link-vault-20300101T120001Z-bbbbbbbbbbbbbbbb.tar.age"
        fresh = disk.add(fresh_name, BACKUP.AGE_HEADER + b"fresh")
        user = disk.add("my-verified.tar.age", SECRET.encode("ascii"))
        fs, deadline, now = BACKUP.Filesystem(), BACKUP.Deadline(100, lambda: 0), datetime.datetime(2030, 1, 31, 12)
        with disk.patched():
            receipt = fs.attest(BACKUP.Config.backups, fresh_name, hashlib.sha256(disk.files[fresh]).hexdigest(), deadline, now)
            disk.removed.clear()
            fs.rotate(BACKUP.Config.backups, receipt, deadline, now)
        self.assertEqual(disk.removed, [old])
        self.assertIn(fresh, disk.files)
        self.assertIn(user, disk.files)

    def test_no_verification_receipt_preserves_all_eligible_snapshots_over_age_and_count_limits(self):
        disk, fs = VerificationDisk(), BACKUP.Filesystem()
        for index in range(10):
            disk.add("link-vault-20290101T120000Z-" + format(index, "016x") + ".tar.age",
                     BACKUP.AGE_HEADER + b"potentially recoverable but unverified")
        disk.add("operator-data", SECRET.encode("ascii"))
        before = dict(disk.files)
        deadline, now = BACKUP.Deadline(100, lambda: 0), datetime.datetime(2030, 1, 31, 12)
        with disk.patched():
            self.assertIsNone(fs.recovery_receipt(BACKUP.Config.backups, deadline, now))
            fs.rotate(BACKUP.Config.backups, None, deadline, now)
            errors = io.StringIO()
            BACKUP.Backup(filesystem=fs).recovery_status(errors, deadline, now)
            self.assertEqual(errors.getvalue(), "BACKUP_RECOVERY_UNVERIFIED\n")
        self.assertEqual(disk.files, before)
        self.assertEqual(disk.removed, [])
        self.assertNotIn(BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME, disk.files)

    def test_count_and_age_pruning_preserve_exactly_last_verified_old_copy(self):
        disk, fs = VerificationDisk(), BACKUP.Filesystem()
        protected_name = "link-vault-20290101T120000Z-aaaaaaaaaaaaaaaa.tar.age"
        protected = disk.add(protected_name, BACKUP.AGE_HEADER + b"independently verified")
        other_old = disk.add("link-vault-20290102T120000Z-bbbbbbbbbbbbbbbb.tar.age", BACKUP.AGE_HEADER + b"unverified")
        for index in range(10):
            disk.add("link-vault-20300131T120000Z-" + format(index, "016x") + ".tar.age", BACKUP.AGE_HEADER + b"new but not verified")
        now, deadline = datetime.datetime(2030, 1, 31, 12), BACKUP.Deadline(100, lambda: 0)
        with disk.patched():
            receipt = fs.attest(BACKUP.Config.backups, protected_name, hashlib.sha256(disk.files[protected]).hexdigest(), deadline, now)
            disk.removed.clear()
            fs.rotate(BACKUP.Config.backups, receipt, deadline, now)
        self.assertIn(protected, disk.files)
        self.assertNotIn(other_old, disk.files)
        self.assertEqual(len([path for path in disk.files if BACKUP.OWNED_NAME.fullmatch(os.path.basename(path))]), 8)
        self.assertEqual(json.loads(disk.files[BACKUP.Config.backups + "/" + BACKUP.RECEIPT_NAME]), receipt)

    def test_pruning_rejects_receipt_hash_or_inode_change_before_any_deletion(self):
        for field in ("ciphertext", "inode"):
            disk, fs = VerificationDisk(), BACKUP.Filesystem()
            name = "link-vault-20290101T120000Z-aaaaaaaaaaaaaaaa.tar.age"
            path = disk.add(name, BACKUP.AGE_HEADER + b"verified")
            disk.add("link-vault-20290102T120000Z-bbbbbbbbbbbbbbbb.tar.age", BACKUP.AGE_HEADER + b"old")
            now, deadline = datetime.datetime(2030, 1, 31, 12), BACKUP.Deadline(100, lambda: 0)
            with disk.patched():
                receipt = fs.attest(BACKUP.Config.backups, name, hashlib.sha256(disk.files[path]).hexdigest(), deadline, now)
                disk.removed.clear()
                if field == "ciphertext":
                    disk.files[path] = BACKUP.AGE_HEADER + b"tampered"
                else:
                    disk.infos[path].st_ino += 1
                with self.assertRaises(BACKUP.BackupError):
                    fs.rotate(BACKUP.Config.backups, receipt, deadline, now)
            self.assertEqual(disk.removed, [])

    def test_pruning_entry_limit_and_deadline_fail_before_deleting(self):
        disk, fs = VerificationDisk(), BACKUP.Filesystem()
        name = "link-vault-20290101T120000Z-aaaaaaaaaaaaaaaa.tar.age"
        path = disk.add(name, BACKUP.AGE_HEADER + b"old")
        for index in range(3):
            disk.add("user-file-" + str(index), b"user")
        now = datetime.datetime(2030, 1, 31)
        with disk.patched():
            receipt = fs.attest(BACKUP.Config.backups, name, hashlib.sha256(disk.files[path]).hexdigest(), BACKUP.Deadline(100, lambda: 0), now)
            disk.removed.clear()
        for deadline, limit in ((BACKUP.Deadline(100, lambda: 0), 2), (BACKUP.Deadline(0, lambda: 1), 10000)):
            with disk.patched(), mock.patch.object(BACKUP, "MAX_BACKUP_ENTRIES", limit):
                with self.assertRaises(BACKUP.BackupError):
                    fs.rotate(BACKUP.Config.backups, receipt, deadline, now)
            self.assertEqual(disk.removed, [])

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
