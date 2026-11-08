# The relay, with the ground station web app it serves. Build from the repository root:
#   docker build -f relay/Dockerfile -t smartphonex-relay .
FROM node:22.17.1-alpine3.22

ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8787 \
    RELAY_DATA=/data \
    RELAY_WEB=/app/web

WORKDIR /app/relay
COPY relay/package.json relay/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY relay/server.mjs relay/auth.mjs relay/admin.mjs ./
COPY app/src/main/assets/web /app/web

RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD wget -q -O /dev/null http://127.0.0.1:8787/health || exit 1
CMD ["node", "server.mjs"]
