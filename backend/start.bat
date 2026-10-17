@echo off
REM Startet das Backend auf Port 8080 (erreichbar im ganzen WLAN).
cd /d %~dp0
if not exist .venv (
  python -m venv .venv
  .venv\Scripts\pip install -r requirements.txt
)
if not exist .env copy .env.example .env
.venv\Scripts\uvicorn app.main:app --host 0.0.0.0 --port 8080
