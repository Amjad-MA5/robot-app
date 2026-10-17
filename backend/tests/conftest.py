"""Tests must never use the developer's real .env: no real SMTP, always demo mode."""
import os

# load_dotenv() does not override variables that are already set, even empty ones.
for name in ("SR_SMTP_HOST", "SR_SMTP_USERNAME", "SR_SMTP_PASSWORD", "SR_SMTP_FROM"):
    os.environ[name] = ""
os.environ["SR_SMTP_SECURITY"] = "starttls"
os.environ["SR_MODE"] = "mock"

import tempfile

os.environ.setdefault("SR_PRINTER_METHODS", "mock")
os.environ.setdefault("SR_BADGE_STORE", os.path.join(tempfile.mkdtemp(prefix="sr-badges-"), "badges.json"))
