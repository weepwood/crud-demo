$ErrorActionPreference = "Stop"

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created .env. Change APP_PASSWORD and POSTGRES_PASSWORD before exposing the service." -ForegroundColor Yellow
}

docker compose up --build
