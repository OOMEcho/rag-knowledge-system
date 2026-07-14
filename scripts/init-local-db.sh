#!/usr/bin/env bash
#
# 在已有的 PostgreSQL 容器（默认 OrbStack/Docker 中的 postgres17）里初始化本项目所需的
# 数据库、用户和扩展。脚本幂等，可重复执行。
#
# 用法:
#   bash scripts/init-local-db.sh
#   PG_CONTAINER=my-postgres RAG_DB_PASSWORD=secret bash scripts/init-local-db.sh
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-postgres17}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
RAG_DB_NAME="${RAG_DB_NAME:-rag_knowledge}"
RAG_DB_USER="${RAG_DB_USER:-rag_user}"
RAG_DB_PASSWORD="${RAG_DB_PASSWORD:-rag_password}"

psql_super() {
  docker exec -i "${PG_CONTAINER}" psql -U "${PG_SUPERUSER}" -v ON_ERROR_STOP=1 "$@"
}

echo "==> 检查容器 ${PG_CONTAINER} 是否可用"
docker exec "${PG_CONTAINER}" pg_isready -U "${PG_SUPERUSER}" >/dev/null

echo "==> 创建用户 ${RAG_DB_USER}（已存在则跳过）"
if [ "$(psql_super -tAc "SELECT 1 FROM pg_roles WHERE rolname = '${RAG_DB_USER}'")" != "1" ]; then
  psql_super -c "CREATE USER ${RAG_DB_USER} WITH PASSWORD '${RAG_DB_PASSWORD}'"
else
  echo "    用户已存在，跳过"
fi

echo "==> 创建数据库 ${RAG_DB_NAME}（已存在则跳过）"
if [ "$(psql_super -tAc "SELECT 1 FROM pg_database WHERE datname = '${RAG_DB_NAME}'")" != "1" ]; then
  psql_super -c "CREATE DATABASE ${RAG_DB_NAME} OWNER ${RAG_DB_USER}"
else
  echo "    数据库已存在，跳过"
fi

# 扩展必须由超级用户在目标库中预先创建；应用 Flyway 迁移中的
# CREATE EXTENSION IF NOT EXISTS 在扩展已存在时无需超级用户权限即可通过。
echo "==> 在 ${RAG_DB_NAME} 中启用扩展 vector / hstore / uuid-ossp / pg_trgm"
psql_super -d "${RAG_DB_NAME}" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL

echo "==> 完成。应用可使用如下连接信息启动："
echo "    POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/${RAG_DB_NAME}"
echo "    POSTGRES_USER=${RAG_DB_USER}"
echo "    POSTGRES_PASSWORD=${RAG_DB_PASSWORD}"
