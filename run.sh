#!/usr/bin/env bash
# Starts the app: rebuilds the frontend if it changed, runs the backend, opens the browser.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f frontend/dist/index.html ] || [ -n "$(find frontend/src frontend/index.html -newer frontend/dist/index.html -print -quit)" ]; then
  echo "Building frontend..."
  (cd frontend && npm run build)
fi

mkdir -p data
(cd backend && ./mvnw -q spring-boot:run) &
backend_pid=$!
trap 'kill $backend_pid 2>/dev/null' INT TERM EXIT

echo "Starting backend..."
until curl -sf http://localhost:8080/api/health > /dev/null; do
  if ! kill -0 $backend_pid 2>/dev/null; then
    echo "Backend failed to start." >&2
    exit 1
  fi
  sleep 1
done

echo "Ready at http://localhost:8080"
[ -n "${NO_BROWSER:-}" ] || xdg-open http://localhost:8080 > /dev/null 2>&1 || true
wait $backend_pid
