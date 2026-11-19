# Matriz de trazabilidad

Generada por `scripts/qa/matriz.mjs` el 2026-11-19 17:42 UTC.

| HU | Prioridad | Criterio | Casos | Resultado |
|---|---|---|---|---|
| HU-01 Registrarme y verificar mi email | Must | CA-01.1 Registro y verificación correctos | escenario 24 (escenarios.mjs: pasa)<br>RegistroYVerificacionTest#registrarseGuardaLaCuentaYSuCodigoConElEmailNormalizadoYElDniSoloConDigitos (JUnit: pasa)<br>RegistroCompletoTest#unaCuentaQueCompletaElRegistroSaleConLaIdentidadVerificada (JUnit: pasa) | PASA |
| HU-01 Registrarme y verificar mi email | Must | CA-01.2 Email o DNI ya registrados | escenario 24 (escenarios.mjs: pasa)<br>RegistroYVerificacionTest#unEmailYaVerificadoEsUnDatoInvalidoDelCampoEmail (JUnit: pasa)<br>RegistroYVerificacionTest#unDniYaVerificadoEsUnDatoInvalidoDelCampoDni (JUnit: pasa) | PASA |
| HU-01 Registrarme y verificar mi email | Must | CA-01.3 Datos inválidos | escenario 24 (escenarios.mjs: pasa)<br>RegistroYVerificacionTest#unDniDeSeisDigitosSeRechazaSinTocarLaBase (JUnit: pasa)<br>RegistroYVerificacionTest#unMenorDeDieciseisSeRechaza (JUnit: pasa) | PASA |
| HU-01 Registrarme y verificar mi email | Must | CA-01.4 Código incorrecto | escenario 24 (escenarios.mjs: pasa)<br>RegistroYVerificacionTest#unCodigoIncorrectoSumaElIntentoYLoGuardaEnElMomento (JUnit: pasa)<br>RegistroYVerificacionTest#conCincoIntentosFallidosElCodigoYaNoSirve (JUnit: pasa) | PASA |
| HU-01 Registrarme y verificar mi email | Must | CA-01.5 Campos que no se pueden elegir (mass assignment) | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.1 Login correcto | escenario 5 (escenarios.mjs: pasa)<br>escenario 24 (escenarios.mjs: pasa)<br>TokensTest#laFirmaSeVerificaConLaClavePublicaReconstruidaDesdeElJwks (JUnit: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.2 Contraseña incorrecta | escenario 24 (escenarios.mjs: pasa)<br>ContrasenasTest#laContrasenaCorrectaVerificaYLaIncorrectaNo (JUnit: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.3 Fuerza bruta | escenario 24 (escenarios.mjs: pasa)<br>LimiteDeIntentosDeLoginTest#cincoFallidosYElSextoConLaClaveBuenaRecibe429 (JUnit: pasa)<br>LimiteDeIntentosDeLoginTest#laVentanaEsDeQuinceMinutos (JUnit: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.4 Token inválido | escenario 5 (escenarios.mjs: pasa)<br>TokensTest#unTokenModificadoNoVerifica (JUnit: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.5 Email sin verificar | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-03 Recuperar mi contraseña | Should | CA-03.1 Reinicio correcto | escenario 24 (escenarios.mjs: pasa)<br>ReinicioDePasswordTest#pedirloConUnaCuentaVerificadaGuardaElCodigoYMandaElEmail (JUnit: pasa)<br>ReinicioDePasswordTest#conElCodigoCorrectoCambiaLaContrasenaYBorraElCodigo (JUnit: pasa) | PASA |
| HU-03 Recuperar mi contraseña | Should | CA-03.2 Email que no existe | escenario 24 (escenarios.mjs: pasa)<br>ReinicioDePasswordTest#unEmailQueNoExisteNoMandaNadaYTampocoEsUnError (JUnit: pasa) | PASA |
| HU-03 Recuperar mi contraseña | Should | CA-03.3 Código incorrecto | escenario 24 (escenarios.mjs: pasa)<br>ReinicioDePasswordTest#unCodigoIncorrectoSumaUnIntentoYNoCambiaNada (JUnit: pasa) | PASA |
| HU-04 Consultar el catálogo | Must | CA-04.1 Listado y detalle | escenario 24 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#elDetalleDevuelveElEventoConSusSectores (JUnit: pasa)<br>CupoEnH2Test#elListadoFiltraPorCategoriaYOrdenaPorFecha (JUnit: pasa) | PASA |
| HU-04 Consultar el catálogo | Must | CA-04.2 Búsqueda sin resultados | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-04 Consultar el catálogo | Must | CA-04.3 Evento inexistente | escenario 24 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#unSlugQueNoExisteEsNoEncontrado (JUnit: pasa) | PASA |
| HU-04 Consultar el catálogo | Must | CA-04.4 Entrada maliciosa en la búsqueda | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.1 Compra completa | escenario 1 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.2 Pago rechazado | escenario 3 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.3 Reglas de la compra | escenario 4 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.4 Idempotencia | escenario 2 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.5 Falla externa: Pagos no responde | ClienteDePagosTest#siPagosNoRespondeEsSinRespuesta (JUnit: pasa)<br>ConciliacionDePagosTest#conUnPagoAprobadoConfirmaLaVentaConEsePagoYLosTitularesCopiados (JUnit: pasa)<br>ConciliacionDePagosTest#sinPagoConEsaClaveVenceLaVentaYRecienDespuesDevuelveElCupo (JUnit: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.6 Falla externa: Eventos no responde al reservar | ClienteDeEventosTest#siEventosNoRespondeAlReservarEsSinRespuesta (JUnit: pasa)<br>ServicioDeVentasTest#unSectorQueNoRespondioNoSeDevuelvePorqueNoSeSabeSiSeReservo (JUnit: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.7 Reserva vencida o cancelada | escenario 6 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.8 Identidad sin verificar | ComprasEnCursoTest#sinLaIdentidadVerificadaNoSeAbreLaCompra (JUnit: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.9 Mensajería: emisión una sola vez | escenario 7 (escenarios.mjs: pasa)<br>escenario 8 (escenarios.mjs: pasa)<br>escenario 9 (escenarios.mjs: pasa)<br>escenario 10 (escenarios.mjs: pasa)<br>escenario 11 (escenarios.mjs: pasa)<br>escenario 12 (escenarios.mjs: pasa)<br>escenario 13 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.10 Reglas entre compras: tope por evento y un DNI por evento | escenario 23 (escenarios.mjs: pasa)<br>ServicioDeVentasTest#conLasDeOtrasComprasNoSePuedePasarDeSeisEntradasPorEvento (JUnit: pasa)<br>ServicioDeVentasTest#unDniQueYaTieneEntradaParaElEventoNoPuedeSerTitularDeOtra (JUnit: pasa)<br>ServicioDeVentasTest#antesDeCobrarSeVuelveAMirarElDniYSiOtraCompraLoUsoNoSeCobra (JUnit: pasa)<br>ComprasEnCursoTest#siOtraCompraUsoElDniDeUnTitularNoSeCobraYSeQuitaLaMarca (JUnit: pasa)<br>ConciliacionEnH2Test#lasEntradasDelCompradorCuentanLasEnCursoVigentesYLasPagadasMenosLasRevendidas (JUnit: pasa)<br>ConciliacionEnH2Test#unDniTieneEntradaSiEsTitularDeUnaEnPieOEstaEnUnaCompraQueSeEstaCobrando (JUnit: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.1 Factura emitida | escenario 15 (escenarios.mjs: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.2 Una sola factura por compra | escenario 15 (escenarios.mjs: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.3 Falla externa: ARCA no responde o rechaza | ServicioDeFacturacionTest#siArcaNoRespondeLaFallaSaleYNoSeGuardaNada (JUnit: pasa)<br>ServicioDeFacturacionTest#siArcaRechazaElComprobanteLaFallaSaleYNoSeGuardaNada (JUnit: pasa)<br>FacturacionDeComprasMDBTest#conArcaCaidoElMensajeVuelveAlBrokerYLaReentregaFactura (JUnit: pasa)<br>ArcaSoapTest#siArcaTardaMasQueElTiempoDeLecturaFallaSinEsperarlo (JUnit: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.4 Factura de otra persona | FacturasResourceTest#otroUsuarioNoVeUnaFacturaAjenaYRecibeElMismo404QueSiNoExistiera (JUnit: pasa)<br>ServicioDeFacturacionTest#unaFacturaAjenaRespondeLoMismoQueUnaQueNoExiste (JUnit: pasa) | PASA |
| HU-07 Ver mis entradas | Must | CA-07.1 Mis compras | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-07 Ver mis entradas | Must | CA-07.2 Sin compras | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-07 Ver mis entradas | Must | CA-07.3 Entrada de otra persona (BOLA) | escenario 5 (escenarios.mjs: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.1 Publicación dentro del rango | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.2 Precio fuera de rango | escenario 17 (escenarios.mjs: pasa)<br>ServicioDeReventaTest#losBordesDelRangoSeAceptanYUnPesoAfueraNo (JUnit: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.3 Entrada que no es mía o no está disponible | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.4 Retirar la publicación | ServicioDeReventaTest#retirarUnaAjenaEsNoEncontradaYUnaPropiaLaDevuelveAValida (JUnit: pasa)<br>ServicioDeReventaTest#noSeRetiraUnaQueNoEstaActiva (JUnit: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.5 Entrada publicada en la puerta | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.1 Compra en la reventa | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.2 Pago rechazado | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.3 Publicación propia o ya vendida | escenario 17 (escenarios.mjs: pasa)<br>ServicioDeReventaTest#nadieCompraSuPropiaPublicacion (JUnit: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.4 Falla externa: Pagos no responde | escenario 19 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.5 Cobrado y sin entrada | escenario 20 (escenarios.mjs: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.1 Alta de evento | escenario 24 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#crearUnEventoParaMasTardeSeGuarda (JUnit: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.2 Datos inválidos | escenario 24 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#crearUnEventoConFechaPasadaEsUnDatoInvalidoDelCampoFechaISO (JUnit: pasa)<br>ServicioDeEventosTest#crearUnEventoConUnaCategoriaQueNoExisteEsUnDatoInvalidoDelCampoCategoria (JUnit: pasa)<br>NuevoEventoDtoTest#cadaSectorSeValidaConSuPrecioYSuCupo (JUnit: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.3 Permisos (BFLA y BOLA) | escenario 5 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#unEventoAjenoNoSePuedeEditarYParaElOrganizadorNoExiste (JUnit: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.4 Panel con mis eventos | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-11 Cancelar un evento | Should | CA-11.1 Cancelación con reembolso | escenario 22 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#cancelarMarcaElEventoYPublicaElMensajeConSuMotivo (JUnit: pasa)<br>CancelacionDeEventosTest#anulaCadaCompraPagadaPideSuReembolsoYPublicaSuAviso (JUnit: pasa)<br>PublicacionesEnH2Test#alCancelarseElEventoSeRetiranSusActivasSalvoLaQueSeEstaPagando (JUnit: pasa) | PASA |
| HU-11 Cancelar un evento | Should | CA-11.2 Evento de otro organizador | escenario 22 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#unEventoAjenoNoSePuedeCancelar (JUnit: pasa) | PASA |
| HU-11 Cancelar un evento | Should | CA-11.3 Comprar un evento cancelado | escenario 22 (escenarios.mjs: pasa)<br>ServicioDeEventosTest#unEventoCanceladoNoVendeMasEntradasPeroSiguePudiendoDevolverCupo (JUnit: pasa)<br>ClienteDeEventosTest#unEventoCanceladoEsUnConflictoDeEventoCanceladoYNoDeCupoAgotado (JUnit: pasa) | PASA |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.1 Resumen propio | escenario 24 (escenarios.mjs: pasa)<br>ResumenDeVentasTest#laLiquidacionEsElSubtotalYElCargoQuedaParaPalco (JUnit: pasa) | PASA |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.2 Eventos ajenos | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.3 Rol sin permiso | escenario 24 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.1 Primer escaneo | escenario 16 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.2 Segundo escaneo | escenario 16 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.3 Código inexistente o de otro evento | escenario 16 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.4 Falla externa: Ventas no responde | ServicioDeValidacionTest#siVentasNoRespondeSeRegistraElIntentoYSeAvisaConUn504 (JUnit: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.5 Personal no asignado | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.1 Asignar y quitar | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.2 Evento de otro organizador | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.3 Cuenta que no es de puerta | escenario 21 (escenarios.mjs: pasa)<br>ServicioDeValidacionTest#unaCuentaQueNoEsDePuertaNoSeAsignaYNoQuedaNada (JUnit: pasa)<br>ClienteDeUsuariosTest#conUn200DevuelveElRolDeLaCuenta (JUnit: pasa) | PASA |

62 criterios de 14 historias: 62 pasan, 0 fallan, 0 sin caso.
