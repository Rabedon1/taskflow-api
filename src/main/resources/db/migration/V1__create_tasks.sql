-- Tabla principal de TaskFlow. Flyway aplica este script una sola vez y lo registra
-- en flyway_schema_history; los cambios futuros van en V2__..., V3__..., nunca editando este archivo.
CREATE TABLE tasks (
    id          UUID          PRIMARY KEY,
    title       VARCHAR(120)  NOT NULL,
    description VARCHAR(1000),
    status      VARCHAR(20)   NOT NULL,
    due_date    DATE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE'))
);

CREATE INDEX idx_tasks_status ON tasks (status);
