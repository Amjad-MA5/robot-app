"""Gemeinsame Schnittstelle für Mock- und Microsoft-Graph-Anbindung."""
from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime

from ..models import BusyBlock, Employee, NotificationResult


class ProviderError(RuntimeError):
    """Fehler bei der Kommunikation mit dem Kalender- oder Benachrichtigungssystem."""


class Provider(ABC):
    name: str = "base"

    @abstractmethod
    def list_employees(self) -> list[Employee]:
        """Alle Mitarbeiter, die als Ansprechpartner in Frage kommen."""

    def get_employee(self, employee_id: str) -> Employee | None:
        return next((e for e in self.list_employees() if e.id == employee_id), None)

    @abstractmethod
    def get_busy(self, employee: Employee, start: datetime, end: datetime) -> list[BusyBlock]:
        """Belegte Zeitblöcke im Kalender des Mitarbeiters."""

    @abstractmethod
    def notify(self, employee: Employee, subject: str, html: str, text: str) -> NotificationResult:
        """Sends an email notification to the employee."""

    @abstractmethod
    def create_appointment(
        self, employee: Employee, start: datetime, end: datetime, subject: str, body_html: str
    ) -> str:
        """Legt einen Termin im Kalender des Mitarbeiters an, liefert die Event-ID."""

    def status(self) -> dict:
        return {"provider": self.name, "ready": True}
