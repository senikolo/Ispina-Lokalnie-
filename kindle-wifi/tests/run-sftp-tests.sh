#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fixture="$(mktemp -d "$HOME/.kindle-wifi-sftp.XXXXXX")"
trap 'if [ -r "$fixture/server/sshd.pid" ]; then sudo kill "$(cat "$fixture/server/sshd.pid")" || true; fi; rm -rf "$fixture"' EXIT
mkdir -p "$fixture/server" "$fixture/classes" "$fixture/documents"
chmod 700 "$fixture/server"
ssh-keygen -q -t ed25519 -N '' -f "$fixture/server/host"
ssh-keygen -q -t rsa -b 2048 -N '' -f "$fixture/server/client"
cp "$fixture/server/client.pub" "$fixture/server/authorized_keys"
printf '[127.0.0.1]:22222 %s\n' "$(cut -d ' ' -f1-2 "$fixture/server/host.pub")" > "$fixture/server/known_hosts"
cat > "$fixture/server/sshd_config" <<CONFIG
Port 22222
ListenAddress 127.0.0.1
PidFile $fixture/server/sshd.pid
HostKey $fixture/server/host
AuthorizedKeysFile $fixture/server/authorized_keys
AllowUsers $(id -un)
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
UsePAM yes
Subsystem sftp internal-sftp
CONFIG
sudo install -d /run/sshd
sudo /usr/sbin/sshd -E "$fixture/server/sshd.log" -f "$fixture/server/sshd_config"
ssh -F /dev/null -p 22222 -i "$fixture/server/client" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$fixture/server/known_hosts" 127.0.0.1 true || { sudo tail -30 "$fixture/server/sshd.log"; exit 1; }
curl --retry 3 -fsSL https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/0.2.21/jsch-0.2.21.jar -o "$fixture/jsch.jar"
cp="$fixture/classes:$fixture/jsch.jar:$ANDROID_HOME/platforms/android-35/android.jar"
src=src/pl/ispinalokalnie/kindlewifi
javac -encoding UTF-8 -cp "$cp" -d "$fixture/classes" \
    "$src"/{AudioFiles,BialolekaForecast,CompanionFiles,KindleTools,RadioInstaller,RemoteFiles,VerifiedTransfer}.java \
    tests/{AudioSftpTest,RemoteFilesTest,TransferTest}.java
java -cp "$cp" TransferTest "$fixture"
java -cp "$cp" RemoteFilesTest "$fixture"
java -cp "$cp" pl.ispinalokalnie.kindlewifi.AudioSftpTest "$fixture" 22222
