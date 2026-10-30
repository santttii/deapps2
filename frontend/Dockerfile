# Imagen web: nginx sirve el build del frontend por HTTPS y hace de proxy de /api y /legado
# hacia el backend. La arma docker-compose.yml (raíz del repo); para desarrollar sigue npm run dev.

# Etapa 1: build de producción. PALCO_URL_PUBLICA es la dirección con la que se abre la app:
# el frontend llama a la API en ese mismo origen y nginx la pasa al backend.
FROM node:22-alpine AS build
ARG PALCO_URL_PUBLICA=https://localhost
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN VITE_API_URL="$PALCO_URL_PUBLICA" npm run build

# Etapa 2: nginx con TLS. El certificado autofirmado se genera al arrancar (nunca está en el repo
# ni en la imagen); openssl se instala para eso.
FROM nginx:1.27-alpine
RUN apk add --no-cache openssl
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/certificado.sh /docker-entrypoint.d/05-certificado.sh
RUN chmod +x /docker-entrypoint.d/05-certificado.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80 443
