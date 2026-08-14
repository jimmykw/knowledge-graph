FROM amazoncorretto:21-alpine

# Install Node.js, npm, and Git via Alpine apk
RUN apk add --no-cache nodejs npm git

# Install OpenCode
RUN npm install -g opencode-ai

WORKDIR /app
ENTRYPOINT ["opencode"]