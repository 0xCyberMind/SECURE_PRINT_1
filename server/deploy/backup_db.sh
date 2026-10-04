#!/bin/bash
# PrivPrint Automated Production PostgreSQL Backup Script
set -e

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_DIR="/var/backups/privprint"
DB_CONTAINER="privprint-db-prod"
DB_NAME="privprint_prod_db"
DB_USER="privprint_prod_user"
BACKUP_FILE="${BACKUP_DIR}/privprint_backup_${TIMESTAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"

echo "[$(date -u)] Starting PrivPrint PostgreSQL Database Backup..."
docker exec -t "${DB_CONTAINER}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" | gzip > "${BACKUP_FILE}"

# Retention: Keep last 30 daily backups
find "${BACKUP_DIR}" -name "privprint_backup_*.sql.gz" -mtime +30 -delete

echo "[$(date -u)] Database backup completed successfully: ${BACKUP_FILE}"
