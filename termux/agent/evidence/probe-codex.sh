set -eu
mkdir -p "$HOME/lateral-agent-probe"
cd "$HOME/lateral-agent-probe"
curl -fL --retry 3 https://github.com/openai/codex/releases/download/rust-v0.153.4/codex-x86_64-unknown-linux-musl.tar.gz -o codex.tar.gz
echo 'f479424eca092484dc40d87ae28c44f4cc40234a60045d6131e493800d814a30  codex.tar.gz' | sha256sum -c -
tar -xzf codex.tar.gz
./codex-x86_64-unknown-linux-musl --version
./codex-x86_64-unknown-linux-musl exec --help
