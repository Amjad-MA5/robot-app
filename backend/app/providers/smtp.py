"""Send employee notifications through a standard SMTP server."""
from __future__ import annotations

import logging
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import parseaddr

from ..config import Settings
from ..models import Employee, NotificationResult
from .base import ProviderError

log = logging.getLogger(__name__)


def _valid_address(address: str) -> bool:
    _, parsed = parseaddr(address)
    return parsed == address and address.count("@") == 1 and not any(char.isspace() for char in address)


def notify_email(
    cfg: Settings,
    employee: Employee,
    subject: str,
    html: str,
    text: str,
) -> NotificationResult:
    recipient = employee.email.strip()
    if not _valid_address(recipient):
        return NotificationResult(sent=False, channel="email", detail=f"Invalid email address for {employee.name}")
    if recipient.casefold().endswith("@example.com"):
        return NotificationResult(sent=False, channel="email", detail=f"{recipient} is a placeholder address")
    if not cfg.smtp_host:
        return NotificationResult(sent=False, channel="email", detail="SMTP is not configured")
    if not _valid_address(cfg.smtp_from):
        return NotificationResult(sent=False, channel="email", detail="SR_SMTP_FROM is not a valid email address")

    message = EmailMessage()
    message["From"] = cfg.smtp_from
    message["To"] = recipient
    message["Subject"] = subject
    message.set_content(text)
    message.add_alternative(html, subtype="html")

    try:
        if cfg.smtp_security == "ssl":
            with smtplib.SMTP_SSL(
                cfg.smtp_host,
                cfg.smtp_port,
                timeout=20,
                context=ssl.create_default_context(),
            ) as server:
                _send(server, cfg, message)
        else:
            with smtplib.SMTP(cfg.smtp_host, cfg.smtp_port, timeout=20) as server:
                server.ehlo()
                if cfg.smtp_security == "starttls":
                    server.starttls(context=ssl.create_default_context())
                    server.ehlo()
                _send(server, cfg, message)
    except (OSError, ProviderError, smtplib.SMTPException) as exc:
        log.warning("Email to %s failed: %s", recipient, exc)
        return NotificationResult(sent=False, channel="email", detail=str(exc))

    return NotificationResult(
        sent=True,
        channel="email",
        detail=f"Email accepted by SMTP server for {recipient}",
    )


def _send(server: smtplib.SMTP, cfg: Settings, message: EmailMessage) -> None:
    if cfg.smtp_username:
        server.login(cfg.smtp_username, cfg.smtp_password)
    refused = server.send_message(message)
    if refused:
        recipients = ", ".join(refused)
        raise ProviderError(f"SMTP server refused recipient(s): {recipients}")
