#!/usr/bin/env bash
# Startet das Backend auf Port 8080 (erreichbar im ganzen WLAN).
# Erster Start: legt eine virtuelle Python-Umgebung an und installiert die Pakete.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  python3 -m venv .venv
  .venv/bin/pip install -r requirements.txt
fi
[ -f .env ] || cp .env.example .env
echo "Backend läuft gleich unter: http://$(hostname -I 2>/dev/null | awk '{print $1}'):8080"
exec .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8080
