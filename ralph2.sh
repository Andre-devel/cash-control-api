#!/bin/bash
#
# ralph.sh
#
# Orquestrador que le docs/v1/project-phases.md, quebra em fases,
# e alimenta cada uma ao Codex CLI ou Claude Code para implementacao automatica.
#
# Uso:
#   chmod +x ralph.sh
#   ./ralph.sh [--engine codex|claude] [--fresh] [--yes] [--status] [caminho-do-arquivo]
#
# Exemplos:
#   ./ralph.sh                          # default: codex, retoma de onde parou
#   ./ralph.sh --engine claude          # usa Claude Code
#   ./ralph.sh --status                 # mostra progresso sem executar
#   ./ralph.sh --fresh                  # apaga progresso e reprocessa tudo
#   ./ralph.sh --yes                    # pula confirmacoes (util para retomar)
#   ./ralph.sh --engine codex docs/v1/project-phases.md
#
# Pre-requisitos:
#   - Codex: npm install -g @openai/codex + OPENAI_API_KEY
#   - Claude: npm install -g @anthropic-ai/claude-code + ANTHROPIC_API_KEY
#   - Estar na raiz do projeto Laravel (dentro de um repo git)

set -euo pipefail

ENGINE="claude"
INPUT_FILE=""
FRESH=false
AUTO_YES=false
SHOW_STATUS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --engine)
      ENGINE="$2"
      shift 2
      ;;
    --engine=*)
      ENGINE="${1#*=}"
      shift
      ;;
    --fresh)
      FRESH=true
      shift
      ;;
    --yes|-y)
      AUTO_YES=true
      shift
      ;;
    --status)
      SHOW_STATUS=true
      shift
      ;;
    *)
      INPUT_FILE="$1"
      shift
      ;;
  esac
done

INPUT_FILE="${INPUT_FILE:-docs/v1/project-phases.md}"

if [[ "$ENGINE" != "codex" && "$ENGINE" != "claude" ]]; then
  echo "Engine invalida: $ENGINE. Use 'codex' ou 'claude'."
  exit 1
fi
PHASES_DIR=".phases"
LOG_DIR=".phases/logs"
PROMPT_DIR=".phases/prompts"
MANIFEST="$PHASES_DIR/manifest.txt"
PROGRESS_FILE="$PHASES_DIR/.progress"
CURRENT_FILE="$PHASES_DIR/.current"
SOURCE_HASH_FILE="$PHASES_DIR/.source-hash"
MAX_RETRIES=2

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()     { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $1"; }
success() { echo -e "${GREEN}[$(date '+%H:%M:%S')] $1${NC}"; }
warn()    { echo -e "${YELLOW}[$(date '+%H:%M:%S')] $1${NC}"; }
fail()    { echo -e "${RED}[$(date '+%H:%M:%S')] $1${NC}"; }

format_duration() {
  local total_seconds=$1
  local hours=$((total_seconds / 3600))
  local minutes=$(( (total_seconds % 3600) / 60 ))
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
  if [[ "$ENGINE" == "codex" ]]; then
    if ! command -v codex &> /dev/null; then
      fail "codex CLI nao encontrado. Instale com: npm install -g @openai/codex"
      exit 1
    fi
  elif [[ "$ENGINE" == "claude" ]]; then
    if ! command -v claude &> /dev/null; then
      fail "Claude Code CLI nao encontrado. Instale com: npm install -g @anthropic-ai/claude-code"
      exit 1
    fi
  fi

  if [ ! -f "$INPUT_FILE" ]; then
    fail "Arquivo nao encontrado: $INPUT_FILE"
    exit 1
  fi

  if [ ! -f "artisan" ]; then
    warn "Nao parece ser a raiz de um projeto Laravel (artisan nao encontrado)"
    read -p "Continuar mesmo assim? (y/N) " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]] || exit 1
  fi

  if ! git rev-parse --is-inside-work-tree &> /dev/null 2>&1; then
    fail "Requer um repositorio git."
    exit 1
  fi

  success "Pre-checks OK (engine: $ENGINE)"
}

input_file_hash() {
  sha256sum "$INPUT_FILE" | awk '{print $1}'
}

needs_resplit() {
  if $FRESH; then
    return 0
  fi

  if [ ! -f "$MANIFEST" ]; then
    return 0
  fi

  if [ ! -f "$SOURCE_HASH_FILE" ]; then
    return 0
  fi

  [ "$(input_file_hash)" != "$(cat "$SOURCE_HASH_FILE")" ]
}

filter_progress_to_manifest() {
  if [ ! -f "$PROGRESS_FILE" ]; then
    return
  fi

  local filtered=""
  while IFS= read -r done_file || [ -n "$done_file" ]; do
    [ -z "$done_file" ] && continue
    if grep -qF "${done_file}|" "$MANIFEST"; then
      filtered+="${done_file}"$'\n'
    fi
  done < "$PROGRESS_FILE"

  printf '%s' "$filtered" > "$PROGRESS_FILE"
}

extract_phases_from_input() {
  local current_file=""
  local phase_count=0

  > "$MANIFEST"

  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^##[[:space:]]+(Phase[[:space:]]+[0-9]+[^#]*) ]]; then
      phase_count=$((phase_count + 1))

      local raw_title="${BASH_REMATCH[1]}"
      raw_title="$(echo "$raw_title" | sed 's/[[:space:]]*$//')"

      local slug
      slug=$(echo "$raw_title" \
        | tr '[:upper:]' '[:lower:]' \
        | sed 's/phase[[:space:]]*/phase-/' \
        | sed 's/[^a-z0-9-]/-/g' \
        | sed 's/--*/-/g' \
        | sed 's/-$//' \
        | sed 's/^-//')
      slug=$(echo "$slug" | sed -E 's/phase-([0-9])$/phase-0\1/' | sed -E 's/phase-([0-9])-/phase-0\1-/')

      current_file="$PHASES_DIR/${slug}.md"
      echo "$line" > "$current_file"
      echo "${slug}.md|${raw_title}" >> "$MANIFEST"
      continue
    fi

    if [ -n "$current_file" ]; then
      echo "$line" >> "$current_file"
    fi
  done < "$INPUT_FILE"

  input_file_hash > "$SOURCE_HASH_FILE"
  echo "$phase_count"
}

split_phases() {
  if $FRESH; then
    log "Modo --fresh: apagando progresso e recriando fases..."
    rm -rf "$PHASES_DIR"
  fi

  mkdir -p "$PHASES_DIR" "$LOG_DIR" "$PROMPT_DIR"

  if needs_resplit; then
    if [ -f "$MANIFEST" ] && ! $FRESH; then
      warn "$INPUT_FILE mudou — atualizando arquivos de fase (progresso preservado)"
      rm -f "$PHASES_DIR"/phase-*.md
    else
      log "Quebrando $INPUT_FILE em fases..."
    fi

    local phase_count
    phase_count=$(extract_phases_from_input)
    filter_progress_to_manifest
    success "$phase_count fases extraidas"
  else
    local phase_count
    phase_count=$(wc -l < "$MANIFEST")
    log "Usando fases existentes ($phase_count fases, $INPUT_FILE inalterado)"
  fi
}

completed_phases_prompt_text() {
  if [ ! -f "$MANIFEST" ]; then
    echo "Nenhuma fase anterior foi completada ainda."
    return
  fi

  local completed=()
  while IFS="|" read -r file title; do
    if is_phase_done "$file"; then
      completed+=("$title")
    fi
  done < "$MANIFEST"

  if [ ${#completed[@]} -eq 0 ]; then
    echo "Nenhuma fase anterior foi completada ainda."
  else
    local list
    list=$(IFS=', '; echo "${completed[*]}")
    echo "As seguintes fases ja foram implementadas: ${list}. Se a fase atual for uma delas, apenas finalize a execucao."
  fi
}

show_status() {
  if [ ! -f "$MANIFEST" ]; then
    warn "Nenhuma fase encontrada. Execute ./ralph.sh para gerar as fases."
    exit 0
  fi

  local total_phases
  total_phases=$(wc -l < "$MANIFEST")
  local done_count=0
  local num=0

  echo ""
  log "Progresso das fases ($INPUT_FILE)"
  echo ""

  while IFS="|" read -r file title; do
    num=$((num + 1))
    if is_phase_done "$file"; then
      done_count=$((done_count + 1))
      echo -e "  ${GREEN}[$num/$total_phases] $title — completa${NC}"
    elif [ -f "$CURRENT_FILE" ] && grep -qF "${file}|" "$CURRENT_FILE"; then
      echo -e "  ${YELLOW}[$num/$total_phases] $title — em andamento (interrompida)${NC}"
    else
      echo -e "  ${YELLOW}[$num/$total_phases] $title — pendente${NC}"
    fi
  done < "$MANIFEST"

  echo ""
  log "$done_count de $total_phases fases completadas"

  if [ -f "$CURRENT_FILE" ]; then
    local current_title
    current_title=$(cut -d'|' -f2 "$CURRENT_FILE")
    warn "Ultima fase interrompida: $current_title"
    log "Execute ./ralph.sh para retomar a partir dela"
  elif [ "$done_count" -lt "$total_phases" ]; then
    log "Execute ./ralph.sh para continuar"
  else
    success "Todas as fases foram completadas!"
  fi
  echo ""
}

build_prompt_file() {
  local phase_file="$1"
  local prompt_file="$PROMPT_DIR/${phase_file%.md}.txt"

  {
    echo "=== docs/v1/project-description.md ==="
    cat "docs/v1/project-description.md"
    echo ""
    echo "=== docs/v1/user-stories.md ==="
    cat "docs/v1/user-stories.md"
    echo ""
    echo "=== docs/v1/database-schema.md ==="
    cat "docs/v1/database-schema.md"
    echo ""
    echo "=== docs/v1/project-phases.md ==="
    cat "docs/v1/project-phases.md"
    echo ""
    cat <<'PROMPT'
Inspect the current codebase and identify what is already implemented.

Find the FIRST pending phase in docs/v1/project-phases.md that still contains unchecked tasks ([ ]).

Implement the ENTIRE phase completely, including:
- all sub-phases
- all tasks
- all required dependencies inside that phase

Do not partially implement the phase.

Do not skip ahead to future phases.

Update docs/v1/project-phases.md as tasks are completed by changing:
- [ ] to [x]

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
PROMPT
  } > "$prompt_file"

  echo "$prompt_file"
}

build_retry_prompt_file() {
  local phase_file="$1"
  local test_output="$2"
  local prompt_file="$PROMPT_DIR/${phase_file%.md}-retry.txt"

  {
    printf 'Os testes falharam apos a implementacao anterior. Corrija os erros.\n\nSaida dos testes:\n```\n'
    printf '%s\n' "$test_output"
    printf '```\n\nCorrija o codigo para que todos os testes passem. Rode os testes novamente apos cada correcao.\n'
  } > "$prompt_file"

  echo "$prompt_file"
}

run_engine() {
  local prompt_file="$1"
  local log_file="$2"

  # Exporta contexto da fase atual para os hooks (notify-n8n.sh usa quando .message vem vazio)
  export RALPH_ENGINE="$ENGINE"
  export RALPH_PHASE_TITLE="${RALPH_PHASE_TITLE:-}"
  export RALPH_PHASE_NUM="${RALPH_PHASE_NUM:-}"
  export RALPH_PHASE_TOTAL="${RALPH_PHASE_TOTAL:-}"
  export RALPH_PHASE_ATTEMPT="${RALPH_PHASE_ATTEMPT:-1}"
  export RALPH_PHASE_MAX_ATTEMPTS="$((MAX_RETRIES + 1))"

  if [[ "$ENGINE" == "codex" ]]; then
    cat "$prompt_file" | codex exec --sandbox danger-full-access - 2>&1 | tee "$log_file"
  elif [[ "$ENGINE" == "claude" ]]; then
    cat "$prompt_file" | env -u CLAUDECODE claude --model claude-sonnet-4-6 --dangerously-skip-permissions -p - --output-format text --verbose 2>&1 | tee "$log_file"
  fi
}

run_phase() {
  local phase_file="$1"
  local phase_title="$2"
  local phase_num="$3"
  local total_phases="$4"
  local log_file="$LOG_DIR/${phase_file%.md}.log"
  local phase_start
  phase_start=$(date +%s)

  export RALPH_PHASE_TITLE="$phase_title"
  export RALPH_PHASE_NUM="$phase_num"
  export RALPH_PHASE_TOTAL="$total_phases"

  echo "${phase_file}|${phase_title}|${phase_num}" > "$CURRENT_FILE"

  echo ""
  log "[$phase_num/$total_phases] $phase_title"

  local attempt=0
  local phase_success=false

  while [ $attempt -le $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    export RALPH_PHASE_ATTEMPT="$attempt"

    if [ $attempt -gt 1 ]; then
      warn "Tentativa $attempt/$((MAX_RETRIES + 1))..."
    fi

    local prompt_file
    if [ $attempt -eq 1 ]; then
      prompt_file=$(build_prompt_file "$phase_file")
    fi

    if run_engine "$prompt_file" "$log_file"; then
      phase_success=true
      break
    else
      fail "$ENGINE retornou erro"
      if [ $attempt -le $MAX_RETRIES ]; then
        local test_output
        test_output=$(tail -30 "$log_file" 2>/dev/null || echo "Sem output disponivel")
        prompt_file=$(build_retry_prompt_file "$phase_file" "$test_output")
      fi
    fi
  done

  local phase_end
  phase_end=$(date +%s)
  local phase_duration=$((phase_end - phase_start))

  if $phase_success; then
    success "$phase_title — COMPLETA ($(format_duration $phase_duration))"

    if git rev-parse --is-inside-work-tree &> /dev/null 2>&1; then
      git add -A
      git commit -m "feat: $phase_title" --allow-empty
      log "Commit criado no git"
    fi

    echo "$phase_file" >> "$PROGRESS_FILE"
    rm -f "$CURRENT_FILE"
    return 0
  else
    fail "$phase_title — FALHOU apos $((MAX_RETRIES + 1)) tentativas ($(format_duration $phase_duration))"
    fail "Log disponivel em: $log_file"
    warn "Progresso salvo — execute ./ralph.sh novamente para retomar esta fase"
    return 1
  fi
}

is_phase_done() {
  local phase_file="$1"
  [ -f "$PROGRESS_FILE" ] && grep -qF "$phase_file" "$PROGRESS_FILE"
}

confirm() {
  local prompt="$1"
  if $AUTO_YES; then
    return 0
  fi
  read -p "$prompt" -n 1 -r
  echo
  [[ ! $REPLY =~ ^[Nn]$ ]]
}

main() {
  preflight_checks
  split_phases

  if $SHOW_STATUS; then
    show_status
    exit 0
  fi

  local total_phases
  total_phases=$(wc -l < "$MANIFEST")
  local done_count=0

  echo ""
  log "$total_phases fases no plano"
  echo ""

  local num=0
  while IFS="|" read -r file title; do
    num=$((num + 1))
    if is_phase_done "$file"; then
      done_count=$((done_count + 1))
      echo -e "  ${GREEN}[$num] $title (ja completada)${NC}"
    elif [ -f "$CURRENT_FILE" ] && grep -qF "${file}|" "$CURRENT_FILE"; then
      echo -e "  ${YELLOW}[$num] $title (retomar — interrompida)${NC}"
    else
      echo -e "  ${YELLOW}[$num] $title${NC}"
    fi
  done < "$MANIFEST"

  if [ "$done_count" -gt 0 ]; then
    echo ""
    log "Retomando: $done_count fase(s) ja completada(s) serao puladas"
  fi

  if [ -f "$CURRENT_FILE" ]; then
    local current_title
    current_title=$(cut -d'|' -f2 "$CURRENT_FILE")
    echo ""
    warn "Fase interrompida detectada: $current_title"
  fi

  echo ""
  confirm "Iniciar implementacao? (Y/n) " || exit 0

  local start_time
  start_time=$(date +%s)
  log "Inicio: $(date '+%d/%m/%Y %H:%M:%S')"

  local current=0
  local failed_phases=()
  local skipped_phases=()
  local completed_phases=()

  while IFS="|" read -r file title; do
    current=$((current + 1))

    if is_phase_done "$file"; then
      log "Pulando $title (ja completada)"
      skipped_phases+=("$title")
      continue
    fi

    if run_phase "$file" "$title" "$current" "$total_phases"; then
      completed_phases+=("$title")
    else
      failed_phases+=("$title")
      echo ""
      warn "Fase falhou: $title"
      confirm "Continuar para a proxima fase? (Y/n) " || break
    fi
  done < "$MANIFEST"

  local end_time
  end_time=$(date +%s)
  local total_duration=$((end_time - start_time))

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  log "RELATORIO FINAL (engine: $ENGINE)"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  if [ ${#completed_phases[@]} -gt 0 ]; then
    echo ""
    success "Completadas (${#completed_phases[@]}):"
    for phase in "${completed_phases[@]}"; do
      echo -e "    ${GREEN}$phase${NC}"
    done
  fi

  if [ ${#skipped_phases[@]} -gt 0 ]; then
    echo ""
    log "Puladas (${#skipped_phases[@]}):"
    for phase in "${skipped_phases[@]}"; do
      echo -e "    $phase"
    done
  fi

  if [ ${#failed_phases[@]} -gt 0 ]; then
    echo ""
    fail "Falharam (${#failed_phases[@]}):"
    for phase in "${failed_phases[@]}"; do
      echo -e "    ${RED}$phase${NC}"
    done
    echo ""
    fail "Verifique os logs em $LOG_DIR/"
  fi

  echo ""
  log "Inicio: $(date -d @$start_time '+%d/%m/%Y %H:%M:%S')"
  log "Fim:    $(date -d @$end_time '+%d/%m/%Y %H:%M:%S')"
  log "Duracao total: $(format_duration $total_duration)"
  echo ""
}

main