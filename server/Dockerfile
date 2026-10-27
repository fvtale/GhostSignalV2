# Build from the repository root so the mission file can be copied in:
#   docker build -f server/Dockerfile -t ghostsignal-server .
FROM node:22-slim
WORKDIR /srv/server
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev
COPY server/src ./src
COPY server/admin ./admin
COPY server/account ./account
COPY server/play ./play
# The web player plays the same music as the app.
COPY app/src/main/assets/music /srv/assets/music
COPY app/src/main/assets/mission/ghostsignal.json /srv/mission/ghostsignal.json
RUN mkdir -p /data && chown node:node /data
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data MISSION_PATH=/srv/mission/ghostsignal.json ASSETS_DIR=/srv/assets
VOLUME /data
EXPOSE 8080
USER node
CMD ["node", "src/index.js"]
