"""Gemeinsame Test-Einstellungen: nie echt drucken, nie echte Ausweisdaten anfassen."""
import os
import tempfile

os.environ.setdefault("SR_PRINTER_METHODS", "mock")
os.environ.setdefault("SR_BADGE_STORE", os.path.join(tempfile.mkdtemp(prefix="sr-badges-"), "badges.json"))
