# Smart Receptionist for Norra

An Android reception app for the Orionstar GreetingBot Nova robot, with a Python backend.
Norra, the robot, is the receptionist of Founders Foundation (Hinterland Hack project). She greets visitors, checks them in, notifies the host, prints a visitor badge, and walks people to places on the robot's map.

- **Robot app:** Kotlin and Jetpack Compose, in `android/`.
- **Backend:** Python and FastAPI, in `backend/`.
- **Languages:** German and English, with a DE | EN switch on screen and automatic language detection.

## What Norra can do

| Feature | What happens |
|---|---|
| **Check-in** | The first question is "How can we help you?". The visitor picks a button or just speaks: appointment, walk-in, book an appointment, delivery, or "Where can I find …?". |
| **Appointment** | Norra asks for the visitor's name and the host, notifies the host, and walks the visitor to the waiting room. |
| **Walk-in** | The backend checks whether the host is free and tells the host. If not, Norra offers to book a time. |
| **Booking** | The backend suggests free slots from the host's calendar and creates the calendar event. |
| **Delivery** | Norra records the carrier, takes a photo of the package, and notifies the recipient. The backend sends a reminder if it is not collected. |
| **Guide** | Norra walks visitors to any point on the robot's map, such as restroom, exit or waiting room. If a visitor asks for another place on the way, Norra makes the detour and then continues. |
| **Visitor badge** | Photo, name, host and QR code. The robot prints it itself over IPP, with a local fallback. |
| **Waiting-room story** | Norra offers a short story about Founders Foundation while the visitor waits. The visitor can stop it at any time. |
| **Employee list** | Say "show the employee list" or tap the button. |
| **Free chat** | Short answers to off-topic questions, then back to check-in. |
| **Visitor log** | Admin screen: search visits by visitor or host, today or all days. |

## How it fits together

```
Visitor <-> Robot (Android app) --HTTP--> Backend (FastAPI) --> Microsoft 365 / email
                |
                +--> AgentOS: speech (TTS/ASR), language model
                +--> RobotAPI: map points, navigation
                +--> Printer (IPP, over the office network)
```

- The app talks to the backend through `net/BackendClient.kt`, with an API key header.
- Speech understanding first asks the AgentOS language model. If that fails or is switched off, keyword rules are used (`voice/RuleNlu.kt`, `guide/`).
- Every spoken line is fixed text in `voice/Lines.kt` and `guide/GuideLines.kt`. Only free chat lets the language model write a reply.

## Quick start

### 1. Run the backend

```bash
cd backend
bash start.sh
```

The first start creates a virtual environment, installs the packages and copies `.env.example` to `.env`. The backend then runs on port **8080** and prints its address, for example `http://192.168.0.50:8080`.

- Open that address in a browser for the dashboard.
- Check `http://<laptop-ip>:8080/health`. It should return `{"status":"ok", ...}`.
- On Windows, use `start.bat`.

The robot must be on the **same Wi-Fi** as the laptop. If the robot cannot connect, check the laptop firewall for port 8080.

### 2. Install the app on the robot

**Option A: download the build from GitHub.** Pushing to `main` runs the *Build APK* workflow. Download the file from
`https://github.com/<user>/<repo>/releases/download/latest/smart-receptionist.apk`.

**Option B: build it yourself.**

```bash
cd android
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is in `android/app/build/outputs/apk/debug/`. Install it with `adb install -r <file>.apk`.

Requirements: JDK 17 and the Android SDK. The app targets API 34 and needs at least API 26.

### 3. Configure the app

Long-press the **top-left corner** of the start screen, then enter the PIN (default `1234`; change it in Admin). Set at least:

1. **Backend URL** to the laptop address from step 1, and the **API key** to the same value as `SR_API_KEY` in `backend/.env`.
2. **Waiting room** and **Reception** to points on the robot's map. The dropdowns fill automatically once the robot has connected.
3. **Printer URL**, if you use the badge printer.

Press **Save**.

## Settings in the Admin screen

| Section | Setting | Notes |
|---|---|---|
| Backend | Backend URL, API key | Must match the backend. |
| Robot & map | Waiting room, Reception point | Names of points saved on the robot's map. |
| Robot & map | Test buttons | Drive to a point to check navigation. |
| Visitor badge and camera | Printer URL, camera source and rotation, test print | Default printer URL is `http://192.168.0.233/ipp`. |
| Conversation | Language, robot name, company name, admin PIN | Defaults: English, "Norra", "Founders Foundation". |
| Conversation | Use AgentOS language model | Turn off to use only keyword rules. |
| Conversation | Free chat | Needs the language model. |
| Conversation | Waiting-room story and custom text | Separate paragraphs with a blank line. |
| Visitor log | Search, "Today only", Load visitor log | Reads from the backend. |
| Log | Recent actions | Useful for debugging. |

## Backend configuration (`backend/.env`)

Start from `backend/.env.example`; it documents every setting. The important ones:

| Variable | Purpose |
|---|---|
| `SR_API_KEY` | Shared secret between app and backend. |
| `SR_MODE` | `mock` for demo employees and calendars, `graph` for real Microsoft 365. |
| `SR_COMPANY_NAME` | Shown on screen and in messages. |
| `SR_LANGUAGE` | Language of notifications to employees: `en` or `de`. |
| `SR_SMTP_*` | Email delivery. Without a host, messages are only simulated and appear in the dashboard inbox. |
| `SR_GRAPH_*` | Microsoft Graph sign-in (directory and calendar). |
| `SR_PRINTER_*` | Backend-side printing. Normally the robot prints itself. |
| `SR_PUBLIC_URL` | Address placed in badge QR codes. |
| `SR_VISIT_LOG` | Visitor log file (default `data/visits.jsonl`). |

Keep credentials in `.env` or the host's secrets. `.env` is ignored by git.

## Voice commands and examples

- "I have an appointment with Anna Schmidt."
- "Where is the restroom?" / "Wo ist die Toilette?"
- "Show the employee list." / "Zeige die Mitarbeiterliste."
- While being escorted: "Where is the restroom?" Norra takes the detour and then continues.
- Say stop, or tap the stop button, to cancel driving or the story.

## Data and privacy

- Badges are valid for 8 hours by default (`SR_BADGE_VALID_HOURS`) and the data is deleted afterwards.
- `backend/data/badges.json` and `backend/data/visits.jsonl` hold personal data and are ignored by git.
- Badge photos are never printed with the Hinterland background logos.
- Free chat answers only from the story text and never invents facts about the company.

## Tests

```bash
# Android unit tests
cd android && bash gradlew :app:testDebugUnitTest

# Backend tests
cd backend && .venv/bin/python -m pytest -q
```

The Android tests cover the language detection, keyword rules, guide and detour logic, the story and the chat cleanup. The AgentOS parts (speech, language model, navigation) can only be tested on a real robot.

## Project layout

```
android/app/src/main/java/de/hinterland/smartreceptionist/
  flow/      ReceptionViewModel (the conversation state machine), UiState
  voice/     Lines (texts), RuleNlu, Understanding (LLM + rules), LanguageDetector, Chat
  guide/     Places, GuideLines, Detour, Navigator
  badge/     IPP printer, camera, local badge
  story/     Waiting-room story
  robot/     RobotController (RobotAPI: map, navigation)
  net/       BackendClient, AppUpdater
  ui/        ReceptionScreen, AdminScreen, RobotFace, theme
  config/    AppSettings
backend/app/
  main.py    API: employees, visits, packages, appointments, visitor log
  badges/    Badge API, rendering, printer, QR pass page
  providers/ mock, Microsoft Graph, SMTP email
render.yaml  Optional deployment on Render
.github/workflows/build-apk.yml  APK build on GitHub
```

## Known limits

- Detours, microphone use while driving, the story, badge printing and German speech were not yet tested on the real robot. German speech output depends on the robot's system language.
- Visitors' phones cannot open the badge QR code unless the backend has a public HTTPS address. This is the main blocker for the digital pass.
- On Render's free plan the disk is wiped on each redeploy, so badges and the visitor log are lost.
- There is no check-out yet, so the visitor log has no departure times.

## Planned next

The digital QR pass and visitor check-out: a public address for the backend (Cloudflare Tunnel for the hackathon, Render with persistent storage later), a pass page with a PDF download and a check-out button, and the check-out time in the visitor log.
