FROM node:22.23.5-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_CONTROL_PLANE_API_URL
ARG VITE_AGENT_RUNTIME_API_URL
ENV VITE_CONTROL_PLANE_API_URL=$VITE_CONTROL_PLANE_API_URL \
    VITE_AGENT_RUNTIME_API_URL=$VITE_AGENT_RUNTIME_API_URL
RUN npm run build

FROM node:22.23.5-bookworm-slim
WORKDIR /app
COPY --from=build --chown=node:node /app/node_modules node_modules
COPY --from=build --chown=node:node /app/dist dist
COPY --from=build --chown=node:node /app/package.json package.json
USER node
EXPOSE 4173
# Local development preview only; production hosting is a future decision.
CMD ["npm", "run", "preview", "--", "--host", "0.0.0.0", "--port", "4173", "--strictPort"]
