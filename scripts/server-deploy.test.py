#!/usr/bin/python3
"""Pure receiver tests: temporary files and mocks, never Docker or live secrets."""

import contextlib
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tarfile
import tempfile
import types
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "server_deploy", str(Path(__file__).with_name("server-deploy.py")))
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)
COMMIT = "a" * 40
OLD = "202609130001"
NEW = "202609130002"
KEY = "synthetic-anonymous-key-for-tests"


def fixture_files():
    return {
        deploy.APP + "/index.ts": b"export const version = 2;\n",
        deploy.APP + "/deno.json": b'{"imports":{"example":"npm:example@1"}}',
        deploy.MIGRATIONS + "/" + OLD + "_first.sql": b"SELECT 1;\n",
    }


def raw_archive(entries, comment=None):
    target = io.BytesIO()
    with tarfile.open(fileobj=target, mode="w", format=tarfile.PAX_FORMAT,
                      pax_headers={} if comment is None else {"comment": comment}) as archive:
        for entry in entries:
            if isinstance(entry, tuple):
                name, content = entry
                member = tarfile.TarInfo(name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
            else:
                archive.addfile(entry)
    return target.getvalue()


def archive_for(extra=(), files=None, comment=None):
    entries = list((fixture_files() if files is None else files).items()) + list(extra)
    return gzip.compress(raw_archive(entries, comment))


def hashes(migrations):
    return {version: {"name": item["name"], "sha256": item["sha256"]}
            for version, item in migrations.items()}


@contextlib.contextmanager
def unlocked(root):
    yield


class ReceiverCase(unittest.TestCase):
    def assert_code(self, code, function, *args, **kwargs):
        with self.assertRaises(deploy.DeployError) as caught:
            function(*args, **kwargs)
        self.assertEqual(caught.exception.code, code)


class ArchiveTests(ReceiverCase):
    def test_valid_archive_is_data_only_with_implicit_parent_directories(self):
        files = fixture_files()
        files[deploy.APP + "/nested/source.ts"] = b"export {};"
        files[deploy.APP + "/decoder.wasm"] = b"\x00asm"
        actual, directories = deploy.validate_archive(archive_for(files=files))
        self.assertEqual(actual, files)
        self.assertEqual(directories, {"supabase", "supabase/functions", deploy.APP,
                                       deploy.MIGRATIONS, deploy.APP + "/nested"})

    def test_parent_directories_from_git_and_matching_commit_are_accepted(self):
        entries = []
        for path in ("supabase", "supabase/functions", deploy.APP, deploy.MIGRATIONS):
            member = tarfile.TarInfo(path + "/")
            member.type = tarfile.DIRTYPE
            entries.append(member)
        files, _ = deploy.validate_archive(archive_for(entries, comment=COMMIT), COMMIT)
        self.assertEqual(files, fixture_files())
        self.assert_code("ARCHIVE_COMMIT_MISMATCH", deploy.validate_archive,
                         archive_for(comment="b" * 40), COMMIT)

    def test_path_traversal_absolute_windows_and_ambiguous_paths_are_rejected(self):
        for name in ("/etc/passwd", "../bad.ts", deploy.APP + "/../bad.ts",
                     deploy.APP + "/./bad.ts", deploy.APP + "//bad.ts",
                     deploy.APP + "/bad\\path.ts", "C:/bad.ts",
                     deploy.APP + "/" + "a/" * 16 + "bad.ts",
                     deploy.APP + "/" + ("a" * 200 + "/") * 6 + "bad.ts"):
            with self.subTest(name=name):
                self.assert_code("ARCHIVE_UNSAFE_PATH", deploy.validate_archive,
                                 archive_for([(name, b"bad")]))

    def test_links_devices_fifo_and_sparse_entries_are_rejected(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE,
                     tarfile.BLKTYPE, tarfile.FIFOTYPE, tarfile.GNUTYPE_SPARSE):
            with self.subTest(kind=kind):
                member = tarfile.TarInfo(deploy.APP + "/bad.ts")
                member.type = kind
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    member.linkname = "/etc/passwd"
                with self.assertRaises(deploy.DeployError):
                    deploy.validate_archive(archive_for([member]))

    def test_only_app_data_and_flat_sql_migrations_are_accepted(self):
        for name in ("scripts/deploy.sh", "supabase/docker/docker-compose.yml",
                     deploy.APP + "/startup.sh", deploy.APP + "/compose.yml",
                     deploy.MIGRATIONS + "/nested/123_test.sql",
                     deploy.MIGRATIONS + "/README.md", "supabase/evil.ts"):
            with self.subTest(name=name):
                self.assert_code("ARCHIVE_UNEXPECTED_NAME", deploy.validate_archive,
                                 archive_for([(name, b"bad")]))
        member = tarfile.TarInfo("supabase/docker")
        member.type = tarfile.DIRTYPE
        self.assert_code("ARCHIVE_UNEXPECTED_NAME", deploy.validate_archive,
                         archive_for([member]))

    def test_duplicate_files_directories_and_file_parent_collisions_are_rejected(self):
        self.assert_code("ARCHIVE_MEMBER_LIMIT_OR_DUPLICATE", deploy.validate_archive,
                         archive_for([(deploy.APP + "/index.ts", b"other")]))
        directory = tarfile.TarInfo(deploy.APP)
        directory.type = tarfile.DIRTYPE
        self.assert_code("ARCHIVE_MEMBER_LIMIT_OR_DUPLICATE", deploy.validate_archive,
                         archive_for([directory, directory]))
        self.assert_code("ARCHIVE_PATH_CONFLICT", deploy.validate_archive,
                         archive_for([(deploy.APP + "/source.ts", b"one"),
                                      (deploy.APP + "/source.ts/nested.ts", b"two")]))

    def test_compressed_expanded_and_member_limits_fail_closed_at_boundary(self):
        compressed = archive_for()
        expanded = gzip.decompress(compressed)
        with mock.patch.object(deploy, "MAX_COMPRESSED", len(compressed)):
            deploy.validate_archive(compressed)
        with mock.patch.object(deploy, "MAX_COMPRESSED", len(compressed) - 1):
            self.assert_code("ARCHIVE_COMPRESSED_LIMIT", deploy.validate_archive, compressed)
        with mock.patch.object(deploy, "MAX_EXPANDED", len(expanded)):
            deploy.validate_archive(compressed)
        with mock.patch.object(deploy, "MAX_EXPANDED", len(expanded) - 1):
            self.assert_code("ARCHIVE_EXPANDED_LIMIT", deploy.validate_archive, compressed)
        with mock.patch.object(deploy, "MAX_MEMBERS", 7):
            deploy.validate_archive(compressed)
        with mock.patch.object(deploy, "MAX_MEMBERS", 6):
            self.assert_code("ARCHIVE_MEMBER_LIMIT_OR_DUPLICATE", deploy.validate_archive,
                             compressed)

    def test_truncated_corrupt_and_hidden_concatenated_tar_payloads_are_rejected(self):
        compressed = archive_for()
        for malformed in (b"", b"not gzip", compressed[:-5],
                          gzip.compress(b"not tar")):
            with self.subTest(malformed=malformed[:8]):
                self.assert_code("ARCHIVE_INVALID", deploy.validate_archive, malformed)
        hidden = gzip.compress(raw_archive(list(fixture_files().items())) + b"x" * 512)
        self.assert_code("ARCHIVE_INVALID_END", deploy.validate_archive, hidden)
        self.assert_code("ARCHIVE_INVALID_END", deploy.validate_archive,
                         compressed + compressed)

    def test_required_entrypoint_import_map_and_migrations_cannot_be_omitted(self):
        for name in fixture_files():
            files = fixture_files()
            del files[name]
            self.assert_code("ARCHIVE_REQUIRED_FILES_MISSING", deploy.validate_archive,
                             archive_for(files=files))

    def test_protocol_header_is_strict_bounded_and_normalized(self):
        actual = deploy.read_payload(io.BytesIO(
            json.dumps({"commit": COMMIT.upper()}).encode() + b"\n" + archive_for()))
        self.assertEqual(actual[0], COMMIT)
        self.assertEqual(actual[1], fixture_files())
        for line in (b"{}\n", b"[]\n", b"null\n", b"not-json\n", b"\xff\n",
                     b'{"commit":12}\n', b'{"commit":"short"}\n',
                     b'{"commit":"' + COMMIT.encode() + b'","other":true}\n',
                     b" " * 1025 + b"\n", b"{}"):
            with self.subTest(line=line[:40]):
                self.assert_code("INVALID_DEPLOY_HEADER", deploy.read_payload,
                                 io.BytesIO(line + archive_for()))
        duplicate = b'{"commit":"' + COMMIT.encode() + b'","commit":"x"}\n'
        self.assert_code("DUPLICATE_JSON_KEY", deploy.read_payload,
                         io.BytesIO(duplicate + archive_for()))
        with mock.patch.object(deploy, "MAX_COMPRESSED", 2):
            self.assert_code("ARCHIVE_COMPRESSED_LIMIT", deploy.read_payload,
                             io.BytesIO(json.dumps({"commit": COMMIT}).encode() +
                                        b"\n" + archive_for()))


class MigrationTests(ReceiverCase):
    def test_transaction_controls_and_psql_meta_commands_are_rejected(self):
        controls = ("BEGIN;", "COMMIT;", "ROLLBACK;", "END;", "ABORT;",
                    "START TRANSACTION;", "SAVEPOINT one;", "RELEASE one;",
                    "PREPARE TRANSACTION 'x';", "SET TRANSACTION READ ONLY;",
                    "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;",
                    "SELECT 1; /* outer /* nested */ */ COMMIT;")
        for sql in controls:
            with self.subTest(sql=sql):
                self.assert_code("MIGRATION_TRANSACTION_CONTROL_FORBIDDEN",
                                 deploy.check_migration_sql, sql)
        for sql in ("\\connect other\nSELECT 1;", "SELECT 1; \\gexec\n"):
            self.assert_code("MIGRATION_PSQL_COMMAND_FORBIDDEN",
                             deploy.check_migration_sql, sql)

    def test_function_bodies_comments_and_quoted_data_are_not_transaction_commands(self):
        deploy.check_migration_sql(
            "-- BEGIN;\n/* COMMIT; /* nested */ */\n"
            "CREATE FUNCTION x() RETURNS void LANGUAGE plpgsql AS $body$\n"
            "BEGIN PERFORM 1; END; $body$;\n"
            "SELECT 'COMMIT; \\connect', \"rollback\", 'it''s fine', E'it\\'s fine';\n"
            "DO $$ BEGIN PERFORM 'END;'; END; $$; -- ROLLBACK;")

    def test_unclosed_or_unterminated_sql_is_rejected_before_database_execution(self):
        for sql in ("", "SELECT 1", "SELECT 'unfinished;", "SELECT \"unfinished;",
                    "/* unfinished", "DO $body$ BEGIN END;", "SELECT '\x00';"):
            with self.subTest(sql=sql):
                self.assert_code("MIGRATION_SQL_INVALID", deploy.check_migration_sql, sql)

    def test_old_hash_name_removal_and_unknown_baseline_drift_fail(self):
        original = deploy.collect_migrations(fixture_files())
        history = [{"version": OLD, "name": "first"}]
        variants = []
        changed = fixture_files()
        changed[deploy.MIGRATIONS + "/" + OLD + "_first.sql"] = b"SELECT 2;\n"
        variants.append(deploy.collect_migrations(changed))
        renamed = {deploy.MIGRATIONS + "/" + OLD + "_renamed.sql": b"SELECT 1;\n"}
        variants.append(deploy.collect_migrations(renamed))
        variants.append({})
        for migrations in variants:
            self.assert_code("EXISTING_MIGRATION_DRIFT_OR_BASELINE_MISSING",
                             deploy.migration_plan, migrations, history, hashes(original))
        self.assert_code("EXISTING_MIGRATION_DRIFT_OR_BASELINE_MISSING",
                         deploy.migration_plan, original, history, {})

    def test_pending_hashes_only_constrain_versions_actually_applied(self):
        files = fixture_files()
        files[deploy.MIGRATIONS + "/" + NEW + "_second.sql"] = b"SELECT 2;\n"
        migrations = deploy.collect_migrations(files)
        known = hashes(migrations)
        history = [{"version": OLD, "name": "first"}]
        self.assertEqual(deploy.migration_plan(migrations, history, known), [NEW])
        history.append({"version": NEW, "name": "second"})
        self.assertEqual(deploy.migration_plan(migrations, history, known), [])
        known[NEW]["sha256"] = "0" * 64
        self.assert_code("EXISTING_MIGRATION_DRIFT_OR_BASELINE_MISSING",
                         deploy.migration_plan, migrations, history, known)

    def test_duplicate_versions_and_out_of_order_additions_fail(self):
        files = fixture_files()
        files[deploy.MIGRATIONS + "/" + OLD + "_duplicate.sql"] = b"SELECT 1;"
        self.assert_code("MIGRATION_VERSION_INVALID_OR_DUPLICATE",
                         deploy.collect_migrations, files)
        files = {deploy.MIGRATIONS + "/9_old.sql": b"SELECT 9;",
                 deploy.MIGRATIONS + "/10_next.sql": b"SELECT 10;",
                 deploy.MIGRATIONS + "/11_last.sql": b"SELECT 11;"}
        migrations = deploy.collect_migrations(files)
        self.assertEqual(deploy.migration_plan(
            migrations, [{"version": "9", "name": "old"}], hashes(migrations)),
            ["10", "11"])
        self.assert_code("MIGRATION_OUT_OF_ORDER", deploy.migration_plan, migrations,
                         [{"version": "11", "name": "last"}], hashes(migrations))

    def test_sources_and_nullable_history_are_in_one_single_transaction_command(self):
        migrations = deploy.collect_migrations(fixture_files())
        sql = deploy.migration_transaction(migrations, [OLD])
        self.assertIn("LOCK TABLE supabase_migrations.schema_migrations", sql)
        self.assertLess(sql.index("SELECT 1;"), sql.index("INSERT INTO"))
        self.assertIn("(version, name, statements)", sql)
        self.assertIn("('{}', 'first', NULL)".format(OLD), sql)
        with mock.patch.object(deploy, "run_command", return_value=b"") as runner, \
                mock.patch.object(deploy, "write_document"), \
                mock.patch.object(deploy, "settle_postgres"), \
                mock.patch.object(deploy.Path, "unlink"), \
                mock.patch.object(deploy, "sync_directory"):
            deploy.psql(Path("stack"), sql, transaction=True)
        arguments, stack, timeout, code, data = runner.call_args[0]
        self.assertIn("--single-transaction", arguments)
        self.assertIn("--set=ON_ERROR_STOP=1", arguments)
        self.assertIn("--no-psqlrc", arguments)
        self.assertIn("--username=supabase_admin", arguments)
        self.assertEqual(arguments[:4], [deploy.DOCKER, "exec", "-i", "--env"])
        self.assertTrue(arguments[4].startswith("PGAPPNAME=link-vault-deploy-"))
        self.assertEqual(arguments[5:12], ["supabase-db", "timeout", "-s", "TERM",
                                          "-k", "5", "840s"])
        self.assertEqual(data, sql.encode())
        self.assertEqual(code, "MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED")

    def test_history_is_content_free_and_validated(self):
        rows = [{"version": OLD, "name": "first"}]
        with mock.patch.object(deploy, "psql", return_value=json.dumps(rows).encode()) as psql:
            self.assertEqual(deploy.read_history(Path("stack")), rows)
        self.assertNotIn("statements", psql.call_args[0][1])
        for value in (None, {}, [{"version": OLD, "name": None}], rows + rows,
                      [{"version": "x", "name": "first"}]):
            with mock.patch.object(deploy, "psql", return_value=json.dumps(value).encode()):
                self.assert_code("MIGRATION_HISTORY_INVALID", deploy.read_history, Path("stack"))


class FilesystemTests(ReceiverCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: deploy.remove_tree(self.root))
        self.trust = mock.patch.object(deploy, "require_trusted")
        self.trust.start()
        self.addCleanup(self.trust.stop)

    def release(self):
        releases = self.root / "releases"
        releases.mkdir()
        files, directories = deploy.validate_archive(archive_for())
        return deploy.stage_release(releases, COMMIT, files, directories), files, directories

    def live(self):
        functions = self.root / "functions"
        (functions / "library-api").mkdir(parents=True)
        (functions / "library-api/index.ts").write_bytes(b"previous version")
        old_map = b'{"imports":{"old":"npm:old@1"},"compilerOptions":{"strict":true}}\n'
        (functions / "deno.jsonc").write_bytes(old_map)
        return functions, old_map

    def test_release_is_idempotent_but_content_changes_and_extra_paths_fail(self):
        release, files, directories = self.release()
        repeated = deploy.stage_release(release.parent, COMMIT, files, directories)
        self.assertEqual(repeated, release)
        self.assertEqual((release / deploy.APP / "index.ts").read_bytes(),
                         files[deploy.APP + "/index.ts"])
        changed = dict(files)
        changed[deploy.APP + "/index.ts"] = b"different"
        self.assert_code("IMMUTABLE_RELEASE_CONTENT_MISMATCH", deploy.stage_release,
                         release.parent, COMMIT, changed, directories)
        (release / deploy.APP).chmod(0o755)
        (release / deploy.APP / "extra").mkdir()
        self.assert_code("IMMUTABLE_RELEASE_CONTENT_MISMATCH", deploy.stage_release,
                         release.parent, COMMIT, files, directories)

    def test_success_installs_only_functions_and_matching_imports(self):
        release, files, _ = self.release()
        functions, old_map = self.live()
        new_map = deploy.deployment_import_map(files, old_map)
        with mock.patch.object(deploy, "restart_functions") as restart:
            deploy.activate_functions(release, functions, new_map, Path("stack"), lambda: True)
        self.assertEqual((functions / "library-api/index.ts").read_bytes(),
                         files[deploy.APP + "/index.ts"])
        config = json.loads((functions / "deno.jsonc").read_text())
        self.assertEqual(config["imports"], {"example": "npm:example@1"})
        self.assertEqual(config["compilerOptions"], {"strict": True})
        restart.assert_called_once_with(Path("stack"))
        self.assertFalse(list(functions.glob(".deploy-*")))

    def test_health_failure_restores_exact_previous_function_and_import_map_not_db(self):
        release, files, _ = self.release()
        functions, old_map = self.live()
        with mock.patch.object(deploy, "restart_functions") as restart:
            self.assert_code(
                "DEPLOY_HEALTH_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST",
                deploy.activate_functions, release, functions,
                deploy.deployment_import_map(files, old_map), Path("stack"), lambda: False)
        self.assertEqual((functions / "library-api/index.ts").read_bytes(), b"previous version")
        self.assertEqual((functions / "deno.jsonc").read_bytes(), old_map)
        self.assertEqual(restart.call_count, 2)
        self.assertFalse(list(functions.glob(".deploy-*")))

    def test_partial_rename_failure_restores_both_files(self):
        release, files, _ = self.release()
        functions, old_map = self.live()
        rename = os.rename

        def fail_install(source, destination):
            if Path(source).name == "next-deno.jsonc":
                raise OSError("private failure detail")
            return rename(source, destination)

        with mock.patch.object(deploy.os, "rename", side_effect=fail_install), \
                mock.patch.object(deploy, "restart_functions") as restart:
            self.assert_code(
                "DEPLOY_API_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST",
                deploy.activate_functions, release, functions,
                deploy.deployment_import_map(files, old_map), Path("stack"), lambda: True)
        self.assertEqual((functions / "library-api/index.ts").read_bytes(), b"previous version")
        self.assertEqual((functions / "deno.jsonc").read_bytes(), old_map)
        restart.assert_called_once()

    def test_restart_failure_restores_and_failed_restore_retains_recovery_directory(self):
        release, files, _ = self.release()
        functions, old_map = self.live()
        with mock.patch.object(deploy, "restart_functions", side_effect=RuntimeError("private")):
            self.assert_code("DEPLOY_API_RESTORE_FAILED_MIGRATIONS_MAY_PERSIST",
                             deploy.activate_functions, release, functions,
                             deploy.deployment_import_map(files, old_map), Path("stack"),
                             lambda: True)
        self.assertEqual((functions / "library-api/index.ts").read_bytes(), b"previous version")
        self.assertEqual((functions / "deno.jsonc").read_bytes(), old_map)
        self.assertEqual(len(list(functions.glob(".deploy-*"))), 1)

    def test_invalid_import_map_is_never_silently_replaced(self):
        for current in (b"// JSONC comments\n{}", b"[]", b"not-json"):
            self.assert_code("DEPLOY_IMPORT_MAP_INVALID", deploy.deployment_import_map,
                             fixture_files(), current)
        files = fixture_files()
        files[deploy.APP + "/deno.json"] = b'{"imports":{"bad":null}}'
        self.assert_code("DEPLOY_IMPORT_MAP_INVALID", deploy.deployment_import_map,
                         files, b"{}")

    def test_stored_pending_hashes_are_loadable_and_invalid_hashes_fail_closed(self):
        migrations = deploy.collect_migrations(fixture_files())
        path = self.root / "receipt.json"
        document = {"commit": COMMIT, "status": "pending", "migration_hashes": hashes(migrations)}
        path.write_text(json.dumps(document), encoding="utf-8")
        self.assertEqual(deploy.load_hashes(path), hashes(migrations))
        document["migration_hashes"][OLD]["sha256"] = "not a hash"
        path.write_text(json.dumps(document), encoding="utf-8")
        self.assert_code("MIGRATION_HASH_RECEIPT_INVALID", deploy.load_hashes, path)

    def test_receipt_contains_no_sql_or_keys_and_is_fsynced_before_return(self):
        path = self.root / "receipt.json"
        open_file, close_file = os.open, os.close
        directory_descriptor = 987654

        def open_directory(name, flags, *args, **kwargs):
            if str(name) == str(self.root):
                return directory_descriptor
            return open_file(name, flags, *args, **kwargs)

        def close_directory(descriptor):
            if descriptor != directory_descriptor:
                return close_file(descriptor)

        with mock.patch.object(deploy.os, "O_DIRECTORY", getattr(os, "O_DIRECTORY", 0), create=True), \
                mock.patch.object(deploy.os, "open", side_effect=open_directory), \
                mock.patch.object(deploy.os, "close", side_effect=close_directory), \
                mock.patch.object(deploy.os, "fsync") as fsync:
            result = deploy.write_receipt(path, COMMIT,
                                         deploy.collect_migrations(fixture_files()), "pending",
                                         deploy.BASELINE_COMMIT)
        self.assertEqual(json.loads(path.read_text()), result)
        self.assertEqual(set(result), {"commit", "status", "previous_commit", "migration_hashes"})
        self.assertEqual(set(result["migration_hashes"][OLD]), {"name", "sha256"})
        self.assertEqual(fsync.call_count, 2)
        self.assertFalse(list(self.root.glob(".receipt-*")))

    def test_anon_key_parser_only_accepts_one_safe_value(self):
        path = self.root / ".env"
        path.write_text("OTHER=synthetic-unused-private-value\nANON_KEY='" + KEY + "'\n",
                        encoding="utf-8")
        self.assertEqual(deploy.read_anon_key(path), KEY)
        for value in ("OTHER=unused\n", "ANON_KEY=short\n", "ANON_KEY=" + KEY +
                      "\nANON_KEY=" + KEY + "\n", "ANON_KEY=value with spaces\n"):
            path.write_text(value, encoding="utf-8")
            self.assert_code("DEPLOY_HEALTH_KEY_INVALID", deploy.read_anon_key, path)


class CommandAndHealthTests(ReceiverCase):
    def test_subprocess_output_is_captured_stderr_suppressed_and_environment_fixed(self):
        result = types.SimpleNamespace(returncode=0, stdout=b"synthetic private output")
        with mock.patch.object(deploy.subprocess, "run", return_value=result) as run:
            self.assertEqual(deploy.run_command(["fixed"], Path("stack"), 10, "FIXED_FAILURE"),
                             result.stdout)
        options = run.call_args[1]
        self.assertEqual(options["stdout"], subprocess.PIPE)
        self.assertEqual(options["stderr"], subprocess.DEVNULL)
        self.assertEqual(options["env"], deploy.COMMAND_ENV)
        self.assertEqual(options["timeout"], 10)
        self.assertNotIn(KEY, repr(run.call_args))
        result.returncode = 1
        with mock.patch.object(deploy.subprocess, "run", return_value=result):
            self.assert_code("FIXED_FAILURE", deploy.run_command,
                             ["fixed"], Path("stack"), 10, "FIXED_FAILURE")
        with mock.patch.object(deploy.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired("private", 10)):
            self.assert_code("FIXED_FAILURE", deploy.run_command,
                             ["fixed"], Path("stack"), 10, "FIXED_FAILURE")

    def test_backup_is_mandatory_and_missing_command_is_not_skipped(self):
        with mock.patch.object(deploy, "require_trusted", side_effect=FileNotFoundError()), \
                mock.patch.object(deploy, "run_command") as runner:
            self.assert_code("MANDATORY_BACKUP_UNAVAILABLE", deploy.mandatory_backup, Path("stack"))
        runner.assert_not_called()
        with mock.patch.object(deploy, "require_trusted"), \
                mock.patch.object(deploy.os, "access", return_value=True), \
                mock.patch.object(deploy, "run_command") as runner:
            deploy.mandatory_backup(Path("stack"))
        runner.assert_called_once_with([str(deploy.BACKUP)], Path("stack"), 900,
                                       "MANDATORY_BACKUP_FAILED")

    def test_root_ownership_links_and_permissions_are_checked(self):
        path = mock.Mock()
        for metadata in (types.SimpleNamespace(st_mode=stat.S_IFLNK | 0o777, st_uid=0),
                         types.SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_uid=1000),
                         types.SimpleNamespace(st_mode=stat.S_IFREG | 0o666, st_uid=0)):
            path.lstat.return_value = metadata
            self.assert_code("DEPLOY_PATH_NOT_ROOT_OWNED_OR_SAFE", deploy.require_trusted, path)
        path.lstat.return_value = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0)
        deploy.require_trusted(path)

    def test_flock_serializes_with_bounded_wait_and_always_closes_descriptor(self):
        metadata = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0)
        flock = mock.Mock(side_effect=[BlockingIOError(), None])
        fcntl = types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=flock)
        with mock.patch.dict(deploy.sys.modules, {"fcntl": fcntl}), \
                mock.patch.object(deploy.os, "O_NOFOLLOW", getattr(os, "O_NOFOLLOW", 0), create=True), \
                mock.patch.object(deploy.os, "open", return_value=99), \
                mock.patch.object(deploy.os, "fstat", return_value=metadata), \
                mock.patch.object(deploy.os, "close") as close, \
                mock.patch.object(deploy.time, "sleep") as sleep:
            with deploy.deployment_lock(Path("fixed-root")):
                self.assertEqual(flock.call_count, 2)
        flock.assert_called_with(99, 6)
        sleep.assert_called_once_with(0.2)
        close.assert_called_once_with(99)
        flock.side_effect = BlockingIOError()
        with mock.patch.dict(deploy.sys.modules, {"fcntl": fcntl}), \
                mock.patch.object(deploy.os, "O_NOFOLLOW", getattr(os, "O_NOFOLLOW", 0), create=True), \
                mock.patch.object(deploy.os, "open", return_value=99), \
                mock.patch.object(deploy.os, "fstat", return_value=metadata), \
                mock.patch.object(deploy.os, "close") as close, \
                mock.patch.object(deploy.time, "monotonic", side_effect=[0, 301]):
            with self.assertRaises(deploy.DeployError) as caught:
                with deploy.deployment_lock(Path("fixed-root")):
                    self.fail("expired lock must never enter deployment")
        self.assertEqual(caught.exception.code, "DEPLOY_LOCK_TIMEOUT")
        close.assert_called_once_with(99)

    def test_health_retries_and_requires_actual_database_health(self):
        unavailable = mock.MagicMock()
        unavailable.__enter__.return_value = unavailable
        unavailable.getcode.return_value = 200
        unavailable.read.return_value = b'{"status":"unavailable"}'
        healthy = mock.MagicMock()
        healthy.__enter__.return_value = healthy
        healthy.getcode.return_value = 200
        healthy.read.return_value = b'{"status":"ok"}'
        opener = mock.Mock()
        opener.open.side_effect = [OSError("private transport detail"), unavailable, healthy]
        with mock.patch.object(deploy.urllib.request, "build_opener", return_value=opener) as build, \
                mock.patch.object(deploy.time, "sleep") as sleep:
            self.assertTrue(deploy.wait_for_health(KEY, attempts=3))
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(opener.open.call_count, 3)
        request = opener.open.call_args[0][0]
        self.assertEqual(request.full_url,
                         "http://127.0.0.1:8000/functions/v1/library-api/v1/health")
        self.assertEqual(request.get_header("Apikey"), KEY)
        self.assertEqual(opener.open.call_args[1], {"timeout": 3})
        self.assertIsInstance(build.call_args[0][1], deploy.NoRedirect)
        self.assertIsNone(deploy.NoRedirect().redirect_request(None, None, 302, "", {}, "elsewhere"))

    def test_health_exhaustion_does_not_accept_wrong_types_or_oversized_bodies(self):
        opener = mock.Mock()
        responses = []
        for body in (b"[]", b"synthetic private body", b"x" * 8193, b'{"status":"ok"}'):
            response = mock.MagicMock()
            response.__enter__.return_value = response
            response.getcode.return_value = 503
            response.read.return_value = body
            responses.append(response)
        opener.open.side_effect = responses
        with mock.patch.object(deploy.urllib.request, "build_opener", return_value=opener), \
                mock.patch.object(deploy.time, "sleep") as sleep:
            self.assertFalse(deploy.wait_for_health(KEY, attempts=4))
        self.assertEqual(sleep.call_count, 3)


class OrchestrationTests(ReceiverCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: deploy.remove_tree(self.root))
        functions = self.root / "supabase/docker/volumes/functions"
        (functions / "library-api").mkdir(parents=True)
        (functions / "deno.jsonc").write_bytes(b'{"imports":{}}')
        (self.root / "supabase/docker/.env").write_text("ANON_KEY=" + KEY + "\n", encoding="utf-8")
        (self.root / "releases").mkdir()
        self.files = fixture_files()
        self.files[deploy.MIGRATIONS + "/" + NEW + "_second.sql"] = b"SELECT 2;"
        self.files, self.directories = deploy.validate_archive(archive_for(files=self.files))
        self.known = hashes(deploy.collect_migrations(fixture_files()))
        self.events = []
        self.patches = contextlib.ExitStack()
        self.addCleanup(self.patches.close)
        self.patches.enter_context(mock.patch.object(deploy, "require_trusted"))
        self.patches.enter_context(mock.patch.object(deploy, "deployment_lock", side_effect=unlocked))
        self.patches.enter_context(mock.patch.object(deploy, "current_application",
                                                     return_value=deploy.BASELINE_COMMIT))
        self.patches.enter_context(mock.patch.object(deploy, "load_hashes", return_value=self.known))
        self.patches.enter_context(mock.patch.object(deploy, "read_history", return_value=[
            {"version": OLD, "name": "first"}]))

    def apply(self):
        return deploy.deploy(COMMIT, self.files, self.directories, self.root)

    def test_backup_failure_prevents_staging_receipt_migration_and_api_mutations(self):
        with mock.patch.object(deploy, "mandatory_backup",
                               side_effect=deploy.DeployError("MANDATORY_BACKUP_FAILED")), \
                mock.patch.object(deploy, "stage_release") as stage, \
                mock.patch.object(deploy, "write_receipt") as receipt, \
                mock.patch.object(deploy, "psql") as psql, \
                mock.patch.object(deploy, "activate_functions") as activate:
            self.assert_code("MANDATORY_BACKUP_FAILED", self.apply)
        for operation in (stage, receipt, psql, activate):
            operation.assert_not_called()

    def test_drift_fails_before_backup_or_any_mutation(self):
        self.known[OLD]["sha256"] = "0" * 64
        with mock.patch.object(deploy, "mandatory_backup") as backup, \
                mock.patch.object(deploy, "stage_release") as stage:
            self.assert_code("EXISTING_MIGRATION_DRIFT_OR_BASELINE_MISSING", self.apply)
        backup.assert_not_called()
        stage.assert_not_called()

    def test_original_baseline_and_stored_hash_conflict_fails_before_mutation(self):
        baseline = self.root / "releases" / deploy.BASELINE / deploy.MIGRATIONS
        baseline.mkdir(parents=True)
        (baseline / (OLD + "_first.sql")).write_bytes(b"SELECT 1;\n")
        self.known[OLD]["sha256"] = "0" * 64
        with mock.patch.object(deploy, "mandatory_backup") as backup:
            self.assert_code("MIGRATION_BASELINE_RECEIPT_CONFLICT", self.apply)
        backup.assert_not_called()

    def test_pending_hashes_precede_transaction_and_success_receipt_follows_health(self):
        def record(name, value=None):
            def run(*args, **kwargs):
                self.events.append(name)
                return value
            return run

        def receipt(path, commit, migrations, status, previous_commit):
            self.events.append(status)
            return {"commit": commit, "status": status, "previous_commit": previous_commit,
                    "migration_hashes": hashes(migrations)}

        with mock.patch.object(deploy, "mandatory_backup", side_effect=record("backup")), \
                mock.patch.object(deploy, "stage_release", side_effect=record("stage", self.root / "release")), \
                mock.patch.object(deploy, "write_receipt", side_effect=receipt), \
                mock.patch.object(deploy, "psql", side_effect=record("migrations")) as psql, \
                mock.patch.object(deploy, "activate_functions", side_effect=record("health")):
            result = self.apply()
        self.assertEqual(self.events, ["backup", "stage", "pending", "migrations", "health", "deployed"])
        self.assertEqual(result["status"], "deployed")
        self.assertEqual(set(result["migration_hashes"]), {OLD, NEW})
        self.assertTrue(psql.call_args[1]["transaction"])
        self.assertNotIn("SELECT 1;", psql.call_args[0][1])
        self.assertIn("SELECT 2;", psql.call_args[0][1])
        self.assertNotIn(KEY, json.dumps(result))

    def test_api_failure_keeps_pending_hash_receipt_without_database_rollback(self):
        with mock.patch.object(deploy, "mandatory_backup"), \
                mock.patch.object(deploy, "stage_release", return_value=self.root / "release"), \
                mock.patch.object(deploy, "write_receipt") as receipt, \
                mock.patch.object(deploy, "psql") as psql, \
                mock.patch.object(deploy, "wait_for_health", return_value=True), \
                mock.patch.object(deploy, "activate_functions", side_effect=deploy.DeployError(
                    "DEPLOY_API_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST")):
            self.assert_code("DEPLOY_API_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST", self.apply)
        self.assertEqual(receipt.call_count, 1)
        self.assertEqual(receipt.call_args[0][-2:], ("pending", deploy.BASELINE_COMMIT))
        self.assertEqual(psql.call_count, 1)
        self.assertNotIn("ROLLBACK", psql.call_args[0][1])

    def test_migration_failure_does_not_update_api_or_finalize_success_receipt(self):
        with mock.patch.object(deploy, "mandatory_backup"), \
                mock.patch.object(deploy, "stage_release", return_value=self.root / "release"), \
                mock.patch.object(deploy, "write_receipt") as receipt, \
                mock.patch.object(deploy, "psql", side_effect=deploy.DeployError(
                    "MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED")), \
                mock.patch.object(deploy, "activate_functions") as activate:
            self.assert_code("MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED", self.apply)
        activate.assert_not_called()
        self.assertEqual(receipt.call_count, 1)
        self.assertEqual(receipt.call_args[0][-2:], ("pending", deploy.BASELINE_COMMIT))


class RepairTests(ReceiverCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: deploy.remove_tree(self.root))
        self.stack = self.root / "supabase/docker"
        self.functions = self.stack / "volumes/functions"
        self.functions.mkdir(parents=True)
        (self.root / "releases").mkdir()
        self.old_files = fixture_files()
        self.old_files[deploy.APP + "/index.ts"] = b"export const version = 1;"
        self.old_files[deploy.APP + "/deno.json"] = b'{"imports":{"old":"npm:old@1"}}'
        self.files = fixture_files()
        self.files[deploy.MIGRATIONS + "/" + NEW + "_second.sql"] = b"SELECT 2;"
        self.files, self.directories = deploy.validate_archive(archive_for(files=self.files))
        self.migrations = deploy.collect_migrations(self.files)
        self.patches = contextlib.ExitStack()
        self.addCleanup(self.patches.close)
        for name in ("require_trusted", "sync_directory", "restart_functions"):
            self.patches.enter_context(mock.patch.object(deploy, name))
        self.patches.enter_context(mock.patch.object(deploy, "deployment_lock", side_effect=unlocked))
        baseline = self.root / "releases" / deploy.BASELINE
        for name, content in self.old_files.items():
            path = baseline / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        deploy.shutil.copytree(str(baseline / deploy.APP), str(self.functions / "library-api"))
        (self.functions / "deno.jsonc").write_bytes(self.old_files[deploy.APP + "/deno.json"])
        (self.stack / ".env").write_text("ANON_KEY=" + KEY + "\n", encoding="utf-8")
        (self.root / "deployment-receipt.json").write_text(
            json.dumps({"applicationCommit": deploy.BASELINE}), encoding="utf-8")

    def test_failed_migration_or_activation_then_retry_preserves_actual_backup_commit(self):
        for phase in ("migration", "activation"):
            with self.subTest(phase=phase):
                receipt = self.root / "deploy-receipt.json"
                if receipt.exists():
                    receipt.unlink()
                if (self.functions / "library-api").exists():
                    deploy.remove_tree(self.functions / "library-api")
                deploy.shutil.copytree(str(self.root / "releases" / deploy.BASELINE / deploy.APP),
                                       str(self.functions / "library-api"))
                (self.functions / "deno.jsonc").write_bytes(self.old_files[deploy.APP + "/deno.json"])
                backup_commits = []

                def backup(stack):
                    previous = (json.loads(receipt.read_text())["previous_commit"]
                                if receipt.exists() else deploy.BASELINE_COMMIT)
                    backup_commits.append(previous)

                old_history = [{"version": OLD, "name": "first"}]
                new_history = old_history + [{"version": NEW, "name": "second"}]
                error = deploy.DeployError("MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED")
                with mock.patch.object(deploy, "mandatory_backup", side_effect=backup), \
                        mock.patch.object(deploy, "read_history", side_effect=[old_history,
                            old_history if phase == "migration" else new_history]), \
                        mock.patch.object(deploy, "psql", side_effect=[error, b""]
                                          if phase == "migration" else None), \
                        mock.patch.object(deploy, "wait_for_health", side_effect=[True]
                                          if phase == "migration" else [False, True, True]):
                    with self.assertRaises(deploy.DeployError):
                        deploy.deploy(COMMIT, self.files, self.directories, self.root)
                    pending = json.loads(receipt.read_text())
                    self.assertEqual(pending["status"], "pending")
                    self.assertEqual(pending["previous_commit"], deploy.BASELINE_COMMIT)
                    result = deploy.deploy(COMMIT, self.files, self.directories, self.root)
                self.assertEqual(backup_commits, [deploy.BASELINE_COMMIT, deploy.BASELINE_COMMIT])
                self.assertEqual(result["status"], "deployed")
                self.assertEqual(result["commit"], COMMIT)
                self.assertFalse((self.root / "deploy-unresolved.json").exists())

    def test_activation_interruption_persists_marker_before_any_file_switch(self):
        marker = self.root / "deploy-unresolved.json"

        def interrupted(*args):
            self.assertEqual(json.loads(marker.read_text())["operation"], "activation")
            raise KeyboardInterrupt()

        with mock.patch.object(deploy, "mandatory_backup"), \
                mock.patch.object(deploy, "read_history", return_value=[{"version": OLD, "name": "first"}]), \
                mock.patch.object(deploy, "psql"), \
                mock.patch.object(deploy, "activate_functions", side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                deploy.deploy(COMMIT, self.files, self.directories, self.root)
        self.assertTrue(marker.exists())
        with mock.patch.object(deploy, "mandatory_backup") as backup:
            self.assert_code("DEPLOY_UNRESOLVED_OPERATION_BLOCKED", deploy.deploy,
                             COMMIT, self.files, self.directories, self.root)
        backup.assert_not_called()

    def test_failed_activation_does_not_clear_marker_for_unhealthy_old_api(self):
        with mock.patch.object(deploy, "mandatory_backup"), \
                mock.patch.object(deploy, "read_history", return_value=[{"version": OLD, "name": "first"}]), \
                mock.patch.object(deploy, "psql"), \
                mock.patch.object(deploy, "wait_for_health", return_value=False):
            with self.assertRaises(deploy.DeployError):
                deploy.deploy(COMMIT, self.files, self.directories, self.root)
        self.assertTrue((self.root / "deploy-unresolved.json").exists())

    def test_interrupted_activation_requires_matching_live_files_and_global_imports(self):
        release = deploy.stage_release(self.root / "releases", COMMIT, self.files, self.directories)
        deploy.write_receipt(self.root / "deploy-receipt.json", COMMIT, self.migrations,
                             "pending", deploy.BASELINE_COMMIT)
        deploy.remove_tree(self.functions / "library-api")
        deploy.shutil.copytree(str(release / deploy.APP), str(self.functions / "library-api"))
        self.assert_code("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED", deploy.current_application,
                         self.root, self.functions)
        marker = self.root / "deploy-unresolved.json"
        self.assertEqual(json.loads(marker.read_text())["operation"], "application_identity")
        with mock.patch.object(deploy, "mandatory_backup") as backup:
            self.assert_code("DEPLOY_UNRESOLVED_OPERATION_BLOCKED", deploy.deploy,
                             COMMIT, self.files, self.directories, self.root)
        backup.assert_not_called()
        # Operator-resolved full activation can be positively identified, not
        # silently labeled as the old previous commit.
        marker.unlink()
        (self.functions / "deno.jsonc").write_bytes(self.files[deploy.APP + "/deno.json"])
        self.assertEqual(deploy.current_application(self.root, self.functions), COMMIT)
        self.assertEqual(json.loads((self.root / "deploy-receipt.json").read_text())[
            "previous_commit"], COMMIT)

    def test_host_timeout_terminates_tagged_backend_and_verifies_gone_before_unlock(self):
        replies = [subprocess.TimeoutExpired("synthetic private command", 900),
                   types.SimpleNamespace(returncode=0, stdout=b"t\n"),
                   types.SimpleNamespace(returncode=0, stdout=b"1\n"),
                   types.SimpleNamespace(returncode=0, stdout=b"0\n")]
        with mock.patch.object(deploy.subprocess, "run", side_effect=replies) as runner, \
                mock.patch.object(deploy.time, "sleep"):
            self.assert_code("MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED", deploy.psql,
                             self.stack, "SELECT 1;", transaction=True)
        marker = self.root / "deploy-unresolved.json"
        self.assertFalse(marker.exists())
        calls = runner.call_args_list
        tag = calls[0][0][0][4].split("=", 1)[1]
        self.assertIn("840s", calls[0][0][0])
        self.assertEqual(calls[0][1]["timeout"], 900)
        self.assertIn("pg_terminate_backend", calls[1][1]["input"].decode())
        self.assertIn(tag, calls[1][1]["input"].decode())
        self.assertIn("count(*)", calls[-1][1]["input"].decode())
        self.assertEqual(len(calls), 4)

    def test_unverifiable_backend_preserves_marker_and_blocks_future_deploy(self):
        error = deploy.DeployError("MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED")
        with mock.patch.object(deploy, "run_command", side_effect=[error, OSError("private")]) as run:
            self.assert_code("DEPLOY_DATABASE_SESSION_UNRESOLVED", deploy.psql,
                             self.stack, "SELECT 1;", transaction=True)
        marker = self.root / "deploy-unresolved.json"
        document = json.loads(marker.read_text())
        self.assertEqual(document["status"], "unresolved")
        self.assertEqual(document["operation"], "postgres")
        self.assertIn(document["application_name"], run.call_args_list[0][0][0][4])
        with mock.patch.object(deploy, "mandatory_backup") as backup, \
                mock.patch.object(deploy, "read_history") as history:
            self.assert_code("DEPLOY_UNRESOLVED_OPERATION_BLOCKED", deploy.deploy,
                             COMMIT, self.files, self.directories, self.root)
        backup.assert_not_called()
        history.assert_not_called()


class MainTests(ReceiverCase):
    def test_unsafe_payload_never_reaches_deployment_and_only_fixed_error_is_printed(self):
        payload = json.dumps({"commit": COMMIT}).encode() + b"\n" + archive_for([
            ("../synthetic-private-value.ts", b"synthetic private payload")])
        error_output, output = io.StringIO(), io.StringIO()
        with mock.patch.object(deploy.sys, "argv", ["forced-receiver"]), \
                mock.patch.object(deploy.os, "geteuid", return_value=0, create=True), \
                mock.patch.object(deploy.os, "umask"), \
                mock.patch.object(deploy, "require_trusted"), \
                mock.patch.object(deploy.sys, "stdin", types.SimpleNamespace(buffer=io.BytesIO(payload))), \
                mock.patch.object(deploy, "deploy") as apply, \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(error_output):
            self.assertEqual(deploy.main(), 1)
        apply.assert_not_called()
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(error_output.getvalue(), "ARCHIVE_UNSAFE_PATH\n")

    def test_nonroot_and_arguments_are_refused_before_reading_stdin(self):
        for arguments, uid in ((["forced-receiver", "malicious-command"], 0),
                               (["forced-receiver"], 1000)):
            with mock.patch.object(deploy.sys, "argv", arguments), \
                    mock.patch.object(deploy.os, "geteuid", return_value=uid, create=True), \
                    mock.patch.object(deploy, "read_payload") as receive, \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(deploy.main(), 1)
            receive.assert_not_called()


if __name__ == "__main__":
    unittest.main()
