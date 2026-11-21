# Imagen del backend: WildFly con los 9 servicios y el ARCA simulado.
# La arma docker-compose.yml (raíz del repo); para desarrollar sigue estando ./dev.sh.
#
# Etapa 1: Maven compila y arma el servidor (servidor/target/server) igual que dev.sh.
# Los tests no corren acá: corren en el CI (mvn verify). La caché de ~/.m2 la guarda
# BuildKit entre construcciones, así la segunda vez no vuelve a bajar WildFly.
# -T 1C arma los módulos en paralelo, un hilo por núcleo: los servicios no dependen entre sí.
FROM maven:3.9-eclipse-temurin-25 AS armado
WORKDIR /fuente
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -T 1C -DskipTests install

# Etapa 2: solo un JRE 25 y el servidor ya armado.
FROM eclipse-temurin:25-jre
# WildFly corre con el usuario palco, sin privilegios. Su carpeta personal es /home/palco (ahí jboss-cli guarda
# su historial) y no /opt/palco: así /opt/palco y los guiones de arranque y de salud quedan de root, y palco
# puede ejecutarlos pero no cambiarlos ni reemplazarlos.
RUN useradd --system --create-home palco
# El servidor sí es de palco: WildFly escribe dentro de standalone (configuración, data, log, tmp y las marcas
# de despliegue de deployments) y jboss-cli guarda en standalone.xml lo que cambia en caliente.
COPY --from=armado --chown=palco:palco /fuente/servidor/target/server /opt/palco/server
COPY --chmod=755 docker/arrancar.sh docker/salud.sh /opt/palco/
# standalone/data (journal del broker y registro de transacciones) va en un volumen del compose. Docker
# crea el volumen con el dueño que tiene esta carpeta en la imagen: si fuera root, WildFly no podría escribir.
RUN mkdir -p /opt/palco/server/standalone/data && chown palco:palco /opt/palco/server/standalone/data
USER palco
EXPOSE 8080
# Sano cuando los 9 servicios contestan su /health (el primer arranque migra la base: tarda).
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=30 CMD ["/opt/palco/salud.sh"]
ENTRYPOINT ["/opt/palco/arrancar.sh"]
