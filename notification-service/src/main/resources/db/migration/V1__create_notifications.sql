CREATE TABLE notifications (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL,
    event_status   VARCHAR(20)  NOT NULL,
    appointment_id BIGINT       NOT NULL,
    patient_id     BIGINT       NOT NULL,
    message        VARCHAR(255) NOT NULL,
    status         VARCHAR(10)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_notifications_event_id UNIQUE (event_id)
);

CREATE INDEX idx_notifications_patient ON notifications (patient_id, created_at DESC);
