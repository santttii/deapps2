package com.palco.validacion.dominio;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Una cuenta de puerta asignada a un evento (limitación L17). Solo una cuenta
 * asignada escanea y ve el registro de ese evento. La clave es el par evento
 * y email: asignar dos veces la misma cuenta no agrega nada.
 *
 * Desde el código solo se crea y se borra; no tiene nada que cambiar.
 */
@Entity
@Table(name = "puerta_asignada")
@IdClass(PuertaAsignada.Clave.class)
public class PuertaAsignada {

    /** La clave compuesta, como la pide JPA: con {@code equals} y {@code hashCode}. */
    public static class Clave implements Serializable {
        private String eventoId;
        private String staffEmail;

        public Clave() {
            // JPA
        }

        public Clave(String eventoId, String staffEmail) {
            this.eventoId = eventoId;
            this.staffEmail = staffEmail;
        }

        @Override
        public boolean equals(Object otro) {
            return otro instanceof Clave c && Objects.equals(eventoId, c.eventoId)
                    && Objects.equals(staffEmail, c.staffEmail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventoId, staffEmail);
        }
    }

    @Id
    @Column(name = "evento_id")
    private String eventoId;

    /** En minúsculas. */
    @Id
    @Column(name = "staff_email")
    private String staffEmail;

    @Column(name = "asignada_por", nullable = false)
    private String asignadaPor;

    @Column(nullable = false)
    private LocalDateTime asignada;

    protected PuertaAsignada() {
        // JPA
    }

    /** {@code ahora} va en UTC. */
    public PuertaAsignada(String eventoId, String staffEmail, String asignadaPor, LocalDateTime ahora) {
        this.eventoId = eventoId;
        this.staffEmail = staffEmail;
        this.asignadaPor = asignadaPor;
        this.asignada = ahora;
    }

    public String getEventoId() { return eventoId; }
    public String getStaffEmail() { return staffEmail; }
    public String getAsignadaPor() { return asignadaPor; }
    public LocalDateTime getAsignada() { return asignada; }
}
