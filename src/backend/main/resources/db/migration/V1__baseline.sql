-- Baseline: the schema Hibernate generated before migrations were introduced.
-- Every statement is idempotent so it can run on fresh databases and on existing
-- ones created by schema-management=update (those are baselined at version 0).

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
    output character varying(131072),
    projectid bigint,
    requestedby character varying(255),
    revision character varying(255),
    startedat timestamp(6) with time zone,
    status character varying(255),
    CONSTRAINT build_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS build_seq START WITH 1 INCREMENT BY 50;

-- Request details recorded on each build (#13). Added separately so databases created before them gain the columns.
ALTER TABLE build ADD COLUMN IF NOT EXISTS branch character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS image character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS command character varying(4000);
ALTER TABLE build ADD COLUMN IF NOT EXISTS credentialref character varying(255);

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
