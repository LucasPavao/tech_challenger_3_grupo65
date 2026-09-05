package br.com.tech.challenge.historyservice.dto;

import java.time.format.DateTimeFormatter;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;

/**
 * Uma linha do historico como o GraphQL a expoe.
 *
 * Ids sao String porque o tipo ID! do GraphQL e serializado como string, e as datas sao ISO-8601
 * pelo mesmo motivo -- o schema nao usa scalar customizado.
 */
public record MedicalRecordResponse(
        String id,
        String appointmentId,
        String patientId,
        String patientName,
        String doctorId,
        String doctorName,
        String description,
        String appointmentDate,
        AppointmentEventStatus eventStatus,
        String occurredAt) {

    public static MedicalRecordResponse from(MedicalHistory registro) {
        return new MedicalRecordResponse(
                String.valueOf(registro.getId()),
                String.valueOf(registro.getAppointmentId()),
                String.valueOf(registro.getPatientId()),
                registro.getPatientName(),
                String.valueOf(registro.getDoctorId()),
                registro.getDoctorName(),
                registro.getDescription(),
                registro.getAppointmentDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                registro.getEventStatus(),
                registro.getOccurredAt().toString());
    }
}
