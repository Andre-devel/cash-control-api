bash id="z7v4qn"
#!/bin/bash

set -euo pipefail

INPUT_FILE="${1:-docs/v1/project-phases.md}"

PHASES_DIR=".phases"
LOG_DIR="$PHASES_DIR/logs"

mkdir -p "$LOG_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
  echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $1"
}

success() {
  echo -e "${GREEN}[$(date '+%H:%M:%S')] $1${NC}"
}

warn() {
  echo -e "${YELLOW}[$(date '+%H:%M:%S')] $1${NC}"
}

fail() {
  echo -e "${RED}[$(date '+%H:%M:%S')] $1${NC}"
}

format_duration() {
  local total_seconds=$1

  local hours=$((total_seconds / 3600))
  local minutes=$(((total_seconds % 3600) / 60))
  local seconds=$((total_seconds % 60))

  if [ $hours -gt 0 ]; then
    printf "%dh %dm %ds" $hours $minutes $seconds
  elif [ $minutes -gt 0 ]; then
    printf "%dm %ds" $minutes $seconds
  else
    printf "%ds" $seconds
  fi
}

preflight_checks() {

  if ! command -v claude >/dev/null 2>&1; then
    fail "Claude Code nao encontrado."
    fail "Instale com:"
    echo "npm install -g @anthropic-ai/claude-code"
    exit 1
  fi

  if [ ! -f "$INPUT_FILE" ]; then
    fail "Arquivo nao encontrado: $INPUT_FILE"
    exit 1
  fi

  if [ ! -d ".git" ]; then
    fail "Execute dentro de um repositorio git."
    exit 1
  fi

  success "Pre-checks OK"
}

build_prompt() {

cat <<'EOF'
- `@docs/v1/project-description.md`
- `@docs/v1/user-stories.md`
- `@docs/v1/database-schema.md`
- `@docs/v1/project-phases.md`

Inspect the current codebase and identify what is already implemented.

Find the FIRST pending phase in `@docs/v1/project-phases.md` that still contains unchecked tasks (`[ ]`).

Implement the ENTIRE phase completely, including:
- all sub-phases
- all tasks
- all required dependencies inside that phase

Do not partially implement the phase.

Do not skip ahead to future phases.

Update `@docs/v1/project-phases.md` as tasks are completed by changing:
- `[ ]` → `[x]`

Requirements:

- Follow the existing architecture, conventions, and patterns already present in the project.
- Use production-ready implementations only.
- Add automated tests for everything implemented.
- Use Flyway for schema changes.
- Keep the implementation aligned with the documentation files.
- Respect the project's stateless JWT architecture.
- Do not implement refresh tokens, token persistence, or session management.
- Keep security, LGPD, RBAC, and audit requirements consistent with the existing docs.
- Do not rewrite unrelated code.
- Do not add placeholder implementations or TODOs.

Before finishing:

- ensure the project compiles
- ensure tests pass
- ensure migrations are valid
- ensure imports and references are correct
- ensure project phase tracking was updated
- ensure the entire phase is fully completed
EOF

}

is_rate_limited() {

  local output="$1"

  echo "$output" | grep -qiE \
  "out of|usage limit|rate limit|try again later|resets at|reset at"
}

wait_for_limit_reset() {

  echo ""
  warn "Limite atingido."
  warn "Aguardando 5 minutos antes de tentar novamente..."
  echo ""

  sleep 300
}

run_loop() {

  while true; do

    local start_time
    start_time=$(date +%s)

    echo ""
    log "Executando proxima fase pendente..."
    echo ""

    local prompt
    prompt="$(build_prompt)"

    local tmp_log
    tmp_log="$(mktemp)"

    set +e

    claude --model claude-sonnet-4-6 --dangerously-skip-permissions <<EOF 2>&1 | tee "$tmp_log"
$prompt
EOF

    local exit_code=${PIPESTATUS[0]}

    set -e

    local end_time
    end_time=$(date +%s)

    local duration=$((end_time - start_time))

    local output
    output="$(cat "$tmp_log")"

    cat "$tmp_log" >> "$LOG_DIR/claude.log"

    rm -f "$tmp_log"

    echo ""

    if [ $exit_code -eq 0 ]; then

      success "Fase concluida ($(format_duration $duration))"

      git add -A

      git commit -m "feat: implement next project phase" --allow-empty >/dev/null 2>&1 || true

      success "Commit realizado"

      log "Contexto encerrado"
      log "Nova sessao limpa iniciando..."

      sleep 2

      continue
    fi

    if is_rate_limited "$output"; then

      wait_for_limit_reset

      continue
    fi

    fail "Erro nao relacionado a limite."
    fail "Execucao interrompida."

    exit 1
  done
}

main() {

  preflight_checks

  echo ""
  log "Projeto: java-auth-template"
  echo ""

  read -p "Iniciar implementacao? (Y/n) " -n 1 -r
  echo ""

  [[ $REPLY =~ ^[Nn]$ ]] && exit 0

  run_loop
}

main