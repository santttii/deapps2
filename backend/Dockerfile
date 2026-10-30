# Imagen del backend: WildFly con los 8 servicios y el ARCA simulado.
# La arma docker-compose.yml (raíz del repo); para desarrollar sigue estando ./dev.sh.
#
# Etapa 1: Maven compila y arma el servidor (servidor/target/server) igual que dev.sh.
# Los tests no corren acá: corren en el CI (mvn verify). La caché de ~/.m2 la guarda
# BuildKit entre construcciones, así la segunda vez no vuelve a bajar WildFly.
FROM maven:3.9-eclipse-temurin-25 AS armado
WORKDIR /fuente
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests install

# Etapa 2: solo un JRE 25 y el servidor ya armado.
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --home-dir /opt/palco palco
COPY --from=armado --chown=palco:palco /fuente/servidor/target/server /opt/palco/server
COPY --chown=palco:palco docker/arrancar.sh docker/salud.sh /opt/palco/
RUN chmod +x /opt/palco/arrancar.sh /opt/palco/salud.sh
USER palco
EXPOSE 8080
# Sano cuando los 8 servicios contestan su /health (el primer arranque migra la base: tarda).
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=30 CMD ["/opt/palco/salud.sh"]
ENTRYPOINT ["/opt/palco/arrancar.sh"]
