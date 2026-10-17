"""E-Mail-Benachrichtigung per SMTP (z. B. Gmail), Mitarbeiter und Kalender wie im Demo-Modus."""
from __future__ import annotations

import logging
import smtplib
import ssl
from email.message import EmailMessage

from ..config import Settings
from ..models import Employee, NotificationResult
from .base import ProviderError
from .mock import MockProvider

log = logging.getLogger("smart-receptionist")

LOCAL_HOSTS = ("localhost", "127.0.0.1")


class SmtpProvider(MockProvider):
    name = "smtp"

    def __init__(self, cfg: Settings) -> None:
        # Ohne Zugangsdaten nur für lokale Test-Server (z. B. SR_SMTP_HOST=localhost, Port 1025)
        if (not cfg.smtp_user or not cfg.smtp_password) and cfg.smtp_host not in LOCAL_HOSTS:
            raise ProviderError("SR_SMTP_USER und SR_SMTP_PASSWORD fehlen in der .env")
        super().__init__(cfg)

    def notify(self, employee: Employee, subject: str, html: str, text: str) -> NotificationResult:
        recipient = self.cfg.smtp_redirect_to or employee.email
        if not recipient:
            return NotificationResult(sent=False, channel="email", detail=f"{employee.name} hat keine E-Mail-Adresse")
        msg = EmailMessage()
        msg["Subject"] = subject
        msg["From"] = self.cfg.smtp_from or self.cfg.smtp_user
        msg["To"] = recipient
        msg.set_content(text)
        msg.add_alternative(html, subtype="html")
        try:
            with smtplib.SMTP(self.cfg.smtp_host, self.cfg.smtp_port, timeout=15) as smtp:
                smtp.ehlo()  # nötig, damit has_extn() die Fähigkeiten des Servers kennt
                if smtp.has_extn("starttls"):
                    smtp.starttls(context=ssl.create_default_context())
                if self.cfg.smtp_user:
                    smtp.login(self.cfg.smtp_user, self.cfg.smtp_password)
                smtp.send_message(msg)
        except (smtplib.SMTPException, OSError) as exc:
            raise ProviderError(f"E-Mail konnte nicht gesendet werden: {exc}") from exc
        # Zusätzlich im Dashboard anzeigen
        super().notify(employee, subject, html, text)
        self.inbox[0]["channel"] = "email"
        return NotificationResult(sent=True, channel="email", detail=f"E-Mail an {recipient}")

    def status(self) -> dict:
        return {**super().status(), "provider": self.name, "smtp_host": self.cfg.smtp_host}
