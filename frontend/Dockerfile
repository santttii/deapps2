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
# El proceso principal de nginx arranca como root, como en la imagen oficial, por dos motivos: escucha en los
# puertos 80 y 443 (menores que 1024, que solo root puede abrir) y, al arrancar, certificado.sh escribe la clave
# y el certificado en /etc/nginx/tls. Los pedidos no los atiende root sino los procesos worker, que nginx corre
# con el usuario sin privilegios nginx (la directiva "user nginx" del nginx.conf de la imagen). Pasar todo a un
# usuario sin privilegios pide cambiar los puertos que escucha nginx.conf y los que publica docker-compose.yml.
FROM nginx:1.30-alpine
RUN apk upgrade --no-cache && apk add --no-cache openssl
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/certificado.sh /docker-entrypoint.d/05-certificado.sh
RUN chmod +x /docker-entrypoint.d/05-certificado.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80 443
