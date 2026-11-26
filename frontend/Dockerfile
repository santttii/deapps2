# Imagen web: nginx sirve el build del frontend por HTTPS y hace de proxy de /api hacia el
# backend. La arma docker-compose.yml (raíz del repo); para desarrollar sigue npm run dev.

# Etapa 1: build de producción. PALCO_URL_PUBLICA es la dirección con la que se abre la app:
# el frontend llama a la API en ese mismo origen y nginx la pasa al backend.
FROM node:22-alpine AS build
ARG PALCO_URL_PUBLICA=https://localhost
WORKDIR /app
COPY package.json package-lock.json ./
# Sin scripts de instalación, como en el job frontend del CI: en el package-lock el único paquete con uno es
# fsevents, que es solo para macOS y en Linux no se instala. El build no los necesita.
RUN npm ci --no-audit --no-fund --ignore-scripts
COPY . .
RUN VITE_API_URL="$PALCO_URL_PUBLICA" npm run build

# Etapa 2: nginx con TLS. El certificado autofirmado se genera al arrancar (nunca está en el repo
# ni en la imagen); openssl se instala para eso. Antes se actualizan los paquetes de Alpine: la imagen
# base puede salir con alguno que ya tiene un arreglo publicado, y el escaneo de Trivy lo marcaría.
#
# nginx corre entero sin root (DEF-71): la imagen nginx-unprivileged es la oficial de nginx con el proceso principal
# como el usuario nginx (uid 101) y su pid y sus temporales en /tmp. Root se usa solo para armar la imagen: actualizar
# los paquetes, instalar openssl y dejar /etc/nginx/tls con dueño nginx, donde certificado.sh escribe la clave y el
# certificado al arrancar. Escucha en 80 y 443 igual que antes: Docker, desde la versión 20.10, deja abrir esos
# puertos dentro del contenedor sin root (net.ipv4.ip_unprivileged_port_start=0), así que ni nginx.conf ni
# docker-compose.yml cambian.
FROM nginxinc/nginx-unprivileged:1.30-alpine
USER root
RUN apk upgrade --no-cache && apk add --no-cache openssl \
 && mkdir -p /etc/nginx/tls && chown 101:101 /etc/nginx/tls
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/certificado.sh /docker-entrypoint.d/05-certificado.sh
RUN chmod +x /docker-entrypoint.d/05-certificado.sh
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 80 443
