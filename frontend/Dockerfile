# Многоэтапная сборка: Node собирает статику, nginx её отдаёт.
FROM node:22-alpine AS build
# Режим данных: live (реальный API за nginx) по умолчанию для запуска из docker-compose;
# переопределяется --build-arg VITE_API_MODE=stub для демо без бэкенда.
ARG VITE_API_MODE=live
ENV VITE_API_MODE=$VITE_API_MODE
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
