#!/bin/bash
set -euo pipefail

sudo apt-get clean
sudo fstrim -av || true
if ! sudo docker compose version; then
    sudo install -d -m 755 /usr/local/lib/docker/cli-plugins
    sudo ln -sf /usr/bin/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
    sudo docker compose version
fi

export PATH="$HOME/.local/bin:$PATH"
# The installers verify their downloaded binary checksums. Keep this
# preparation idempotent when a verification command needs to be rerun.
if ! command -v codex >/dev/null; then
    curl -fsSL https://chatgpt.com/codex/install.sh -o /tmp/private-codex-install.sh
    CODEX_NON_INTERACTIVE=1 sh /tmp/private-codex-install.sh
fi
if ! command -v claude >/dev/null; then
    curl -fsSL https://claude.ai/install.sh -o /tmp/private-claude-install.sh
    bash /tmp/private-claude-install.sh stable
fi
codex --version
codex sandbox --help
codex sandbox -- /bin/true
claude --version

sudo usermod -aG docker dex
sudo env DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y unattended-upgrades
printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' |
    sudo tee /etc/apt/apt.conf.d/20auto-upgrades >/dev/null
sudo systemctl enable --now apt-daily.timer apt-daily-upgrade.timer
sudo apt-get clean
# The cloud-init configuration is already applied; retain the locked account
# and private console without rediscovering a datasource on every boot.
sudo touch /etc/cloud/cloud-init.disabled
sudo fstrim -av || true
printf 'PRIVATE_'
printf 'GUEST_TOOLS_OK\n'
