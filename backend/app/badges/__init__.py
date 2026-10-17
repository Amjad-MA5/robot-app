"""Visitor badge: photo + name + ID + QR code -> printout on the company printer.

Flow:
  1. The robot takes a photo (only with consent) and sends it with the name to POST /api/badges.
  2. The backend creates a badge (badge ID + random QR token) and draws the card.
  3. Printing over IPP (Konica Minolta bizhub, http://192.168.0.233/ipp):
     - the robot sends the PDF to the printer itself (return_pdf=true, print=false) - needed when
       the backend runs in the cloud and cannot reach the office network, or
     - the backend prints itself when it runs inside the office network (print=true).
  4. The photo is never written to disk and is discarded after rendering.
  5. On checkout, or at the latest when the badge expires, all data is deleted.

The QR code contains no personal data, only a random token. Scanning it (GET /v/<token>) is
logged; door readers can call POST /api/access/check to see whether the badge is valid for a zone.
"""
