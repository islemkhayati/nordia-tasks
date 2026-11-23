-- Nordia Tasks - schema and sample data for the Oracle APEX / PL/SQL track.
-- Fictional data. Oracle Database 19c or later (12.2+ should work), Oracle APEX 22.x or later.
-- Run it in the schema that your APEX workspace uses (SQL Workshop > SQL Scripts > Run).
-- The script can be run again: it drops its own objects first.
-- ASCII only on purpose, so that it runs on any database character set.

BEGIN
  FOR t IN (SELECT table_name
              FROM user_tables
             WHERE table_name IN ('NT_PROCESSED_OP', 'NT_INCOMING_CHANGE', 'NT_TASK', 'NT_MEMBER')) LOOP
    EXECUTE IMMEDIATE 'DROP TABLE ' || t.table_name || ' CASCADE CONSTRAINTS PURGE';
  END LOOP;
END;
/

-- ---------------------------------------------------------------------------
-- Team members
-- ---------------------------------------------------------------------------
CREATE TABLE nt_member (
  member_id  VARCHAR2(10)  NOT NULL,
  full_name  VARCHAR2(80)  NOT NULL,
  CONSTRAINT nt_member_pk PRIMARY KEY (member_id)
);

-- ---------------------------------------------------------------------------
-- Tasks as the server knows them (the "current" version of each task).
-- version: managed by the server, 1 at creation, +1 for each accepted change (a deletion is a change).
-- deleted: 'Y' = tombstone, the row is kept so that phones learn about the deletion.
-- due_date: a calendar date (no time, no time zone). updated_at: server time.
-- title and note: lengths are counted in characters (CHAR), as in the rules of the brief.
-- ---------------------------------------------------------------------------
CREATE TABLE nt_task (
  task_id      VARCHAR2(36)   NOT NULL,
  title        VARCHAR2(120 CHAR) NOT NULL,
  note         VARCHAR2(500 CHAR),
  status       VARCHAR2(10)   DEFAULT 'todo' NOT NULL,
  assignee_id  VARCHAR2(10),
  due_date     DATE,
  version      NUMBER(10)     DEFAULT 1 NOT NULL,
  deleted      CHAR(1)        DEFAULT 'N' NOT NULL,
  updated_at   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT nt_task_pk          PRIMARY KEY (task_id),
  CONSTRAINT nt_task_status_ck   CHECK (status IN ('todo', 'doing', 'done')),
  CONSTRAINT nt_task_deleted_ck  CHECK (deleted IN ('Y', 'N')),
  CONSTRAINT nt_task_version_ck  CHECK (version >= 1),
  CONSTRAINT nt_task_assignee_fk FOREIGN KEY (assignee_id) REFERENCES nt_member (member_id)
);
CREATE INDEX nt_task_assignee_ix ON nt_task (assignee_id);

-- ---------------------------------------------------------------------------
-- Waiting room: the changes sent by the phones, stored exactly as received.
-- A service outside the database fills this table. Nobody has processed these rows yet.
--   change_id     order of arrival at the server (1, 2, 3...).
--   op_id         the Idempotency-Key of the operation: one logical operation = one op_id.
--   device_id     which phone sent it.
--   op_type       CREATE, UPDATE or DELETE.
--   base_version  the version of the task the user started from (NULL for CREATE).
--   payload       JSON with ONLY the fields the user changed, for example {"status":"doing"}.
--                 A field set to JSON null ("note":null) means "clear this field"; a field that is not there was not touched.
--                 Field names: title, note, status, assigneeId, dueDate (YYYY-MM-DD).
--   sent_at       the phone's own clock, with the phone's own time zone. Phones are not always right.
--   state         PENDING until your code has dealt with the row; then APPLIED, DUPLICATE, CONFLICT, REJECTED
--                 or RESOLVED (see the brief).
--   result_message  a short explanation that a dispatcher can read.
-- ---------------------------------------------------------------------------
CREATE TABLE nt_incoming_change (
  change_id       NUMBER GENERATED ALWAYS AS IDENTITY,
  op_id           VARCHAR2(40)   NOT NULL,
  device_id       VARCHAR2(20)   NOT NULL,
  task_id         VARCHAR2(36)   NOT NULL,
  op_type         VARCHAR2(10)   NOT NULL,
  base_version    NUMBER(10),
  payload         CLOB,
  sent_at         TIMESTAMP(0) WITH TIME ZONE NOT NULL,
  state           VARCHAR2(10)   DEFAULT 'PENDING' NOT NULL,
  result_message  VARCHAR2(400),
  processed_at    TIMESTAMP(3) WITH TIME ZONE,
  CONSTRAINT nt_incoming_change_pk PRIMARY KEY (change_id),
  CONSTRAINT nt_inc_op_type_ck     CHECK (op_type IN ('CREATE', 'UPDATE', 'DELETE')),
  CONSTRAINT nt_inc_state_ck       CHECK (state IN ('PENDING', 'APPLIED', 'DUPLICATE', 'CONFLICT', 'REJECTED', 'RESOLVED')),
  CONSTRAINT nt_inc_payload_json_ck CHECK (payload IS JSON)
);
CREATE INDEX nt_inc_state_ix ON nt_incoming_change (state, change_id);
CREATE INDEX nt_inc_op_ix    ON nt_incoming_change (op_id);

-- ---------------------------------------------------------------------------
-- Operations already dealt with, to recognise a duplicate. Empty at the start.
-- How you use it (which outcomes you record, when) is up to you.
-- ---------------------------------------------------------------------------
CREATE TABLE nt_processed_op (
  op_id         VARCHAR2(40)  NOT NULL,
  change_id     NUMBER        NOT NULL,
  task_id       VARCHAR2(36)  NOT NULL,
  outcome       VARCHAR2(10)  NOT NULL,
  task_version  NUMBER(10),
  processed_at  TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT nt_processed_op_pk        PRIMARY KEY (op_id),
  CONSTRAINT nt_processed_op_change_fk FOREIGN KEY (change_id) REFERENCES nt_incoming_change (change_id)
);

-- ---------------------------------------------------------------------------
-- Sample data
-- ---------------------------------------------------------------------------
INSERT INTO nt_member (member_id, full_name) VALUES ('m-01', 'Lina B.');
INSERT INTO nt_member (member_id, full_name) VALUES ('m-02', 'Karim D.');
INSERT INTO nt_member (member_id, full_name) VALUES ('m-03', 'Sofia M.');
INSERT INTO nt_member (member_id, full_name) VALUES ('m-04', 'Omar T.');
INSERT INTO nt_member (member_id, full_name) VALUES ('m-05', 'Nadia H.');
INSERT INTO nt_member (member_id, full_name) VALUES ('m-06', 'Yanis P.');

INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000001', 'Remplacer le filtre de la CTA du batiment B', 'Filtre G4, reference en stock',
   'todo', 'm-01', DATE '2026-11-12', 1, 'N', TIMESTAMP '2026-11-03 09:15:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000002', 'Verifier les extincteurs du parking P2', NULL,
   'doing', 'm-02', DATE '2026-11-10', 3, 'N', TIMESTAMP '2026-11-04 14:02:31.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000003', 'Releve des compteurs eau', 'Local technique, niveau -1',
   'done', 'm-03', DATE '2026-11-02', 4, 'N', TIMESTAMP '2026-11-02 16:40:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000004', 'Peindre le marquage de la zone de livraison', NULL,
   'todo', NULL, DATE '2026-11-20', 2, 'N', TIMESTAMP '2026-11-01 08:00:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000005', 'Controler eclairage de secours escalier C', 'Deux blocs a changer',
   'doing', 'm-04', DATE '2026-11-09', 1, 'N', TIMESTAMP '2026-11-04 10:30:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000006', 'Depanner le portail du quai 3', 'Doublon de la tache sur les extincteurs',
   'todo', 'm-05', NULL, 3, 'Y', TIMESTAMP '2026-11-04 15:45:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000007', 'Nettoyer les gaines de ventilation', NULL,
   'todo', 'm-05', DATE '2026-11-25', 1, 'N', TIMESTAMP '2026-10-30 07:20:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000008', 'Mettre a jour le plan evacuation etage 2', NULL,
   'done', 'm-01', DATE '2026-10-30', 2, 'N', TIMESTAMP '2026-10-29 17:05:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000009', 'Purger les radiateurs du hall', NULL,
   'doing', 'm-06', DATE '2026-11-06', 5, 'N', TIMESTAMP '2026-11-05 07:55:00.000 +00:00');
INSERT INTO nt_task (task_id, title, note, status, assignee_id, due_date, version, deleted, updated_at) VALUES
  ('1a2b3c4d-0000-4000-8000-000000000010', 'Inventaire du local outillage', NULL,
   'todo', 'm-02', NULL, 1, 'N', TIMESTAMP '2026-11-02 11:11:00.000 +00:00');

-- Changes received from the phones, in order of arrival (change_id 1 to 15).
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0001', 'phone-01', '1a2b3c4d-0000-4000-8000-000000000001', 'UPDATE', 1,
   '{"status":"doing"}', TIMESTAMP '2026-11-05 08:01:12 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0001', 'phone-01', '1a2b3c4d-0000-4000-8000-000000000001', 'UPDATE', 1,
   '{"status":"doing"}', TIMESTAMP '2026-11-05 08:01:12 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0002', 'phone-02', '1a2b3c4d-0000-4000-8000-000000000011', 'CREATE', NULL,
   '{"title":"Changer l ampoule du couloir A","assigneeId":"m-03","dueDate":"2026-11-08"}', TIMESTAMP '2026-11-05 08:03:40 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0003', 'phone-02', '1a2b3c4d-0000-4000-8000-000000000002', 'UPDATE', 2,
   '{"title":"Verifier extincteurs et BAES du parking P2"}', TIMESTAMP '2026-11-05 07:58:00 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0004', 'phone-03', '1a2b3c4d-0000-4000-8000-000000000003', 'UPDATE', 4,
   '{"status":"todo"}', TIMESTAMP '2026-11-05 02:10:00 -05:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0005', 'phone-01', '1a2b3c4d-0000-4000-8000-000000000006', 'UPDATE', 3,
   '{"note":"A traiter avec la tache sur les extincteurs"}', TIMESTAMP '2026-11-05 08:20:05 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0006', 'phone-03', '1a2b3c4d-0000-4000-8000-000000000004', 'UPDATE', 2,
   '{"assigneeId":"m-99"}', TIMESTAMP '2026-11-05 08:21:30 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0007', 'phone-02', '1a2b3c4d-0000-4000-8000-000000000005', 'UPDATE', 1,
   '{"status":"done","note":null}', TIMESTAMP '2026-11-05 08:25:00 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0008', 'phone-03', '1a2b3c4d-0000-4000-8000-000000000007', 'UPDATE', 1,
   '{"title":""}', TIMESTAMP '2026-11-05 08:26:10 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0009', 'phone-01', '1a2b3c4d-0000-4000-8000-000000000008', 'DELETE', 2,
   NULL, TIMESTAMP '2026-11-05 08:30:00 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0010', 'phone-03', '1a2b3c4d-0000-4000-8000-000000000009', 'UPDATE', 4,
   '{"status":"todo"}', TIMESTAMP '2026-11-05 08:31:45 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0011', 'phone-02', '1a2b3c4d-0000-4000-8000-000000000010', 'UPDATE', 1,
   '{"title":"Inventaire du local outillage - etage 1"}', TIMESTAMP '2026-11-05 08:40:00 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0012', 'phone-02', '1a2b3c4d-0000-4000-8000-000000000010', 'UPDATE', 1,
   '{"note":"Verifier la cle du cadenas"}', TIMESTAMP '2026-11-05 08:41:20 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0013', 'phone-01', 'ffffffff-ffff-4fff-8fff-ffffffffffff', 'UPDATE', 1,
   '{"title":"Tache fantome"}', TIMESTAMP '2026-11-05 08:45:00 +01:00');
INSERT INTO nt_incoming_change (op_id, device_id, task_id, op_type, base_version, payload, sent_at) VALUES
  ('op-0014', 'phone-03', '1a2b3c4d-0000-4000-8000-000000000007', 'UPDATE', 1,
   '{"status":"finished"}', TIMESTAMP '2026-11-05 08:46:30 +01:00');

COMMIT;
