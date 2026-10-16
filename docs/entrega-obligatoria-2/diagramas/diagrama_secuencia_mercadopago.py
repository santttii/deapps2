"""Diagrama de secuencia: cobro con Mercado Pago y webhook (diseñado, pendiente).

Mismo estilo que actividad_integradora_clase10/diagrama_secuencia.png:
rojo = sincrónico, naranja = alternativa ante falla, gris = webhook, punteado = respuesta.
Fuente: el diseño de la integración con Mercado Pago (diseñado, pendiente).

Uso: python3 diagrama_secuencia_mercadopago.py  (genera .png y .svg al lado)
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Rectangle

ROJO, NARANJA, GRIS, TINTA = "#c0392b", "#e67e22", "#424242", "#1a1a1a"
CAJA, BORDE = "#e8eefc", "#2c3e50"

PART = [
    ("ServicioDeVentas", "(cliente interno)"),
    ("ServicioDePagos", "(IPagos)"),
    ("Base de Pagos", "(tabla pagos.pago)"),
    ("MercadoPagoPagos", "(Adapter de PasarelaDePagos)"),
    ("Mercado Pago", "(REST externo)"),
]
X = [1.2, 4.2, 7.2, 10.2, 13.2]
V, P, B, A, M = range(5)

fig, ax = plt.subplots(figsize=(15.5, 15.2))
ax.set_xlim(0, 14.4)
ax.set_ylim(0, 31.6)
ax.axis("off")

# ── participantes y líneas de vida
for i, (n, s) in enumerate(PART):
    ax.add_patch(FancyBboxPatch((X[i] - 1.15, 29.9), 2.3, 1.15, boxstyle="round,pad=0.02",
                                fc=CAJA, ec=BORDE, lw=1.2))
    ax.text(X[i], 30.62, n, ha="center", va="center", fontsize=11.5, fontweight="bold", color=TINTA)
    ax.text(X[i], 30.2, s, ha="center", va="center", fontsize=9.5, color=TINTA)
    ax.plot([X[i], X[i]], [1.4, 29.9], ls=":", color="#9e9e9e", lw=1)


def flecha(a, b, y, texto, color=ROJO, resp=False, dy=0.27, fs=10):
    ax.annotate("", xy=(X[b] + (0.06 if b < a else -0.06), y), xytext=(X[a], y),
                arrowprops=dict(arrowstyle="->", color=color, lw=2,
                                linestyle=(0, (4, 3)) if resp else "-"))
    ax.text((X[a] + X[b]) / 2, y + dy, texto, ha="center", va="bottom", fontsize=fs, color=color)


def caja(x0, x1, y0, y1, color, fondo, titulo):
    ax.add_patch(Rectangle((x0, y0), x1 - x0, y1 - y0, fc=fondo, ec=color, lw=1.3))
    ax.text(x0 + 0.15, y1 - 0.15, titulo, ha="left", va="top", fontsize=10.5, fontweight="bold", color=color)


def nota(x, y, texto, color=TINTA, ha="center", fs=9.8, estilo="italic"):
    ax.text(x, y, texto, ha=ha, va="center", fontsize=fs, color=color, style=estilo)


# ── A · cobro
caja(0.35, 14.05, 15.95, 29.5, ROJO, "#fdf2f1", "A · COBRO · SÍNCRONO · REST")
flecha(V, P, 28.3, "1. POST /api/v1/pagos   (rol servicio · Idempotency-Key)")
flecha(P, B, 27.0, "2. buscar pago por clave de idempotencia")
flecha(B, P, 25.9, "no existe  (si existe: se devuelve ese pago y no se cobra)", resp=True)
flecha(P, A, 24.6, "3. cobrar(...)  vía PasarelaDePagos", dy=0.27)
flecha(A, M, 23.3, "4. POST /v1/payments   (X-Idempotency-Key)")
nota((X[A] + X[M]) / 2, 22.9, "espera máxima: 2 s para conectar · 5 s para leer", ROJO, fs=9)
flecha(M, A, 21.8, "approved · rejected · in_process", resp=True)
flecha(A, P, 20.6, "ResultadoPasarela: APROBADO · RECHAZADO · PENDIENTE", resp=True)
nota((X[P] + X[A]) / 2, 20.2, "la traducción de estados es una función pura", ROJO, fs=9)
flecha(P, B, 19.1, "5. guardar pago (idExterno, estado) · transacción corta")
flecha(P, V, 17.9, "6. 201 {pagoId, estado}", resp=True)
nota((X[V] + X[P]) / 2, 17.45, "un RECHAZADO también es 201: el 402 se lo da Ventas al comprador", ROJO, fs=9)
nota(7.2, 16.45, "Ningún paso del cobro corre dentro de una transacción abierta: ServicioDePagos lleva NOT_SUPPORTED.",
     ROJO, fs=9.3)

# ── alternativa: sin respuesta
caja(0.35, 14.05, 11.85, 15.55, NARANJA, "#fef5ea", "alt  Mercado Pago no responde a tiempo o no devuelve 2xx")
flecha(A, P, 14.3, "SinRespuesta", NARANJA, resp=True)
flecha(P, V, 13.15, "504 sin-respuesta   · no se guarda nada", NARANJA, resp=True)
nota(7.2, 12.35, "Ventas reintenta con la misma clave de idempotencia: no se cobra dos veces.", NARANJA, fs=9.6)

# ── B · webhook
caja(0.35, 14.05, 2.35, 11.45, GRIS, "#f3f3f3",
     "B · WEBHOOK · canal SÍNCRONO REST, resultado diferido · solo si el pago quedó PENDIENTE")
flecha(M, P, 10.0, "7. POST /api/v1/pagos/webhook?data.id=…   (cabecera x-signature)", GRIS)
nota(X[P], 9.35, "8. valida la firma HMAC antes de leer el cuerpo  →  si no coincide, 401", GRIS, fs=9.4)
flecha(P, A, 8.3, "9. consultar(idExterno)", GRIS)
flecha(A, M, 7.3, "10. GET /v1/payments/{id}", GRIS)
flecha(M, A, 6.3, "estado actual", GRIS, resp=True)
flecha(A, P, 5.3, "APROBADO · RECHAZADO · VENCIDO", GRIS, resp=True)
flecha(P, B, 4.25, "11. UPDATE … WHERE idExterno = :id AND estado = 'PENDIENTE'", GRIS)
nota((X[P] + X[B]) / 2, 3.82, "un aviso repetido no modifica ninguna fila", GRIS, fs=9)
flecha(P, M, 2.75, "12. 200 vacío, después de guardar   · 504 si no pudo consultar → Mercado Pago reintenta",
       GRIS, resp=True, dy=0.2, fs=9.6)

# ── pie
ax.text(7.2, 1.55, "Pendiente de definir: cómo se entera ServicioDeVentas de que un pago PENDIENTE se resolvió.",
        ha="center", va="center", fontsize=9.6, color=GRIS, fontweight="bold")
ax.text(7.2, 0.75, "Rojo = sincrónico · Naranja = alternativa ante falla · Gris = webhook · Punteado = respuesta",
        ha="center", va="center", fontsize=11, color=TINTA)
ax.text(7.2, 0.25, "Todo el diagrama está DISEÑADO, PENDIENTE: hoy la pasarela es PagosFake.",
        ha="center", va="center", fontsize=10, color=TINTA, fontweight="bold")

plt.tight_layout(pad=0.4)
base = __file__.rsplit(".", 1)[0]
fig.savefig(base + ".png", dpi=150)
fig.savefig(base + ".svg")
print("ok:", base + ".png", base + ".svg")
