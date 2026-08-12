-- Rollback for migration_20260806_remove_redundant_dataset_metadata.sql.
-- Removed historical values are intentionally not reconstructed: the restored columns
-- use the only meaningful legacy defaults (MEDICAL / NULL / 1).
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE IF EXISTS dfetl_dataset
    ADD COLUMN IF NOT EXISTS dataset_type VARCHAR(50) NOT NULL DEFAULT 'MEDICAL',
    ADD COLUMN IF NOT EXISTS dataset_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS sync_revision BIGINT NOT NULL DEFAULT 1;

ALTER TABLE IF EXISTS dfetl_dataset
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_sync_revision_positive;

ALTER TABLE IF EXISTS dfetl_dataset
    ADD CONSTRAINT ck_dfetl_dataset_sync_revision_positive CHECK (sync_revision > 0);

COMMIT;
