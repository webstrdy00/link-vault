#!/usr/bin/python3
"""Root-owned, forced-SSH deployment receiver; never execute payload shell files.

stdin: one JSON line with a 40-hex commit, followed by a bounded gzip git archive.
SQL is trusted repository code, NOT a sandbox. Only function files are restored
on an API failure; committed migrations are never automatically rolled back.
"""

import contextlib
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import uuid


ROOT = Path("/opt/link-vault")
BASELINE = "fbd11f1"
BASELINE_COMMIT = "fbd11f1b49f423c942e52ebe4c7c783f363c6d99"
BACKUP = Path("/usr/local/sbin/link-vault-backup")
DOCKER = "/usr/bin/docker"
APP = "supabase/functions/library-api"
MIGRATIONS = "supabase/migrations"
MAX_COMPRESSED = 32 * 1024 * 1024
MAX_EXPANDED = 64 * 1024 * 1024
MAX_MEMBERS = 1024
MIGRATION_NAME = re.compile(r"([0-9]{1,20})_([a-z][a-z0-9_]*)\.sql\Z")
SAFE_COMPONENT = re.compile(r"[A-Za-z0-9_.-]{1,200}\Z")
DOLLAR_QUOTE = re.compile(r"\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$")
SQL_WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*")
COMMAND_ENV = {
    "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "HOME": "/root",
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
}


class DeployError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def fail(code):
    raise DeployError(code)


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            fail("DUPLICATE_JSON_KEY")
        value[key] = item
    return value


def read_payload(stream):
    line = stream.readline(1025)
    if len(line) > 1024 or not line.endswith(b"\n"):
        fail("INVALID_DEPLOY_HEADER")
    try:
        header = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        fail("INVALID_DEPLOY_HEADER")
    if (not isinstance(header, dict) or set(header) != {"commit"}
            or not isinstance(header["commit"], str)
            or not re.fullmatch(r"[0-9a-fA-F]{40}", header["commit"])):
        fail("INVALID_DEPLOY_HEADER")
    compressed = bytearray()
    while True:
        chunk = stream.read(min(65536, MAX_COMPRESSED + 1 - len(compressed)))
        if not chunk:
            break
        compressed.extend(chunk)
        if len(compressed) > MAX_COMPRESSED:
            fail("ARCHIVE_COMPRESSED_LIMIT")
    commit = header["commit"].lower()
    files, directories = validate_archive(bytes(compressed), commit)
    return commit, files, directories


def validate_archive(compressed, commit=None):
    """Validate the entire archive before writing anything; never use extractall."""
    if len(compressed) > MAX_COMPRESSED:
        fail("ARCHIVE_COMPRESSED_LIMIT")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as source:
            expanded = source.read(MAX_EXPANDED + 1)
        if len(expanded) > MAX_EXPANDED:
            fail("ARCHIVE_EXPANDED_LIMIT")
        files, directories, seen = {}, set(), set()
        with tarfile.open(fileobj=io.BytesIO(expanded), mode="r:") as archive:
            if (commit is not None and "comment" in archive.pax_headers
                    and archive.pax_headers["comment"] != commit):
                fail("ARCHIVE_COMMIT_MISMATCH")
            for member in archive:
                name = member.name.rstrip("/") if member.isdir() else member.name
                parts = name.split("/")
                if (len(seen) >= MAX_MEMBERS or name in seen):
                    fail("ARCHIVE_MEMBER_LIMIT_OR_DUPLICATE")
                if (not name or len(name) > 1024 or len(parts) > 16
                        or any(part in (".", "..") or
                                    not SAFE_COMPONENT.fullmatch(part)
                                    for part in parts)):
                    fail("ARCHIVE_UNSAFE_PATH")
                seen.add(name)
                if (member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE,
                                        tarfile.DIRTYPE)
                        or member.linkname or member.issparse()):
                    fail("ARCHIVE_UNSAFE_TYPE")
                if member.isdir():
                    allowed = (name in ("supabase", "supabase/functions", APP,
                                        MIGRATIONS) or name.startswith(APP + "/"))
                    if not allowed or member.size != 0:
                        fail("ARCHIVE_UNEXPECTED_NAME")
                    directories.add(name)
                else:
                    app_file = (name.startswith(APP + "/") and
                                name.endswith((".ts", ".json", ".jsonc",
                                               ".lock", ".wasm")))
                    migration_file = (len(parts) == 3 and
                                      "/".join(parts[:2]) == MIGRATIONS and
                                      MIGRATION_NAME.fullmatch(parts[-1]))
                    if not (app_file or migration_file):
                        fail("ARCHIVE_UNEXPECTED_NAME")
                    if member.size < 0 or member.size > MAX_EXPANDED:
                        fail("ARCHIVE_EXPANDED_LIMIT")
                    with archive.extractfile(member) as source:
                        content = source.read(member.size + 1)
                    if len(content) != member.size:
                        fail("ARCHIVE_TRUNCATED")
                    files[name] = content
                for length in range(1, len(parts)):
                    directories.add("/".join(parts[:length]))
                if len(files) + len(directories) > MAX_MEMBERS:
                    fail("ARCHIVE_MEMBER_LIMIT_OR_DUPLICATE")
            # Reject concatenated tar payloads or hidden data after tar's EOF.
            padding = expanded[archive.offset:]
            if len(padding) < 1024 or len(expanded) % 512 or any(padding):
                fail("ARCHIVE_INVALID_END")
        if set(files).intersection(directories):
            fail("ARCHIVE_PATH_CONFLICT")
        if APP + "/index.ts" not in files or APP + "/deno.json" not in files:
            fail("ARCHIVE_REQUIRED_FILES_MISSING")
        if not any(name.startswith(MIGRATIONS + "/") for name in files):
            fail("ARCHIVE_REQUIRED_FILES_MISSING")
        return files, directories
    except (OSError, EOFError, ValueError, tarfile.TarError):
        fail("ARCHIVE_INVALID")


def check_migration_sql(sql):
    """Reject top-level transaction control and psql commands, not arbitrary SQL.

    Skip quoted strings/identifiers, dollar bodies, and nested SQL comments so
    PL/pgSQL BEGIN/END and transaction words inside data remain valid.
    """
    words, index, statements = [], 0, 0
    forbidden = {"BEGIN", "COMMIT", "END", "ROLLBACK", "ABORT", "SAVEPOINT",
                 "RELEASE"}
    while index < len(sql):
        character = sql[index]
        if sql.startswith("--", index):
            end = sql.find("\n", index + 2)
            index = len(sql) if end == -1 else end + 1
        elif sql.startswith("/*", index):
            depth, index = 1, index + 2
            while index < len(sql) and depth:
                if sql.startswith("/*", index):
                    depth, index = depth + 1, index + 2
                elif sql.startswith("*/", index):
                    depth, index = depth - 1, index + 2
                else:
                    index += 1
            if depth:
                fail("MIGRATION_SQL_INVALID")
        elif character in ("'", '"'):
            quote = character
            escaped = (quote == "'" and index > 0 and sql[index - 1] in "eE"
                       and (index < 2 or not (sql[index - 2].isalnum()
                                            or sql[index - 2] == "_")))
            index += 1
            while index < len(sql):
                if escaped and sql[index] == "\\":
                    index += 2
                elif sql[index] == quote:
                    index += 1
                    if index < len(sql) and sql[index] == quote:
                        index += 1
                    else:
                        break
                else:
                    index += 1
            else:
                fail("MIGRATION_SQL_INVALID")
        elif character == "$":
            match = DOLLAR_QUOTE.match(sql, index)
            if match is None:
                index += 1
                continue
            delimiter = match.group()
            end = sql.find(delimiter, match.end())
            if end == -1:
                fail("MIGRATION_SQL_INVALID")
            index = end + len(delimiter)
        elif character == "\\":
            fail("MIGRATION_PSQL_COMMAND_FORBIDDEN")
        elif character == ";":
            first = words[0] if words else ""
            if (first in forbidden or words[:2] in
                    (["START", "TRANSACTION"], ["PREPARE", "TRANSACTION"],
                     ["SET", "TRANSACTION"]) or
                    words[:3] == ["SET", "SESSION", "CHARACTERISTICS"]):
                fail("MIGRATION_TRANSACTION_CONTROL_FORBIDDEN")
            if words:
                statements += 1
            words = []
            index += 1
        elif character.isalpha() or character == "_":
            match = SQL_WORD.match(sql, index)
            if match is None:
                fail("MIGRATION_SQL_INVALID")
            if len(words) < 3:
                words.append(match.group().upper())
            index = match.end()
        else:
            index += 1
    if words or not statements or "\x00" in sql:
        fail("MIGRATION_SQL_INVALID")


def collect_migrations(files):
    migrations = {}
    for path, content in sorted(files.items()):
        if not path.startswith(MIGRATIONS + "/"):
            continue
        match = MIGRATION_NAME.fullmatch(path.split("/")[-1])
        if match is None or match.group(1) in migrations:
            fail("MIGRATION_VERSION_INVALID_OR_DUPLICATE")
        try:
            sql = content.decode("utf-8")
        except UnicodeError:
            fail("MIGRATION_SQL_INVALID")
        check_migration_sql(sql)
        migrations[match.group(1)] = {
            "name": match.group(2),
            "sha256": hashlib.sha256(content).hexdigest(),
            "sql": sql,
        }
    return migrations


def require_trusted(path, directory=False):
    metadata = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if (not expected_type(metadata.st_mode) or metadata.st_uid != 0
            or metadata.st_mode & 0o022):
        fail("DEPLOY_PATH_NOT_ROOT_OWNED_OR_SAFE")


@contextlib.contextmanager
def deployment_lock(root):
    import fcntl  # Linux-only execution; pure helper tests also run on Windows.
    descriptor = os.open(str(root / "deploy.lock"),
                         os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != 0
                or metadata.st_mode & 0o022):
            fail("DEPLOY_LOCK_UNSAFE")
        deadline = time.monotonic() + 300
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    fail("DEPLOY_LOCK_TIMEOUT")
                time.sleep(0.2)
        yield
    finally:
        os.close(descriptor)


def run_command(arguments, stack, timeout, code, data=None):
    try:
        result = subprocess.run(arguments, cwd=str(stack), env=COMMAND_ENV,
                                input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=timeout,
                                check=False)
    except (OSError, subprocess.TimeoutExpired):
        fail(code)
    if result.returncode != 0:
        fail(code)
    # Captured subprocess output is never logged, even on failure.
    return result.stdout


def mandatory_backup(stack):
    try:
        for path in (Path("/usr/local"), Path("/usr/local/sbin")):
            require_trusted(path, directory=True)
        require_trusted(BACKUP)
        if not os.access(str(BACKUP), os.X_OK):
            fail("MANDATORY_BACKUP_UNAVAILABLE")
    except OSError:
        fail("MANDATORY_BACKUP_UNAVAILABLE")
    run_command([str(BACKUP)], stack, 900, "MANDATORY_BACKUP_FAILED")


def postgres_command(application_name, seconds):
    return [DOCKER, "exec", "-i", "--env", "PGAPPNAME=" + application_name,
            "supabase-db", "timeout", "-s", "TERM", "-k", "5",
            str(seconds) + "s", "psql", "--no-psqlrc",
                 "--no-password", "--username=supabase_admin", "--dbname=postgres",
                 "--quiet", "--tuples-only", "--no-align", "--set=ON_ERROR_STOP=1"]


def settle_postgres(stack, application_name, terminate):
    arguments = postgres_command(application_name + "-cleanup", 20) + ["--file=-"]
    predicate = "application_name = '{}' AND pid <> pg_backend_pid()".format(application_name)
    if terminate:
        run_command(arguments, stack, 30, "DEPLOY_DATABASE_SESSION_UNRESOLVED",
                    ("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE " +
                     predicate + ";\n").encode("utf-8"))
    for attempt in range(5):
        count = run_command(arguments, stack, 30, "DEPLOY_DATABASE_SESSION_UNRESOLVED",
                            ("SELECT count(*) FROM pg_stat_activity WHERE " +
                             predicate + ";\n").encode("utf-8"))
        if count.strip() == b"0":
            return
        time.sleep(0.2)
    fail("DEPLOY_DATABASE_SESSION_UNRESOLVED")


def psql(stack, sql, transaction=False):
    root = stack.parent.parent
    marker = root / "deploy-unresolved.json"
    if marker.exists() or marker.is_symlink():
        fail("DEPLOY_UNRESOLVED_OPERATION_BLOCKED")
    application_name = "link-vault-deploy-" + uuid.uuid4().hex
    # The marker survives process interruption and blocks snapshots/redeployment
    # until an operator has verified that this exact backend is no longer alive.
    write_document(marker, {"status": "unresolved", "operation": "postgres",
                            "application_name": application_name})
    arguments = postgres_command(application_name, 840 if transaction else 20)
    if transaction:
        arguments.append("--single-transaction")
    arguments.append("--file=-")
    error, output = None, None
    try:
        output = run_command(arguments, stack, 900 if transaction else 30,
                             "MIGRATIONS_FAILED_DATABASE_STATE_UNCONFIRMED" if transaction
                             else "MIGRATION_HISTORY_UNAVAILABLE", sql.encode("utf-8"))
    except BaseException as caught:
        error = caught
    try:
        settle_postgres(stack, application_name, terminate=error is not None)
    except Exception:
        fail("DEPLOY_DATABASE_SESSION_UNRESOLVED")
    marker.unlink()
    sync_directory(root)
    if error is not None:
        raise error
    return output


def read_history(stack):
    raw = psql(stack, "SELECT coalesce(json_agg(json_build_object('version', "
               "version, 'name', name) ORDER BY version), '[]'::json) "
               "FROM supabase_migrations.schema_migrations;\n")
    try:
        history = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        fail("MIGRATION_HISTORY_INVALID")
    if not isinstance(history, list) or len(history) > MAX_MEMBERS:
        fail("MIGRATION_HISTORY_INVALID")
    seen = set()
    for row in history:
        if (not isinstance(row, dict) or set(row) != {"version", "name"}
                or not isinstance(row["version"], str)
                or not re.fullmatch(r"[0-9]{1,20}", row["version"])
                or not isinstance(row["name"], str)
                or not re.fullmatch(r"[a-z][a-z0-9_]*", row["name"])
                or row["version"] in seen):
            fail("MIGRATION_HISTORY_INVALID")
        seen.add(row["version"])
    return history


def load_hashes(receipt):
    if not receipt.exists():
        return {}
    require_trusted(receipt)
    try:
        with receipt.open("r", encoding="utf-8") as source:
            document = json.load(source, object_pairs_hook=unique_object)
        hashes = document["migration_hashes"]
        if (document["status"] not in ("pending", "deployed")
                or not re.fullmatch(r"[0-9a-f]{40}", document["commit"])
                or not isinstance(hashes, dict) or len(hashes) > MAX_MEMBERS):
            fail("MIGRATION_HASH_RECEIPT_INVALID")
        for version, item in hashes.items():
            if (not re.fullmatch(r"[0-9]{1,20}", version)
                    or not isinstance(item, dict) or set(item) != {"name", "sha256"}
                    or not re.fullmatch(r"[a-z][a-z0-9_]*", item["name"])
                    or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"])):
                fail("MIGRATION_HASH_RECEIPT_INVALID")
        return hashes
    except (KeyError, TypeError, ValueError, UnicodeError):
        fail("MIGRATION_HASH_RECEIPT_INVALID")


def migration_plan(migrations, history, known):
    applied = set()
    for row in history:
        version = row["version"]
        candidate, original = migrations.get(version), known.get(version)
        if (candidate is None or original is None
                or row["name"] != candidate["name"]
                or row["name"] != original["name"]
                or original["sha256"] != candidate["sha256"]):
            fail("EXISTING_MIGRATION_DRIFT_OR_BASELINE_MISSING")
        applied.add(version)
    pending = sorted(set(migrations) - applied, key=int)
    if applied and pending and int(pending[0]) <= max(map(int, applied)):
        fail("MIGRATION_OUT_OF_ORDER")
    return pending


def migration_transaction(migrations, pending):
    sql = ["SET LOCAL statement_timeout = '300s';",
           "SET LOCAL lock_timeout = '30s';",
           "LOCK TABLE supabase_migrations.schema_migrations IN EXCLUSIVE MODE;"]
    for version in pending:
        migration = migrations[version]
        sql.append(migration["sql"])
        # Names/versions were restricted to non-quoting characters at ingestion.
        sql.append("INSERT INTO supabase_migrations.schema_migrations "
                   "(version, name, statements) VALUES ('{}', '{}', NULL);".format(
                       version, migration["name"]))
    return "\n".join(sql) + "\n"


def remove_tree(path):
    # Releases/copied functions have read-only directories; make only this
    # temporary recovery tree writable before cleanup, without following links.
    for current, directories, files in os.walk(str(path), followlinks=False):
        os.chmod(current, 0o700)
    def retry(function, name, exception):
        if not os.path.islink(name):
            os.chmod(name, 0o700)
        function(name)
    shutil.rmtree(str(path), onerror=retry)


def stage_release(releases, commit, files, directories):
    release = releases / commit
    if release.exists() or release.is_symlink():
        require_trusted(release, directory=True)
        actual_files, actual_directories = {}, set()
        for current, dirs, names in os.walk(str(release), followlinks=False):
            base = Path(current)
            for name in dirs:
                path = base / name
                require_trusted(path, directory=True)
                actual_directories.add(path.relative_to(release).as_posix())
            for name in names:
                path = base / name
                require_trusted(path)
                relative = path.relative_to(release).as_posix()
                if relative not in files or path.stat().st_size != len(files[relative]):
                    fail("IMMUTABLE_RELEASE_CONTENT_MISMATCH")
                actual_files[relative] = (
                    hashlib.sha256(path.read_bytes()).hexdigest())
        expected = {name: hashlib.sha256(content).hexdigest()
                    for name, content in files.items()}
        if actual_files != expected or actual_directories != directories:
            fail("IMMUTABLE_RELEASE_CONTENT_MISMATCH")
        return release
    staging = Path(tempfile.mkdtemp(prefix=".staging-", dir=str(releases)))
    try:
        for name in sorted(directories, key=lambda value: (value.count("/"), value)):
            staging.joinpath(*name.split("/")).mkdir(mode=0o755)
        for name, content in files.items():
            path = staging.joinpath(*name.split("/"))
            with path.open("xb") as target:
                target.write(content)
            path.chmod(0o444)
        for name in sorted(directories, key=lambda value: value.count("/"), reverse=True):
            staging.joinpath(*name.split("/")).chmod(0o555)
        staging.chmod(0o555)
        os.rename(str(staging), str(release))
        return release
    finally:
        if staging.exists():
            remove_tree(staging)


def sync_directory(path):
    directory = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def write_document(path, document):
    descriptor, temporary = tempfile.mkstemp(prefix=".receipt-", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(document, target, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, str(path))
        sync_directory(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return document


def write_receipt(path, commit, migrations, status, previous_commit):
    if not re.fullmatch(r"[0-9a-f]{40}", previous_commit):
        fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")
    document = {"commit": commit, "status": status,
                "previous_commit": previous_commit,
                "migration_hashes": {version: {"name": item["name"],
                                                "sha256": item["sha256"]}
                                     for version, item in migrations.items()}}
    return write_document(path, document)


def read_document(path):
    require_trusted(path)
    with path.open("rb") as source:
        raw = source.read(256 * 1024 + 1)
    if len(raw) > 256 * 1024:
        fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)


def application_files(path):
    require_trusted(path, directory=True)
    files, directories, total = {}, set(), 0
    for current, names, members in os.walk(str(path), followlinks=False):
        base = Path(current)
        for name in names:
            child = base / name
            require_trusted(child, directory=True)
            directories.add(child.relative_to(path).as_posix())
        for name in members:
            child = base / name
            require_trusted(child)
            total += child.stat().st_size
            if total > MAX_EXPANDED or len(files) + len(directories) >= MAX_MEMBERS:
                fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")
            files[child.relative_to(path).as_posix()] = hashlib.sha256(child.read_bytes()).hexdigest()
    return files, directories


def current_application(root, functions):
    """Validate live files AND global imports before asserting any app commit.

    A pending receipt is prospective DB state. If interrupted activation changed
    the live app, recover its identity from exact release content, not the label.
    """
    receipt_path = root / "deploy-receipt.json"
    try:
        document = read_document(receipt_path) if receipt_path.exists() else None
        if document is None:
            baseline = read_document(root / "deployment-receipt.json")["applicationCommit"]
            candidates = [BASELINE_COMMIT if baseline == BASELINE else baseline]
        elif document["status"] == "deployed":
            candidates = [document["commit"]]
        elif document["status"] == "pending":
            candidates = [document.get("previous_commit", BASELINE_COMMIT), document["commit"]]
        else:
            fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")
        live = application_files(functions / "library-api")
        current_imports = read_document(functions / "deno.jsonc")["imports"]
        for commit in candidates:
            if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
                fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")
            release = root / "releases" / (BASELINE if commit == BASELINE_COMMIT else commit)
            if not release.exists():
                continue
            for path in (release, release / "supabase", release / "supabase/functions"):
                require_trusted(path, directory=True)
            app = release.joinpath(*APP.split("/"))
            if (live == application_files(app)
                    and current_imports == read_document(app / "deno.json")["imports"]):
                if document is not None and document["status"] == "pending":
                    document["previous_commit"] = commit
                    write_document(receipt_path, document)
                return commit
    except Exception:
        pass
    # Never let the backup reader interpret an ambiguous app as an old commit.
    write_document(root / "deploy-unresolved.json",
                   {"status": "unresolved", "operation": "application_identity"})
    fail("DEPLOY_APPLICATION_IDENTITY_UNRESOLVED")


def deployment_import_map(files, current):
    try:
        config = json.loads(files[APP + "/deno.json"].decode("utf-8"),
                            object_pairs_hook=unique_object)
        imports = config["imports"]
        if (not isinstance(imports, dict) or not imports
                or any(not isinstance(key, str) or not isinstance(value, str)
                       for key, value in imports.items())):
            fail("DEPLOY_IMPORT_MAP_INVALID")
        # This stack's deno.jsonc is plain JSON; never rewrite unparsed JSONC.
        document = json.loads(current.decode("utf-8"), object_pairs_hook=unique_object)
        if not isinstance(document, dict):
            fail("DEPLOY_IMPORT_MAP_INVALID")
        document["imports"] = imports
        return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8")
    except (KeyError, TypeError, ValueError, UnicodeError):
        fail("DEPLOY_IMPORT_MAP_INVALID")


def restart_functions(stack):
    run_command([DOCKER, "restart", "supabase-edge-functions"], stack, 60,
                "DEPLOY_FUNCTION_RESTART_FAILED")


def activate_functions(release, functions, import_map, stack, health):
    """Rename only application/config files; retain backups if restoration fails."""
    work = Path(tempfile.mkdtemp(prefix=".deploy-", dir=str(functions)))
    live, global_map = functions / "library-api", functions / "deno.jsonc"
    old_app, old_map = work / "previous-library-api", work / "previous-deno.jsonc"
    cleanup = True
    try:
        shutil.copytree(str(release.joinpath(*APP.split("/"))), str(work / "next"))
        (work / "next-deno.jsonc").write_bytes(import_map)
        (work / "next-deno.jsonc").chmod(0o644)
        try:
            os.rename(str(live), str(old_app))
            os.rename(str(work / "next"), str(live))
            os.rename(str(global_map), str(old_map))
            os.rename(str(work / "next-deno.jsonc"), str(global_map))
            restart_functions(stack)
            if not health():
                fail("DEPLOY_HEALTH_FAILED")
        except Exception as error:
            try:
                if old_app.exists():
                    if live.exists():
                        os.rename(str(live), str(work / "failed-library-api"))
                    os.rename(str(old_app), str(live))
                if old_map.exists():
                    os.replace(str(old_map), str(global_map))
                restart_functions(stack)
            except Exception:
                cleanup = False
                fail("DEPLOY_API_RESTORE_FAILED_MIGRATIONS_MAY_PERSIST")
            if isinstance(error, DeployError) and error.code == "DEPLOY_HEALTH_FAILED":
                fail("DEPLOY_HEALTH_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST")
            fail("DEPLOY_API_FAILED_FUNCTIONS_RESTORED_MIGRATIONS_MAY_PERSIST")
    finally:
        if cleanup:
            remove_tree(work)


def read_anon_key(path):
    key = None
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            match = re.match(r"\s*ANON_KEY\s*=\s*(.*?)\s*$", line)
            if match:
                if key is not None:
                    fail("DEPLOY_HEALTH_KEY_INVALID")
                key = match.group(1)
                if len(key) >= 2 and key[0] in "\"'" and key[-1] == key[0]:
                    key = key[1:-1]
    if key is None or not re.fullmatch(r"[A-Za-z0-9_.-]{20,8192}", key):
        fail("DEPLOY_HEALTH_KEY_INVALID")
    return key


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def wait_for_health(key, attempts=30):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(
        "http://127.0.0.1:8000/functions/v1/library-api/v1/health",
        headers={"apikey": key, "Authorization": "Bearer " + key,
                 "Connection": "close", "Accept": "application/json"})
    for attempt in range(attempts):
        try:
            with opener.open(request, timeout=3) as response:
                raw = response.read(8193)
                if (response.getcode() == 200 and len(raw) <= 8192
                        and json.loads(raw.decode("utf-8")).get("status") == "ok"):
                    return True
        except Exception:
            pass  # Never expose response bodies, URLs, headers, or exceptions.
        if attempt + 1 < attempts:
            time.sleep(2)
    return False


def deploy(commit, files, directories, root=ROOT):
    stack = root / "supabase/docker"
    functions = stack / "volumes/functions"
    releases = root / "releases"
    for path in (root, root / "supabase", stack, stack / "volumes", functions,
                 releases):
        require_trusted(path, directory=True)
    require_trusted(stack / ".env")
    migrations = collect_migrations(files)
    with deployment_lock(root):
        unresolved = root / "deploy-unresolved.json"
        if unresolved.exists() or unresolved.is_symlink():
            fail("DEPLOY_UNRESOLVED_OPERATION_BLOCKED")
        previous_commit = current_application(root, functions)
        receipt = root / "deploy-receipt.json"
        known = load_hashes(receipt)
        baseline = releases / BASELINE / "supabase/migrations"
        if baseline.exists():
            for path in (releases / BASELINE, releases / BASELINE / "supabase", baseline):
                require_trusted(path, directory=True)
            for path in baseline.iterdir():
                require_trusted(path)
            baseline_migrations = collect_migrations({MIGRATIONS + "/" + path.name:
                                                     path.read_bytes()
                                                     for path in baseline.iterdir()})
            for version, item in baseline_migrations.items():
                original = {"name": item["name"], "sha256": item["sha256"]}
                if version in known and known[version] != original:
                    fail("MIGRATION_BASELINE_RECEIPT_CONFLICT")
                known[version] = original
        history = read_history(stack)
        pending = migration_plan(migrations, history, known)
        import_map = deployment_import_map(files, (functions / "deno.jsonc").read_bytes())
        key = read_anon_key(stack / ".env")  # Memory only; never argv/env/logs.
        # Required even for a repeated commit. No persistent staging, DB, or API
        # mutation happens before the external backup exits successfully.
        mandatory_backup(stack)
        release = stage_release(releases, commit, files, directories)
        # Persist prospective hashes before commit: after a crash, compare only
        # versions actually present in DB history. This is NOT a success receipt.
        write_receipt(receipt, commit, migrations, "pending", previous_commit)
        if pending:
            psql(stack, migration_transaction(migrations, pending), transaction=True)
        # Independent backups must not label a partially switched application
        # with pending.previous_commit after an interruption.
        write_document(unresolved, {"status": "unresolved", "operation": "activation",
                                    "commit": commit, "previous_commit": previous_commit})
        try:
            activate_functions(release, functions, import_map, stack,
                               lambda: wait_for_health(key))
        except Exception:
            # activate_functions attempts restoration; positively verify both
            # file identity and health before allowing backups/redeployment.
            if (current_application(root, functions) == previous_commit
                    and wait_for_health(key)):
                unresolved.unlink()
                sync_directory(root)
            raise
        result = write_receipt(receipt, commit, migrations, "deployed", previous_commit)
        unresolved.unlink()
        sync_directory(root)
        return result


def main():
    try:
        if len(sys.argv) != 1 or not hasattr(os, "geteuid") or os.geteuid() != 0:
            fail("DEPLOY_ROOT_NO_ARGUMENTS_REQUIRED")
        require_trusted(Path(__file__).resolve())
        os.umask(0o077)
        commit, files, directories = read_payload(sys.stdin.buffer)
        receipt = deploy(commit, files, directories)
        print(json.dumps(receipt, sort_keys=True))
        return 0
    except DeployError as error:
        print(error.code, file=sys.stderr)
    except Exception:
        print("DEPLOY_FAILED_MIGRATIONS_MAY_PERSIST", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
