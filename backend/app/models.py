"""Datenmodelle (Pydantic) für die REST-Schnittstelle zwischen Roboter und Backend."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class Employee(BaseModel):
    id: str
    name: str
    email: str = ""
    department: str = ""
    job_title: str = ""


class EmployeeMatch(Employee):
    # 0–100, wie gut der Name zur (evtl. fehlerhaft erkannten) Spracheingabe passt
    score: float = 0.0


class BusyBlock(BaseModel):
    start: datetime
    end: datetime
    status: str = "busy"  # busy | tentative | oof | workingElsewhere
    subject: str = ""


class Slot(BaseModel):
    start: datetime
    end: datetime


class Availability(BaseModel):
    employee: Employee
    free_now: bool
    available_today: bool
    today_slots: list[Slot] = Field(default_factory=list)
    next_slots: list[Slot] = Field(default_factory=list)


class NotificationResult(BaseModel):
    sent: bool
    channel: str = ""  # email | mock
    detail: str = ""


class ArrivalRequest(BaseModel):
    visitor_name: str
    employee_id: str
    purpose: str = ""


class ArrivalResponse(BaseModel):
    employee: Employee
    notification: NotificationResult
    # Passender Kalendereintrag rund um "jetzt" (falls lesbar), rein informativ
    appointment: Optional[BusyBlock] = None


class WalkInRequest(BaseModel):
    visitor_name: str
    employee_id: str
    purpose: str = ""


class WalkInResponse(BaseModel):
    employee: Employee
    free_now: bool
    available_today: bool
    notified: bool
    notification: Optional[NotificationResult] = None
    today_slots: list[Slot] = Field(default_factory=list)
    # Wenn heute nichts frei ist: die nächsten freien Termine an den Folgetagen
    next_slots: list[Slot] = Field(default_factory=list)


class AppointmentRequest(BaseModel):
    visitor_name: str
    employee_id: str
    start: datetime
    end: datetime
    purpose: str = ""
    visitor_email: str = ""


class AppointmentResponse(BaseModel):
    created: bool
    employee: Employee
    slot: Slot
    event_id: str = ""
    notification: Optional[NotificationResult] = None
    detail: str = ""


class AppVersion(BaseModel):
    available: bool
    version_code: int = 0
    version_name: str = ""
    download_path: str = ""
    size_bytes: int = 0
