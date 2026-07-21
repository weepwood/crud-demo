#!/usr/bin/env sh
set -eu

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env. Change APP_PASSWORD and POSTGRES_PASSWORD before exposing the service."
fi

docker compose up --build
