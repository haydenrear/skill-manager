-- Provision the two databases skill-manager expects:
--   skill_registry       — primary, used by local dev runs
--   skill_registry_test  — isolated target the test_graph points at via
--                          SKILL_REGISTRY_DB_URL so test runs can TRUNCATE
--                          freely without nuking dev data.
CREATE DATABASE skill_registry_test;

\connect skill_registry
CREATE EXTENSION IF NOT EXISTS vector;

\connect skill_registry_test
CREATE EXTENSION IF NOT EXISTS vector;
