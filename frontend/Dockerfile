# ---- build: Node only exists in this stage --------------------------------
FROM node:22-alpine AS build
WORKDIR /app
# Dependencies first so this layer is cached until package*.json changes.
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY . .
RUN npm run build

# ---- run: just nginx and static files (~50 MB, no Node, no source) -------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
