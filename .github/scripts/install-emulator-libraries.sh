#!/usr/bin/env bash
# Scope APT to signed Ubuntu HTTPS repositories; never retry or skip application tests.
set -euo pipefail
if ldconfig -p | grep 'libpulse.so.0' >/dev/null; then
  echo 'Android Emulator runtime library already installed.'
  exit 0
fi
os_release="${LUMEN_CI_OS_RELEASE:-/etc/os-release}"
codename="$(sed -n 's/^VERSION_CODENAME=//p' "$os_release" | tr -d '"')"
[[ "$codename" =~ ^[a-z][a-z0-9]+$ ]] || { echo '::error::Invalid Ubuntu codename'; exit 1; }
apt_directory="${LUMEN_CI_APT_DIRECTORY:-$PWD/.ci-apt}"
mkdir -p "$apt_directory"
source_file="$apt_directory/ubuntu.sources"
cat > "$source_file" <<EOF
Types: deb
URIs: https://archive.ubuntu.com/ubuntu
Suites: $codename $codename-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu
Suites: $codename-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
options=(-o "Dir::Etc::sourcelist=$source_file" -o 'Dir::Etc::sourceparts=-'
  -o 'Acquire::Retries=2' -o 'Acquire::http::Timeout=30' -o 'Acquire::https::Timeout=30')
sudo timeout 180s apt-get "${options[@]}" update
sudo timeout 180s apt-get "${options[@]}" install --no-install-recommends -y libpulse0
