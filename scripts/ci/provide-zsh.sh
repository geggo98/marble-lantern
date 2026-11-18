#!/bin/bash
# CI helper: makes /bin/zsh exist, because the repository scripts start with #!/bin/zsh and the Ubuntu runner has no zsh.
# Run it inside devenv, which provides zsh: devenv shell -- scripts/ci/provide-zsh.sh
# Exit 0 = /bin/zsh runs, 1 = failure. Idempotent. Needs passwordless sudo (true on GitHub-hosted runners).
set -euo pipefail
if /bin/zsh -c 'exit 0' 2>/dev/null; then echo "provide-zsh: /bin/zsh already works"; exit 0; fi
zsh_path=$(command -v zsh) || { echo "provide-zsh: zsh is not on PATH; run this inside devenv" >&2; exit 1; }
sudo ln -sf "$zsh_path" /bin/zsh
/bin/zsh -c 'exit 0' || { echo "provide-zsh: /bin/zsh does not run after linking $zsh_path" >&2; exit 1; }
echo "provide-zsh: /bin/zsh -> $zsh_path"
