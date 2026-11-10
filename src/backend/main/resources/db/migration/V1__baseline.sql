-- Baseline: the schema Hibernate generated before migrations were introduced.
-- Every statement is idempotent so it can run on fresh databases and on existing
-- ones created by schema-management=update (those are baselined at version 0).

CREATE EXTENSION IF NOT EXISTS lo;

CREATE TABLE IF NOT EXISTS project (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    ownersubject character varying(255) NOT NULL,
    privaterepository boolean NOT NULL,
    repositoryurl character varying(2048) NOT NULL,
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT uk_project_name UNIQUE (name),
    CONSTRAINT uk_project_repositoryurl UNIQUE (repositoryurl)
);

CREATE SEQUENCE IF NOT EXISTS project_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS build (
    id bigint NOT NULL,
    finishedat timestamp(6) with time zone,
    projectid bigint,
    requestedby character varying(255),
    revision character varying(255),
    startedat timestamp(6) with time zone,
    status character varying(255),
    CONSTRAINT build_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS build_seq START WITH 1 INCREMENT BY 50;

-- Added separately so that existing databases gain the columns.
ALTER TABLE build ADD COLUMN IF NOT EXISTS branch character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS image character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS command character varying(4000);
ALTER TABLE build ADD COLUMN IF NOT EXISTS credentialref character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS imagedigest character varying(300);
ALTER TABLE build ADD COLUMN IF NOT EXISTS exitcode integer;
ALTER TABLE build ADD COLUMN IF NOT EXISTS logtruncated boolean NOT NULL DEFAULT false;
ALTER TABLE build ADD COLUMN IF NOT EXISTS logoid lo;

-- Moves logs from the old output column into large objects.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'build' AND column_name = 'output') THEN
        -- Logs cut at the old 120 KB cap.
        UPDATE build SET logtruncated = true WHERE output LIKE '%[output truncated]' || chr(10);
        UPDATE build SET logoid = lo_from_bytea(0, convert_to(output, 'UTF8'))
            WHERE logoid IS NULL AND output IS NOT NULL AND output <> '';
        ALTER TABLE build DROP COLUMN output;
    END IF;
END $$;

-- Unlinks the large object when its build is deleted.
CREATE OR REPLACE TRIGGER build_log_lo_manage
    BEFORE UPDATE OF logoid OR DELETE ON build
    FOR EACH ROW EXECUTE FUNCTION lo_manage(logoid);

CREATE TABLE IF NOT EXISTS branding (
    id bigint NOT NULL,
    background character varying(350000),
    buttoncolor character varying(7) NOT NULL,
    fontfamily character varying(20),
    headercolor character varying(7) NOT NULL,
    headingcolor character varying(7) NOT NULL,
    logo character varying(350000),
    name character varying(80) NOT NULL,
    CONSTRAINT branding_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS allowedimage (
    name character varying(201) NOT NULL,
    CONSTRAINT allowedimage_pkey PRIMARY KEY (name)
);
