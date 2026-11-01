FROM node:22.18.0-alpine AS build
WORKDIR /workspace
COPY package*.json ./
COPY apps/admin/package.json apps/admin/package.json
COPY apps/teacher/package.json apps/teacher/package.json
COPY packages/shared/package.json packages/shared/package.json
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.28-alpine AS teacher
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/teacher/dist /usr/share/nginx/html

FROM nginx:1.28-alpine AS admin
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin/dist /usr/share/nginx/html
