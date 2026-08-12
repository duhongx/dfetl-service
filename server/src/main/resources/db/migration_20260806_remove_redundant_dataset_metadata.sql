-- Spec 101 follow-up: remove dataset metadata that has no runtime semantics.
-- Apply this migration while dfetl is stopped, immediately before installing the new JAR.
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE IF EXISTS dfetl_dataset
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_sync_revision_positive;

ALTER TABLE IF EXISTS dfetl_dataset
    DROP COLUMN IF EXISTS dataset_type,
    DROP COLUMN IF EXISTS dataset_version,
    DROP COLUMN IF EXISTS sync_revision;

COMMIT;
