"""Mitarbeiterverzeichnis mit fehlertoleranter Namenssuche.

Spracherkennung liefert Namen oft leicht falsch ("Maier" statt "Meyer",
"Schmitt" statt "Schmidt"). Deshalb kombinieren wir zwei Verfahren:

* rapidfuzz: Zeichenketten-Ähnlichkeit (Tippfehler, fehlende Buchstaben)
* Kölner Phonetik: deutsches Laut-Verfahren – gleich klingende Namen
  bekommen denselben Zahlencode ("Meyer" = "Maier" = "67").
"""
from __future__ import annotations

import re
import unicodedata

from rapidfuzz import fuzz

from .models import Employee, EmployeeMatch

_HONORIFICS = {
    "herr", "herrn", "frau", "dr", "doktor", "prof", "professor", "mr", "mrs", "ms", "miss",
    "mister", "missis", "bei", "zu", "mit", "von", "termin", "den", "die", "der",
}

_UMLAUTS = str.maketrans({"ä": "a", "ö": "o", "ü": "u", "ß": "s"})


def normalize(text: str) -> str:
    """Kleinschreibung, Umlaute vereinfachen, Satzzeichen entfernen."""
    text = text.lower().translate(_UMLAUTS)
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = re.sub(r"[^a-z\s-]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def strip_honorifics(text: str) -> str:
    tokens = [t for t in normalize(text).replace("-", " ").split() if t not in _HONORIFICS]
    return " ".join(tokens)


def koelner_phonetik(word: str) -> str:
    """Kölner Phonetik – liefert einen Zahlencode für den Klang eines Wortes."""
    w = normalize(word).replace(" ", "").replace("-", "")
    codes: list[str] = []
    for i, ch in enumerate(w):
        prev = w[i - 1] if i > 0 else ""
        nxt = w[i + 1] if i + 1 < len(w) else ""
        if ch in "aeijouy":
            code = "0"
        elif ch == "h":
            continue
        elif ch == "b":
            code = "1"
        elif ch == "p":
            code = "3" if nxt == "h" else "1"
        elif ch in "dt":
            code = "8" if nxt in {"c", "s", "z"} else "2"
        elif ch in "fvw":
            code = "3"
        elif ch in "gkq":
            code = "4"
        elif ch == "c":
            if i == 0:
                code = "4" if nxt in set("ahkloqrux") else "8"
            elif prev in {"s", "z"}:
                code = "8"
            else:
                code = "4" if nxt in set("ahkoqux") else "8"
        elif ch == "x":
            code = "8" if prev in {"c", "k", "q"} else "48"
        elif ch == "l":
            code = "5"
        elif ch in "mn":
            code = "6"
        elif ch == "r":
            code = "7"
        elif ch in "sz":
            code = "8"
        else:
            continue
        codes.append(code)

    raw = "".join(codes)
    collapsed = ""
    for digit in raw:
        if not collapsed or collapsed[-1] != digit:
            collapsed += digit
    if not collapsed:
        return ""
    return collapsed[0] + collapsed[1:].replace("0", "")


def _token_score(query_token: str, name_token: str) -> float:
    score = fuzz.ratio(query_token, name_token)
    if len(query_token) >= 3 and len(name_token) >= 3:
        if koelner_phonetik(query_token) == koelner_phonetik(name_token):
            score = max(score, 92.0)
    return score


def score_name(query: str, name: str) -> float:
    q = strip_honorifics(query)
    n = normalize(name).replace("-", " ")
    if not q:
        return 0.0
    whole = fuzz.WRatio(q, n)
    q_tokens = q.split()
    n_tokens = n.split()
    per_token = [max(_token_score(qt, nt) for nt in n_tokens) for qt in q_tokens]
    token_avg = sum(per_token) / len(per_token)
    # Wenn ein einzelnes Wort genannt wird ("Frau Schmidt"), reicht ein Treffer im Nachnamen.
    return round(max(whole, token_avg), 1)


def search(employees: list[Employee], query: str, limit: int = 5, min_score: float = 60.0) -> list[EmployeeMatch]:
    scored = [
        EmployeeMatch(**e.model_dump(), score=score_name(query, e.name))
        for e in employees
    ]
    scored = [m for m in scored if m.score >= min_score]
    scored.sort(key=lambda m: m.score, reverse=True)
    return scored[:limit]
