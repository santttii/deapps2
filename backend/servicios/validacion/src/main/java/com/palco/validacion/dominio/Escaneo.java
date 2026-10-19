package com.palco.validacion.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un código escaneado en la puerta: qué código, en qué evento, qué resultó,
 * quién lo escaneó y cuándo. Es el registro que permite auditar la puerta. Se
 * escribe una vez y no cambia: no tiene "set".
 *
 * No guarda el nombre ni el DNI del titular: esos datos son de Ventas, y el
 * registro llega a la entrada por {@code entradaId}.
 */
@Entity
@Table(name = "escaneo")
public class Escaneo {

    /** "ESC-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    @Column(name = "codigo_qr", nullable = false)
    private String codigoQr;

    /** El evento que controla la puerta. Vive en ServicioDeEventos: acá es solo un texto. */
    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResultadoEscaneo resultado;

    /** La entrada que encontró Ventas; null si el código no existe o Ventas no respondió. */
    @Column(name = "entrada_id")
    private String entradaId;

    /** El "sub" del token de quien escaneó. */
    @Column(name = "escaneado_por", nullable = false)
    private String escaneadoPor;

    /** En UTC y sin fracción de segundo. */
    @Column(nullable = false)
    private LocalDateTime escaneado;

    protected Escaneo() {
        // JPA
    }

    /** {@code ahora} llega como parámetro (sale de un Clock en UTC): la entidad no mira el reloj del servidor. */
    public Escaneo(String id, String codigoQr, String eventoId, ResultadoEscaneo resultado, String entradaId,
            String escaneadoPor, LocalDateTime ahora) {
        this.id = id;
        this.codigoQr = codigoQr;
        this.eventoId = eventoId;
        this.resultado = resultado;
        this.entradaId = entradaId;
        this.escaneadoPor = escaneadoPor;
        this.escaneado = ahora;
    }

    public String getId() { return id; }
    public String getCodigoQr() { return codigoQr; }
    public String getEventoId() { return eventoId; }
    public ResultadoEscaneo getResultado() { return resultado; }
    public String getEntradaId() { return entradaId; }
    public String getEscaneadoPor() { return escaneadoPor; }
    public LocalDateTime getEscaneado() { return escaneado; }
}
