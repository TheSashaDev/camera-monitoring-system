FROM node:20-alpine

WORKDIR /app

# Install dependencies for better-sqlite3
RUN apk add --no-cache python3 make g++

COPY package*.json ./
RUN npm ci --only=production || npm install --only=production

COPY . .

# Create directories for data persistence
RUN mkdir -p /app/recordings /app/uploads

EXPOSE 3000

ENV NODE_ENV=production

CMD ["node", "src/index.js"]
