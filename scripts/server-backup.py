#!/usr/bin/env python3
"""Encrypted, root-only Supabase recovery snapshots (Python 3.6+, GNU tar).

DOWNTIME: stop every running project service except db before dumping, and keep
API/Auth/Storage/cron targets stopped through archive/encryption. Direct SQL
writers must stay disabled (this stack is loopback-only). Finally restarts exactly
the previously running containers, including failed stop/dump/age operations.
SIGKILL, power loss, or a broken Docker daemon cannot be repaired by finally.

No args is receiver-only: caller MUST hold deploy.lock. --stream acquires that
lock before the distinct backup.lock; stdout is exclusively binary age output.
The PUBLIC age recipient is the only key on-server. Private staging is removed
in finally; deletion is not secure erasure on SSD/COW storage. Encrypt host disks.
"""

import contextlib
import datetime
import errno
import json
import os
import re
import select
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

BASELINE = "fbd11f1b49f423c942e52ebe4c7c783f363c6d99"
SHA = re.compile(r"^[0-9a-f]{40}$")
OWNED_NAME = re.compile(r"^link-vault-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}\.tar\.age$")
AGE_HEADER = b"age-encryption.org/v1\n"
TOTAL_SECONDS, RESTART_RESERVE = 600, 180
RESTORE = (
    "Decrypt off-server with age -d -i PRIVATE_IDENTITY -o snapshot.tar SNAPSHOT.tar.age; "
    "extract into an empty root-private directory. Pin all Compose images from "
    "manifest.json (image binaries are not bundled). Recreate an isolated clean "
    "stack from stack/, application/releases/, recovery/bin/ and receipts; never "
    "overlay a running stack or reuse live DB data. BEFORE starting db, extract "
    "database/postgresql-custom.tar as root with tar --numeric-owner --same-owner "
    "--same-permissions -xf ARCHIVE -C CONFIG_PARENT into an empty DB configuration "
    "volume parent; mount its postgresql-custom directory at /etc/postgresql-custom. "
    "The nested tar preserves pgsodium_root.key numeric UID/GID and mode; verify "
    "against manifest.json. Never docker cp or chown this key to root. Start only db. Restore globals.sql "
    "as superuser, explicitly reconciling bootstrap roles without ignoring errors. "
    "Use pg_restore --exit-on-error to restore postgres.dump into a clean postgres "
    "database, preserving owners/ACLs and explicitly resolving bootstrap objects. "
    "Restore .env, Compose configuration and captured Storage/other volumes before "
    "starting the previously running services. Verify migration history, Vault "
    "decryption, API/Auth/Storage, cron and app health while still loopback-isolated. "
    "Keep private identity off-server and rehearse a complete isolated restore."
)
CAVEATS = (
    "Downtime lasts through dumping, archiving and encryption. Direct SQL writers "
    "are outside the freeze and must be disabled. Live volumes/db/data, .git and "
    "caches are excluded. Symlinks are preserved, never followed. Private staging "
    "is removed but not securely erased. Only seven completed local snapshots "
    "are retained; use --stream for off-server recovery copies. Image IDs/digests "
    "are pinned, but image binaries must remain available separately."
)


class BackupError(Exception):
    pass


class Config:
    root = "/opt/link-vault"
    stack = root + "/supabase/docker"
    backups = root + "/backups"
    recipient = "/etc/link-vault/backup-recipient.txt"
    age = "/usr/local/bin/age"
    scripts = ("/usr/local/sbin/link-vault-deploy", "/usr/local/sbin/link-vault-backup")


class Deadline:
    def __init__(self, end, clock):
        self.end, self.clock = end, clock

    def remaining(self, maximum=TOTAL_SECONDS):
        value = min(maximum, self.end - self.clock())
        if value <= 0:
            raise BackupError()
        return value


class Runner:
    def run(self, command, cwd, timeout, stdout=None, stdin=None):
        try:
            result = subprocess.run(command, cwd=cwd, timeout=timeout, check=True,
                                    stdin=stdin, stdout=subprocess.PIPE if stdout is None else stdout,
                                    stderr=subprocess.PIPE)
            return result.stdout if stdout is None else b""
        except (OSError, subprocess.SubprocessError):
            # Never relay SQL, credentials, .env contents, or Docker logs.
            raise BackupError()


class Filesystem:
    def check(self, path, directory=False, root_owned=True):
        # Reject symlinked ancestors as well as the leaf; staging/lock/output
        # directories are root-owned and not writable by other users.
        absolute = os.path.abspath(path)
        current = os.path.dirname(absolute)
        while current != os.path.dirname(current):
            if stat.S_ISLNK(os.lstat(current).st_mode):
                raise BackupError()
            current = os.path.dirname(current)
        info = os.lstat(absolute)
        valid = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
        if not valid or (root_owned and (info.st_uid != 0 or info.st_mode & 0o022)):
            raise BackupError()
        return info

    def prepare(self, config):
        for path in (config.root, config.stack, config.root + "/releases"):
            self.check(path, directory=True)
        if not os.path.lexists(config.backups):
            os.mkdir(config.backups, 0o700)
        self.check(config.backups, directory=True)
        os.chmod(config.backups, 0o700)
        for path in config.scripts + (config.recipient, config.age):
            self.check(path)

    def exists(self, path):
        return os.path.lexists(path)

    def read(self, path):
        self.check(path)
        with self.open(path) as source:
            value = source.read(1024 * 1024 + 1)
        if len(value) > 1024 * 1024:
            raise BackupError()
        return value

    def open(self, path, write=False):
        if not write:
            self.check(path)
        else:
            self.check(os.path.dirname(path), directory=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL if write else os.O_RDONLY
        return os.fdopen(os.open(path, flags | os.O_NOFOLLOW, 0o600), "wb" if write else "rb")

    def temporary(self, backups):
        return tempfile.mkdtemp(prefix=".incomplete-", dir=backups)  # mode 0700

    def mkdir(self, path):
        os.mkdir(path, 0o700)

    def cleanup(self, path):
        if not shutil.rmtree.avoids_symlink_attacks:
            raise BackupError()
        shutil.rmtree(path)

    def publish(self, source, destination):
        self.check(source)
        with self.open(source) as data:
            if data.read(len(AGE_HEADER)) != AGE_HEADER or os.fstat(data.fileno()).st_size <= len(AGE_HEADER):
                raise BackupError()
            os.fsync(data.fileno())
        os.chmod(source, 0o600)
        os.link(source, destination, follow_symlinks=False)  # never overwrite
        os.unlink(source)
        directory = os.open(os.path.dirname(destination), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)

    def rotate(self, backups):
        completed = []
        for name in os.listdir(backups):
            if not OWNED_NAME.fullmatch(name):
                continue
            info = os.lstat(os.path.join(backups, name))
            if stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1:
                completed.append((info.st_mtime, name, info))
        for unused, name, before in sorted(completed, reverse=True)[7:]:
            path = os.path.join(backups, name)
            after = os.lstat(path)
            if (before.st_dev, before.st_ino, before.st_mode, before.st_uid, before.st_nlink) != (
                    after.st_dev, after.st_ino, after.st_mode, after.st_uid, after.st_nlink):
                raise BackupError()
            os.unlink(path)

    def stream(self, path, output, deadline):
        import fcntl
        fd = output.fileno()
        flags = fcntl.fcntl(fd, fcntl.F_GETFL)
        fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
        try:
            with self.open(path) as source:
                while True:
                    deadline.remaining()
                    chunk = source.read(65536)
                    if not chunk:
                        break
                    pending = memoryview(chunk)
                    while pending:
                        if not select.select([], [fd], [], deadline.remaining())[1]:
                            raise BackupError()
                        try:
                            written = os.write(fd, pending)
                        except BlockingIOError:
                            continue
                        if not written:
                            raise BackupError()
                        pending = pending[written:]
        finally:
            fcntl.fcntl(fd, fcntl.F_SETFL, flags)

    @contextlib.contextmanager
    def lock(self, path, deadline):
        import fcntl
        self.check(os.path.dirname(path), directory=True)
        fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            self.check(path)
            end = min(deadline.end, deadline.clock() + 30)
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError as error:
                    if error.errno not in (errno.EACCES, errno.EAGAIN):
                        raise
                    remaining = end - deadline.clock()
                    if remaining <= 0:
                        raise BackupError()
                    time.sleep(min(0.1, remaining))
            yield
        finally:
            os.close(fd)


INSPECT = ('{"id":{{json .Id}},"name":{{json .Name}},"image_id":{{json .Image}},'
           '"image_reference":{{json .Config.Image}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
           '"project":{{json (index .Config.Labels "com.docker.compose.project")}},"running":{{json .State.Running}}}')


def archive_command(config, temporary, receipts):
    # GNU tar does not follow symlinks unless --dereference/-h is requested.
    # Transform only member names (flags=r), NOT stored symlink targets.
    return ["tar", "--create", "--file", temporary + "/snapshot.tar",
            "--exclude=.git", "--exclude=cache", "--exclude=.cache", "--exclude=__pycache__",
            "--exclude=supabase/docker/volumes/db/data",
            "--transform=flags=r;s,^supabase/docker,stack,",
            "--transform=flags=r;s,^releases,application/releases,",
            "--transform=flags=r;s,^deployment-receipt.json$,recovery/deployment-receipt.json,",
            "--transform=flags=r;s,^deploy-receipt.json$,recovery/deploy-receipt.json,",
            "--transform=flags=r;s,^link-vault-deploy$,recovery/bin/link-vault-deploy,",
            "--transform=flags=r;s,^link-vault-backup$,recovery/bin/link-vault-backup,",
            "--directory", temporary, "database", "manifest.json",
            "--directory", config.root, "supabase/docker", "releases"] + receipts + [
            "--directory", "/usr/local/sbin", "link-vault-deploy", "link-vault-backup"]


def validate_config_archive(source, deadline):
    # Inspect only tar headers, never read/extract the Vault key or change owners.
    size = source.seek(0, os.SEEK_END)
    if size < 1024 or size % 512:
        raise BackupError()
    source.seek(size - 1024)
    if source.read(1024) != b"\0" * 1024:
        raise BackupError()
    source.seek(0)
    key, names = None, set()
    with tarfile.open(fileobj=source, mode="r:") as archive:
        for member in archive:
            deadline.remaining()
            name = member.name.rstrip("/")
            if (name in names or any(part in ("", ".", "..") for part in name.split("/"))
                    or not (name == "postgresql-custom" or name.startswith("postgresql-custom/"))
                    or not (member.isdir() or member.isfile())
                    or (name == "postgresql-custom" and not member.isdir())
                    or member.offset_data + ((member.size + 511) // 512) * 512 > size - 1024):
                raise BackupError()
            names.add(name)
            if name == "postgresql-custom/pgsodium_root.key":
                if not member.isfile() or member.size <= 0 or member.mode != 0o600:
                    raise BackupError()
                key = {"uid": member.uid, "gid": member.gid, "mode": member.mode}
    if key is None or "postgresql-custom" not in names:
        raise BackupError()
    return key


class Backup:
    def __init__(self, config=None, runner=None, filesystem=None, clock=time.monotonic):
        self.config, self.runner, self.fs, self.clock = config or Config(), runner or Runner(), filesystem or Filesystem(), clock

    def command(self, args, deadline, maximum=120, stdout=None, stdin=None):
        return self.runner.run(args, cwd=self.config.stack, timeout=deadline.remaining(maximum), stdout=stdout, stdin=stdin)

    def inspect(self, ids, deadline):
        raw = self.command(["docker", "inspect", "--format", INSPECT] + ids, deadline)
        values = [json.loads(line) for line in raw.decode("utf-8").splitlines()]
        if len(values) != len(ids) or {value["id"] for value in values} != set(ids):
            raise BackupError()
        for value in values:
            if (value.get("project") != "supabase" or not isinstance(value.get("running"), bool)
                    or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", value.get("service", ""))
                    or not re.fullmatch(r"sha256:[0-9a-f]{64}", value.get("image_id", ""))):
                raise BackupError()
        return values

    def receipts(self):
        config = self.config
        baseline = json.loads(self.fs.read(config.root + "/deployment-receipt.json").decode("utf-8"))
        commit = baseline["applicationCommit"]
        commit = BASELINE if commit == "fbd11f1" else commit  # verified initial source archive
        names = ["deployment-receipt.json"]
        if self.fs.exists(config.root + "/deploy-receipt.json"):
            receipt = json.loads(self.fs.read(config.root + "/deploy-receipt.json").decode("utf-8"))
            if receipt.get("status") not in ("pending", "deployed"):
                raise BackupError()
            commit = receipt["commit"] if receipt["status"] == "deployed" else receipt["previous_commit"]
            names.append("deploy-receipt.json")
        if not SHA.fullmatch(commit) or not SHA.fullmatch(baseline["supabaseCommit"]):
            raise BackupError()
        release = "fbd11f1" if commit == BASELINE else commit
        self.fs.check(config.root + "/releases/" + release, directory=True)
        return commit, baseline["supabaseCommit"], release, names

    def restart(self, ids, deadline):
        if not ids:
            return
        try:
            self.command(["docker", "start"] + ids, deadline, maximum=45)
        except Exception:
            for identifier in ids:
                try:
                    self.command(["docker", "start", identifier], deadline, maximum=10)
                except Exception:
                    pass
        if not all(item["running"] for item in self.inspect(ids, deadline)):
            raise BackupError()

    def snapshot(self, temporary, work, total):
        config = self.config
        ids = self.command(["docker", "compose", "ps", "--all", "--quiet"], work).decode("ascii").split()
        if not ids or any(not re.fullmatch(r"[0-9a-f]{64}", item) for item in ids):
            raise BackupError()
        containers = self.inspect(ids, work)
        db = [item for item in containers if item["name"] == "/supabase-db" and item["service"] == "db"]
        if len(db) != 1 or not db[0]["running"]:
            raise BackupError()
        for image_id in sorted({item["image_id"] for item in containers}):
            raw = self.command(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", image_id], work)
            digests = json.loads(raw.decode("utf-8")) or []
            if not isinstance(digests, list) or any(not isinstance(value, str) for value in digests):
                raise BackupError()
            for item in containers:
                if item["image_id"] == image_id:
                    item["repo_digests"] = digests
        commit, supabase_commit, release, receipts = self.receipts()
        running = [item for item in containers if item["running"] and item["id"] != db[0]["id"]]
        restart_ids = [item["id"] for item in running]
        services = sorted({item["service"] for item in running})
        self.fs.mkdir(temporary + "/database")
        try:
            if services:
                self.command(["docker", "compose", "stop", "--timeout", "30"] + services, work)
            states = self.inspect(ids, work)
            if any(item["running"] for item in states if item["id"] in restart_ids) or not next(item["running"] for item in states if item["id"] == db[0]["id"]):
                raise BackupError()
            dumps = (("postgres.dump", ["pg_dump", "--format=custom", "--dbname=postgres"]),
                     ("globals.sql", ["pg_dumpall", "--globals-only"]))
            for name, args in dumps:
                with self.fs.open(temporary + "/database/" + name, write=True) as output:
                    self.command(["docker", "compose", "exec", "-T", "db"] + args + ["--username=supabase_admin", "--no-password"], work, maximum=600, stdout=output)
            key_path = temporary + "/database/postgresql-custom.tar"
            # Both Compose cp --archive and Engine cp -a lose UID/GID here.
            # Archive inside the container; never extract plaintext keys on host.
            with self.fs.open(key_path, write=True) as output:
                self.command(["docker", "exec", "supabase-db", "tar", "-cf", "-", "-C", "/etc", "postgresql-custom"], work, stdout=output)
            with self.fs.open(key_path) as source:
                key_metadata = validate_config_archive(source, work)
            manifest = {"format_version": 1, "created_at": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
                        "application_commit": commit, "application_release": "application/releases/" + release,
                        "supabase_commit": supabase_commit, "containers": containers,
                        "postgresql_custom_key_metadata": key_metadata,
                        "previously_running_container_ids": [item["id"] for item in containers if item["running"]],
                        "restore_instructions": RESTORE, "caveats": CAVEATS}
            with self.fs.open(temporary + "/manifest.json", write=True) as output:
                output.write((json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"))
            with self.fs.open(temporary + "/snapshot.tar", write=True):
                pass  # reserve a private 0600 output before tar truncates it
            self.command(archive_command(config, temporary, receipts), work, maximum=600)
            recipient = self.fs.read(config.recipient).decode("ascii").strip()
            if not re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", recipient):
                raise BackupError()
            encrypted = temporary + "/snapshot.tar.age"
            with self.fs.open(temporary + "/snapshot.tar") as source:
                with self.fs.open(encrypted, write=True) as output:
                    self.command([config.age, "--encrypt", "--recipient", recipient], work, maximum=600, stdout=output, stdin=source)
            return encrypted
        finally:
            self.restart(restart_ids, total)

    def execute(self, output, stream=False):
        total = Deadline(self.clock() + TOTAL_SECONDS, self.clock)
        # Reserve restart/cleanup time inside our 600s bound, before the
        # deployment receiver's longer 900s subprocess timeout can terminate us.
        work = Deadline(total.end - RESTART_RESERVE, self.clock)
        self.fs.prepare(self.config)
        with contextlib.ExitStack() as locks:
            if stream:
                locks.enter_context(self.fs.lock(self.config.root + "/deploy.lock", work))
            locks.enter_context(self.fs.lock(self.config.root + "/backup.lock", work))
            # lexists also rejects a dangling symlink. An unresolved migration
            # backend may still be writing: reconciliation must precede capture.
            if self.fs.exists(self.config.root + "/deploy-unresolved.json"):
                raise BackupError()
            temporary = None
            try:
                temporary = self.fs.temporary(self.config.backups)
                encrypted = self.snapshot(temporary, work, total)
                name = "link-vault-" + datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:16] + ".tar.age"
                completed = self.config.backups + "/" + name
                self.fs.publish(encrypted, completed)
            finally:
                if temporary is not None:
                    self.fs.cleanup(temporary)
            if stream:
                self.fs.stream(completed, output, total)
            total.remaining()
            self.fs.rotate(self.config.backups)  # only after restart, cleanup and transfer succeed
            if not stream:
                output.write((name + "\n").encode("ascii"))
            return name


def main(argv=None, backup=None, output=None, error_output=None, getuid=None):
    argv = sys.argv[1:] if argv is None else argv
    output = sys.stdout.buffer if output is None else output
    error_output = sys.stderr if error_output is None else error_output
    getuid = os.geteuid if getuid is None else getuid
    if argv not in ([], ["--stream"]):
        error_output.write("BACKUP_USAGE\n")
        return 2
    if getuid() != 0:
        error_output.write("BACKUP_ROOT_REQUIRED\n")
        return 1
    try:
        (backup or Backup()).execute(output, stream=bool(argv))
    except (Exception, KeyboardInterrupt):
        error_output.write("BACKUP_FAILED\n")
        return 1
    error_output.write("BACKUP_OK\n")
    return 0


def interrupted(signum, frame):
    raise BackupError()


if __name__ == "__main__":
    for termination_signal in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(termination_signal, interrupted)
    sys.exit(main())
