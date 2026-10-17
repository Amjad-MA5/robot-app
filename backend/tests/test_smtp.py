from __future__ import annotations

import smtplib
from dataclasses import replace
from email.message import EmailMessage

import pytest

from app.config import settings
from app.models import Employee
from app.providers import smtp


class FakeSMTP:
    instances: list[FakeSMTP] = []
    refused: dict = {}

    def __init__(self, host: str, port: int, **kwargs) -> None:
        self.host = host
        self.port = port
        self.kwargs = kwargs
        self.events: list[tuple] = []
        self.message: EmailMessage | None = None
        self.refused = self.__class__.refused
        self.__class__.instances.append(self)

    def __enter__(self) -> FakeSMTP:
        return self

    def __exit__(self, *_args) -> None:
        return None

    def ehlo(self) -> None:
        self.events.append(("ehlo",))

    def starttls(self, *, context) -> None:
        self.events.append(("starttls", context))

    def login(self, username: str, password: str) -> None:
        self.events.append(("login", username, password))

    def send_message(self, message: EmailMessage) -> dict:
        self.message = message
        self.events.append(("send",))
        return self.refused


def smtp_settings(**overrides):
    values = {
        "smtp_host": "smtp.example.net",
        "smtp_port": 587,
        "smtp_username": "reception@example.net",
        "smtp_password": "test-password",
        "smtp_from": "reception@example.net",
        "smtp_security": "starttls",
    }
    values.update(overrides)
    return replace(settings, **values)


def test_sends_html_email_to_employee_address_with_starttls(monkeypatch):
    FakeSMTP.instances.clear()
    monkeypatch.setattr(smtp.smtplib, "SMTP", FakeSMTP)
    employee = Employee(id="amjad", name="Amjad", email="amjadshukkoor12@outlook.com")

    result = smtp.notify_email(
        smtp_settings(),
        employee,
        "Visitor at reception",
        "<p>A visitor is waiting.</p>",
        "A visitor is waiting.",
    )

    assert result.sent and result.channel == "email"
    server = FakeSMTP.instances[0]
    assert (server.host, server.port) == ("smtp.example.net", 587)
    assert server.message is not None
    assert server.message["To"] == employee.email
    assert server.message["From"] == "reception@example.net"
    assert server.message["Subject"] == "Visitor at reception"
    assert server.message.get_body(preferencelist=("plain",)).get_content() == "A visitor is waiting.\n"
    assert server.message.get_body(preferencelist=("html",)).get_content() == "<p>A visitor is waiting.</p>\n"
    assert server.events[0][0] == "ehlo"
    assert server.events[1][0] == "starttls"
    assert server.events[2] == ("ehlo",)
    assert server.events[3] == ("login", "reception@example.net", "test-password")
    assert server.events[4] == ("send",)


def test_smtp_refusal_is_reported_as_failure(monkeypatch):
    FakeSMTP.instances.clear()
    monkeypatch.setattr(smtp.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(FakeSMTP, "refused", {"amjadshukkoor12@outlook.com": (550, b"Mailbox unavailable")})
    employee = Employee(id="amjad", name="Amjad", email="amjadshukkoor12@outlook.com")

    result = smtp.notify_email(smtp_settings(), employee, "Subject", "<p>Hi</p>", "Hi")

    assert not result.sent and result.channel == "email"


def test_smtp_failure_is_not_reported_as_sent(monkeypatch):
    FakeSMTP.instances.clear()

    class FailingSMTP(FakeSMTP):
        def starttls(self, *, context) -> None:
            raise smtplib.SMTPConnectError(454, b"TLS unavailable")

    monkeypatch.setattr(smtp.smtplib, "SMTP", FailingSMTP)
    employee = Employee(id="amjad", name="Amjad", email="amjadshukkoor12@outlook.com")

    result = smtp.notify_email(smtp_settings(), employee, "Subject", "<p>Hi</p>", "Hi")

    assert not result.sent and result.channel == "email"


def test_placeholder_addresses_are_not_sent():
    employee = Employee(id="demo", name="Anna", email="anna.schmidt@example.com")

    result = smtp.notify_email(smtp_settings(), employee, "Subject", "<p>Hi</p>", "Hi")

    assert not result.sent and "placeholder" in result.detail


def test_smtp_credentials_require_encryption():
    with pytest.raises(ValueError, match="SMTP credentials require TLS"):
        smtp_settings(smtp_security="none")
