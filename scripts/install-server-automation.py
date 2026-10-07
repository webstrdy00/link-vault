#!/usr/bin/python3
"""Run as root from the reviewed scripts directory; stdin contains PUBLIC keys only."""
import base64
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys
import tempfile

ROOT = Path('/opt/link-vault')
BIN = Path('/usr/local/sbin')


def command(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError('INSTALL_COMMAND_FAILED')


def write_atomic(path, data, mode):
    descriptor, temporary = tempfile.mkstemp(prefix='.link-vault-', dir=str(path.parent))
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
        os.chmod(temporary, mode)
        os.chown(temporary, 0, 0)
        os.replace(temporary, str(path))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def public_key(value):
    if not isinstance(value, str) or '\n' in value or '\r' in value:
        raise ValueError('INVALID_PUBLIC_KEY')
    parts = value.split()
    if len(parts) < 2 or parts[0] != 'ssh-ed25519':
        raise ValueError('INVALID_PUBLIC_KEY')
    raw = base64.b64decode(parts[1], validate=True)
    if len(raw) != 51 or raw[:19] != b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20':
        raise ValueError('INVALID_PUBLIC_KEY')
    return ' '.join(parts[:2])


def main():
    if os.getuid() != 0:
        raise RuntimeError('ROOT_REQUIRED')
    raw = sys.stdin.buffer.read(8193)
    if len(raw) > 8192:
        raise ValueError('INPUT_LIMIT')
    values = json.loads(raw.decode('utf-8'))
    if set(values) != {'deploy', 'backup'}:
        raise ValueError('INVALID_INSTALL_INPUT')
    keys = {name: public_key(values[name]) for name in values}
    source = Path(__file__).resolve().parent
    if not ROOT.is_dir() or not Path('/etc/link-vault/backup-recipient.txt').is_file():
        raise RuntimeError('PRIVATE_STACK_AND_BACKUP_RECIPIENT_REQUIRED')
    for name in ('deploy', 'backup'):
        data = (source / ('server-' + name + '.py')).read_bytes()
        compile(data, 'server-' + name + '.py', 'exec')
        write_atomic(BIN / ('link-vault-' + name), data, 0o700)
        suffix = '' if name == 'deploy' else ' --stream'
        entry = ('#!/bin/sh\nexec /usr/bin/timeout --signal=TERM --kill-after=180s 1500 '
                 '/usr/local/sbin/link-vault-' + name + suffix + '\n')
        write_atomic(BIN / ('link-vault-' + name + '-entry'), entry.encode(), 0o700)
        user = 'linkvault-' + name
        home = Path('/var/lib') / user
        try:
            account = pwd.getpwnam(user)
            if account.pw_dir != str(home) or account.pw_uid == 0:
                raise RuntimeError('EXISTING_ACCOUNT_MISMATCH')
        except KeyError:
            command(['/usr/sbin/useradd', '--system', '--create-home', '--home-dir', str(home),
                     '--shell', '/bin/sh', user])
        home.mkdir(mode=0o755, exist_ok=True)
        if home.is_symlink():
            raise RuntimeError('UNSAFE_ACCOUNT_HOME')
        os.chown(str(home), 0, 0)
        os.chmod(str(home), 0o755)
        ssh = home / '.ssh'
        ssh.mkdir(mode=0o755, exist_ok=True)
        if ssh.is_symlink():
            raise RuntimeError('UNSAFE_SSH_DIRECTORY')
        os.chown(str(ssh), 0, 0)
        os.chmod(str(ssh), 0o755)
        authorized = ('restrict,command="sudo -n /usr/local/sbin/link-vault-' + name +
                      '-entry" ' + keys[name] + ' link-vault-' + name + '-ci\n')
        current = ssh / 'authorized_keys'
        if current.exists() and current.read_text() != authorized:
            raise RuntimeError('EXISTING_AUTHORIZED_KEYS_DIFFER')
        write_atomic(current, authorized.encode(), 0o644)
        sudo = (user + ' ALL=(root) NOPASSWD: /usr/local/sbin/link-vault-' + name + '-entry ""\n')
        destination = Path('/etc/sudoers.d') / ('link-vault-' + name)
        descriptor, temporary = tempfile.mkstemp(prefix='.sudo-', dir='/etc/sudoers.d')
        try:
            with os.fdopen(descriptor, 'w') as stream:
                stream.write(sudo)
            os.chmod(temporary, 0o440)
            command(['/usr/sbin/visudo', '-cf', temporary])
            os.replace(temporary, str(destination))
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        command(['/usr/sbin/restorecon', '-RF', str(home)])
    service = ('[Unit]\nDescription=Encrypted Link Vault recovery snapshot\nAfter=docker.service\n'
               'Requires=docker.service\n\n[Service]\nType=oneshot\nUser=root\nUMask=0077\n'
               'ExecStart=/usr/bin/flock -w 60 /opt/link-vault/deploy.lock /usr/local/sbin/link-vault-backup\n'
               'TimeoutStartSec=900\nTimeoutStopSec=180\n')
    timer = ('[Unit]\nDescription=Daily Link Vault encrypted snapshot\n\n[Timer]\n'
             'OnCalendar=*-*-* 18:00:00 UTC\nPersistent=true\nRandomizedDelaySec=300\n'
             '\n[Install]\nWantedBy=timers.target\n')
    write_atomic(Path('/etc/systemd/system/link-vault-backup.service'), service.encode(), 0o644)
    write_atomic(Path('/etc/systemd/system/link-vault-backup.timer'), timer.encode(), 0o644)
    command(['/usr/bin/systemctl', 'daemon-reload'])
    # Enable only after the parent completes a real backup/restore rehearsal.
    print('Restricted receivers installed; backup timer requires explicit enable after verification')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('SERVER_AUTOMATION_INSTALL_FAILED', file=sys.stderr)
        sys.exit(1)
