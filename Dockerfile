# 보호자 웹과 API를 한 이미지에서 띄웁니다.
# podman build -t naksang . && podman run --rm -p 8080:8080 --env-file server/.env -v naksang-data:/data naksang
FROM node:22-bookworm-slim AS web
WORKDIR /src/guardian-web
COPY guardian-web/package.json guardian-web/package-lock.json ./
RUN npm ci
COPY guardian-web/ ./
RUN npm run build

FROM node:22-bookworm-slim AS server
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /src/server
COPY server/package.json server/package-lock.json ./
RUN npm ci
COPY server/ ./
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
ENV NODE_ENV=production
ENV PORT=8080
ENV STATIC_DIR=/app/guardian-web/dist
ENV DATABASE_PATH=/data/naksang.db
WORKDIR /app
COPY --from=server /src/server/package.json /app/server/package.json
COPY --from=server /src/server/node_modules /app/server/node_modules
COPY --from=server /src/server/dist /app/server/dist
COPY --from=web /src/guardian-web/dist /app/guardian-web/dist
WORKDIR /app/server
VOLUME ["/data"]
EXPOSE 8080
CMD ["node", "dist/index.js"]
