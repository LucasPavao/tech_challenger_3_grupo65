package br.com.tech.challenge.notificationservice.dto;

/**
 * Status de transição de uma consulta no appointment-service.
 * 
 * Cada evento declara a alteração específica que ocorreu, permitindo
 * que o notification-service customize a mensagem apropriadamente.
 */
public enum AppointmentEventStatus {
    /** Consulta agendada. Primeiro evento do ciclo de vida. */
    SCHEDULED,

    /** Data ou hora da consulta alterada. */
    RESCHEDULED,

    /** Consulta cancelada. */
    CANCELLED,

    /** Atendimento realizado. */
    COMPLETED
}
