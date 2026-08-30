CREATE TABLE medical_history (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL,
    appointment_id BIGINT       NOT NULL,
    patient_id     BIGINT       NOT NULL,
    patient_name   VARCHAR(255),
    doctor_id      BIGINT       NOT NULL,
    doctor_name    VARCHAR(255),
    description    TEXT,
    date_time      TIMESTAMP    NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    event_type     VARCHAR(20)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_medical_history_event_id UNIQUE (event_id)
);

CREATE INDEX idx_medical_history_patient ON medical_history (patient_id, occurred_at DESC);
CREATE INDEX idx_medical_history_appointment ON medical_history (appointment_id, occurred_at ASC);
