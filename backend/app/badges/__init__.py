"""Besucherausweis: Foto + Name + ID + QR-Code → Ausdruck am Firmendrucker.

Ablauf:
  1. Roboter macht (mit Einverständnis) ein Foto und schickt es mit Namen an POST /api/badges.
  2. Das Backend legt einen Ausweis an (Ausweis-ID + zufälliger QR-Token), zeichnet die Karte
     und schickt sie per IPP an den Drucker (Konica Minolta bizhub, http://192.168.0.233/ipp).
  3. Das Foto wird nie auf die Festplatte geschrieben und nach dem Druck verworfen.
  4. Beim Checkout oder spätestens nach Ablauf der Gültigkeit werden alle Daten gelöscht.

Der QR-Code enthält keine persönlichen Daten, nur einen zufälligen Token. Beim Scannen
(GET /v/<token>) wird der Scan protokolliert; Türleser können über POST /api/access/check
prüfen, ob der Ausweis für eine Zone gilt („Zugang je nach Berechtigung“).
"""
