#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
FIXTURE_FILE="${FIXTURE_FILE:-src/test/resources/rag/fixtures/reimbursement-policy.md}"
REQUEST_ID="smoke-$(date +%s)"

require_command() {
  local command_name="$1"

  # smoke test 依赖外部命令，提前检查能给出更清晰的失败原因。
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "${command_name}" >&2
    exit 1
  fi
}

json_get() {
  local json_payload="$1"
  local dotted_path="$2"

  # 使用 Python 解析 JSON，避免依赖 jq，提升 macOS 默认环境下的可用性。
  JSON_PAYLOAD="${json_payload}" python3 - "${dotted_path}" <<'PY'
import json
import os
import sys

path = sys.argv[1].split('.')
payload = json.loads(os.environ["JSON_PAYLOAD"])
value = payload

for item in path:
    value = value[item]

if isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
else:
    print(value)
PY
}

# 服务端开启认证时通过 RAG_API_KEY 环境变量传入 Key，未设置时不附加认证头。
api_key_header() {
  if [[ -n "${RAG_API_KEY:-}" ]]; then
    printf 'X-API-Key: %s' "${RAG_API_KEY}"
  else
    # 输出一个无害的占位头，保持 curl -H 参数结构稳定。
    printf 'X-Smoke-Test: 1'
  fi
}

post_json() {
  local path="$1"
  local payload="$2"

  # 所有请求携带 X-Request-Id，方便服务端日志关联本次 smoke test。
  curl -sS \
    -H "Content-Type: application/json" \
    -H "X-Request-Id: ${REQUEST_ID}" \
    -H "$(api_key_header)" \
    -X POST \
    -d "${payload}" \
    "${BASE_URL}${path}"
}

get_json() {
  local path="$1"

  # GET 请求同样携带 X-Request-Id，保证健康检查和查询日志可追踪。
  curl -sS \
    -H "X-Request-Id: ${REQUEST_ID}" \
    -H "$(api_key_header)" \
    "${BASE_URL}${path}"
}

main() {
  require_command curl
  require_command python3

  # fixture 是 smoke test 的知识来源，缺失时继续执行没有意义。
  if [[ ! -f "${FIXTURE_FILE}" ]]; then
    printf 'Fixture file not found: %s\n' "${FIXTURE_FILE}" >&2
    exit 1
  fi

  printf 'Checking application health at %s\n' "${BASE_URL}"
  get_json "/actuator/health" >/dev/null

  local kb_name="smoke-knowledge-${REQUEST_ID}"
  printf 'Creating knowledge base: %s\n' "${kb_name}"
  local create_kb_response
  create_kb_response=$(post_json "/api/v1/knowledge-bases" "{\"name\":\"${kb_name}\",\"description\":\"Local smoke test knowledge base\"}")
  local knowledge_base_id
  knowledge_base_id=$(json_get "${create_kb_response}" "data.id")

  printf 'Uploading fixture document into knowledge base %s\n' "${knowledge_base_id}"
  local upload_response
  upload_response=$(curl -sS \
    -H "X-Request-Id: ${REQUEST_ID}" \
    -H "$(api_key_header)" \
    -F "file=@${FIXTURE_FILE};type=text/markdown" \
    "${BASE_URL}/api/v1/documents/upload?knowledgeBaseId=${knowledge_base_id}")
  local document_id
  document_id=$(json_get "${upload_response}" "data.id")

  printf 'Ingesting document %s\n' "${document_id}"
  local ingest_response
  ingest_response=$(post_json "/api/v1/documents/${document_id}/ingest" "{}")
  local document_status
  document_status=$(json_get "${ingest_response}" "data.status")

  # 入库是异步任务，触发后需要轮询文档详情等待 COMPLETED/FAILED 终态。
  local detail_response="${ingest_response}"
  local attempt=0
  local max_attempts="${INGESTION_POLL_MAX_ATTEMPTS:-100}"
  while [[ "${document_status}" != "COMPLETED" && "${document_status}" != "FAILED" && ${attempt} -lt ${max_attempts} ]]; do
    sleep "${INGESTION_POLL_INTERVAL:-3}"
    detail_response=$(get_json "/api/v1/documents/${document_id}")
    document_status=$(json_get "${detail_response}" "data.status")
    attempt=$((attempt + 1))
  done

  # 入库失败或超时都输出完整响应，便于排查 Ollama、pgvector 或文档解析问题。
  if [[ "${document_status}" != "COMPLETED" ]]; then
    printf 'Document ingestion did not complete (status=%s). Response:\n%s\n' "${document_status}" "${detail_response}" >&2
    exit 1
  fi

  printf 'Running RAG chat\n'
  local chat_response
  chat_response=$(post_json "/api/v1/rag/chat" "{\"knowledgeBaseId\":${knowledge_base_id},\"question\":\"员工需要在多久内提交报销？\",\"topK\":3,\"similarityThreshold\":0.2}")
  local answer
  answer=$(json_get "${chat_response}" "data.answer")

  printf 'Smoke test answer:\n%s\n' "${answer}"
  printf 'Smoke test completed. requestId=%s knowledgeBaseId=%s documentId=%s\n' "${REQUEST_ID}" "${knowledge_base_id}" "${document_id}"
}

main "$@"
