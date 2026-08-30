package br.com.tech.challenge.historyservice.entities;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Linha do log append-only do historico de consultas.
 * Cada AppointmentEvent recebido vira um registro novo; nada e atualizado depois do insert.
 */
@Entity
@Table(name = "medical_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MedicalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private Long appointmentId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "patient_name", updatable = false)
    private String patientName;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private Long doctorId;

    @Column(name = "doctor_name", updatable = false)
    private String doctorName;

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "date_time", nullable = false, updatable = false)
    private LocalDateTime dateTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private AppointmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private EventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    void aoPersistir() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }
}
