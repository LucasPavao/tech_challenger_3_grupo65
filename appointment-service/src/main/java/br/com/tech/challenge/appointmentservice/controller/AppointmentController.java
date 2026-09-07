package br.com.tech.challenge.appointmentservice.controller;

import br.com.tech.challenge.appointmentservice.dto.AppointmentRequest;
import br.com.tech.challenge.appointmentservice.dto.AppointmentResponse;
import br.com.tech.challenge.appointmentservice.dto.AppointmentStatusRequest;
import br.com.tech.challenge.appointmentservice.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse create(@RequestBody @Valid AppointmentRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public AppointmentResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<AppointmentResponse> findAll() {
        return service.findAll();
    }

    @GetMapping("/patient/{patientId}")
    public List<AppointmentResponse> findByPatient(@PathVariable Long patientId) {
        return service.findByPatient(patientId);
    }

    @PutMapping("/{id}")
    public AppointmentResponse update(
            @PathVariable Long id,
            @RequestBody @Valid AppointmentRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public AppointmentResponse updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid AppointmentStatusRequest request) {
        return service.updateStatus(id, request);
    }
}
