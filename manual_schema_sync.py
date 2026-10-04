#!/usr/bin/env python3
"""
Direct SQL migration script to sync ORM schema with database.
This is useful when Alembic docker container is not available.
Run after restarting Docker:
  python server/manual_schema_sync.py
"""

import asyncio
import sys
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy import text
import os

# Add server to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "server"))

async def sync_schema():
    """Apply pending schema changes directly via SQL."""
    db_url = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://privprint_dev:privprint_dev_pass@localhost:5432/privprint_dev_db"
    )
    
    engine = create_async_engine(db_url, echo=False)
    
    # SQL statements for missing columns/tables
    migrations = [
        # devices table additions
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS os_info VARCHAR(255) DEFAULT 'Windows 11 Pro' NOT NULL;""",
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS app_version VARCHAR(64) DEFAULT '1.0.0' NOT NULL;""",
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS auth_state VARCHAR(32) DEFAULT 'AUTHENTICATED' NOT NULL;""",
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(255);""",
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'ONLINE' NOT NULL;""",
        """ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP WITH TIME ZONE;""",
        
        # printers table additions
        """ALTER TABLE printers ADD COLUMN IF NOT EXISTS driver_name VARCHAR(255);""",
        """ALTER TABLE printers ADD COLUMN IF NOT EXISTS connection_info VARCHAR(255);""",
        """ALTER TABLE printers ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'READY' NOT NULL;""",
        """ALTER TABLE printers ADD COLUMN IF NOT EXISTS supported_paper_sizes VARCHAR(255);""",
        
        # print_jobs table additions
        """ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);""",
        """CREATE INDEX IF NOT EXISTS ix_print_jobs_idempotency_key ON print_jobs (idempotency_key);""",
        
        # sessions table additions
        """ALTER TABLE sessions ADD COLUMN IF NOT EXISTS nonce VARCHAR(64) DEFAULT '' NOT NULL;""",
        
        # shops table additions
        """ALTER TABLE shops ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'ACTIVE' NOT NULL;""",
        """CREATE INDEX IF NOT EXISTS ix_shops_status ON shops (status);""",
        
        # pending_uploads table creation
        """CREATE TABLE IF NOT EXISTS pending_uploads (
            id VARCHAR(64) PRIMARY KEY,
            document_id VARCHAR(64) NOT NULL,
            user_id VARCHAR(64) NOT NULL,
            session_id VARCHAR(64) NOT NULL,
            filename VARCHAR(255) NOT NULL,
            storage_path VARCHAR(512) NOT NULL,
            file_size_bytes BIGINT NOT NULL,
            mime_type VARCHAR(128) NOT NULL,
            sha256_hash VARCHAR(64) NOT NULL,
            iv_hex VARCHAR(64) NOT NULL,
            key_fingerprint VARCHAR(128) NOT NULL,
            copies_authorized INTEGER NOT NULL DEFAULT 1,
            expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
            status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );""",
        """CREATE INDEX IF NOT EXISTS ix_pending_uploads_session_id ON pending_uploads (session_id);""",
        """CREATE INDEX IF NOT EXISTS ix_pending_uploads_user_id ON pending_uploads (user_id);""",
    ]
    
    async with engine.connect() as conn:
        for sql_stmt in migrations:
            try:
                await conn.execute(text(sql_stmt))
                await conn.commit()
                print(f"✓ {sql_stmt[:60]}...")
            except Exception as e:
                print(f"⚠ {sql_stmt[:60]}... : {str(e)[:80]}")
    
    await engine.dispose()
    print("\n✓ Schema sync completed.")

if __name__ == "__main__":
    asyncio.run(sync_schema())
