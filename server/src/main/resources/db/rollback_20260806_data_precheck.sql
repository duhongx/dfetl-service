-- Spec 104 rollback: remove durable data precheck history.
BEGIN;

SET LOCAL search_path TO df_etl, public;

DROP TABLE IF EXISTS dfetl_precheck_issue;
DROP TABLE IF EXISTS dfetl_precheck_run;

COMMIT;
