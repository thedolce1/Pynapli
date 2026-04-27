CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS schema_marker (
    id INTEGER PRIMARY KEY,
    service_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schema_marker (id, service_name)
VALUES (1, 'pynapli-api')
ON CONFLICT (id) DO NOTHING;

