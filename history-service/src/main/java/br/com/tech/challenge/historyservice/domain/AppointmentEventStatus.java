package br.com.tech.challenge.historyservice.domain;

/**
 * Transicao sofrida pela consulta no appointment-service. E o ponto de verdade sobre o que
 * aconteceu: cada evento declara a alteracao, em vez de deixar o historico deduzi-la.
 */
public enum AppointmentEventStatus {

    /** Consulta agendada. Primeiro evento do ciclo de vida. */
    SCHEDULED,

    /** Data ou hora da consulta alterada. A data anterior fica na linha anterior da trilha. */
    RESCHEDULED,

    /** Consulta cancelada. */
    CANCELLED,

    /** Atendimento realizado. */
    COMPLETED
}
