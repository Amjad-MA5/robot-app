# Wayfinder – guide robot for event visitors

A separate Android app for the Orionstar robot (AgentOS SDK 0.4.12). It does not share code, settings or the
package name with the Smart Receptionist app in `android/`, so both can be installed side by side.

**What it does**

- Shows a few **room tiles** on the screen. Visitors tap one or simply ask: *"Where are the restrooms?"*,
  *"Take me to the main stage"*, *"Wo ist die Toilette?"*.
- A room on the robot's floor with a map point: the robot says *"Please follow me"* and **walks the visitor there**
  (big STOP button, *"stop"* also works by voice).
- A room on **another floor**: the robot says which floor it is on, shows big floor badges and, if a stairs/elevator
  point is set, offers to walk the visitor to the stairs.
- **Restrooms** are preset: *"The Restrooms are on the first floor, one floor up, or in the basement, one floor down."*
- When nobody needs help, the robot **wanders** between the points of its map to show presence, pauses at every
  point and invites people nearby. Any touch, a question, or a person stepping in front of it stops the tour at once.
- Free questions and small talk go to the AgentOS language model (short answers, no invented facts).

## Install

1. Download the APK: `https://github.com/Amjad-MA5/robot-app/releases/download/wayfinder-latest/wayfinder.apk`
   (built by the *Build Wayfinder APK* workflow on every push that changes `wayfinder/`),
   or build it yourself (see below).
2. `adb connect <robot-ip>:5555` and `adb install -r wayfinder.apk`
3. Start **Wayfinder** from the RobotOS home screen (starting it with adb or Android Studio can leave the RobotAPI
   without chassis permission). To start it after boot: three-finger swipe down → Settings → Developer settings →
   Startup program → Wayfinder.

Without a robot (emulator, tablet) the app runs in **demo mode** with a simulated map, so the screens can be tried anywhere.

## Set up on site (5 minutes)

1. With the robot's **map tool**, save a point for every place the robot should walk to (e.g. `Main Stage`,
   `Workshop Room`, `Coffee Bar`, `Exit`, `Stairs`). Point names are shown on the screen, so name them nicely.
2. Open Wayfinder, **long-press the top-left corner**, PIN **1234**.
3. In the admin screen:
   - **Rooms on the screen**: by default every map point becomes a tile, plus "Restrooms" (floors 1 and −1).
     Hide points that visitors do not need (e.g. `Reception`). Add rooms that are not on the map (other floors) with
     their floors, e.g. `Room 2.04` on floor `2`, and a spoken direction if useful.
   - **Floors and special points**: the floor the robot is on (0 = ground floor), the stairs/elevator point,
     the home point (default: the RobotOS reception point).
   - **Wandering**: on/off, which points (uncheck points inside presentation rooms!), pause, speed.
   - **Test**: drive to a point, or type a sentence as if a visitor said it.
4. **Save**. The robot starts wandering after 45 s without visitors.

## How it behaves

| Situation | Behaviour |
|---|---|
| Tap or ask for a room on this floor | "Please follow me" → drives there at 0.5 m/s → "Here we are" → waits 12 s → continues its tour (or goes back to the home point when wandering is off) |
| Room on another floor | Floor answer on screen and spoken; with a stairs point: "Shall I walk you to the stairs?" ("yes" or the button) |
| Room without map point on this floor | Speaks the configured directions |
| Nobody around for 45 s | Wanders the route, pauses 20 s at each point, invites people nearby (at most every 3 min) |
| Someone stands in front of it (1.3 m) | Stops and says hello |
| Touch, question, STOP button, "stop" | Stops the wheels immediately |
| Way blocked | "Excuse me, may I pass through?"; after the 30 s obstacle timeout it apologises (guiding) or skips the point (wandering; a point that fails twice is skipped for 5 minutes) |
| Robot not localized / RobotAPI not connected | Does not drive, still answers with floors and directions; banner for the staff |
| Battery below 20 % | No more wandering; below 12 % it drives to the charging pile |
| Emergency stop, charging, OTA (RobotOS takes over) | Everything stops, "taking a short break" screen, continues afterwards |
| Back key | Never leaves the app; stops the robot or closes the card |

## Voice

Understood without network or language model (keyword rules, fuzzy matching of room names, English and the common
German phrases): room requests, *stop*, *yes/no*, *thanks*, *hello*, *bye*, *which rooms are there?*.
Everything else goes to the AgentOS language model (`AgentCore.llmSync`, not streamed) which picks a room, answers
small talk using only the "event facts" from the admin screen, or ignores speech not meant for the robot.
The robot ignores its own voice (echo filter). Spoken lines are in `core/Lines.kt`.

## Build and test

```bash
cd wayfinder
bash gradlew :app:testDebugUnitTest :app:assembleDebug   # JDK 17+ and the Android SDK
```

The APK is in `app/build/outputs/apk/debug/`. Debug and release builds are signed with the demo key in `keystore/`,
so newer builds install over older ones with `adb install -r`.

The unit tests cover the speech rules, rooms and floors, settings, the language-model answers and the whole
behaviour (guiding, wandering, stop, failures, system takeover, battery, greeting) with a fake robot and virtual time.

## Project layout

```
app/src/main/java/de/hinterland/wayfinder/
  core/   Rooms (room list from settings + map), Floors, Matcher (speech rules), Lines (all texts),
          Settings (JSON), WanderPlanner, EchoFilter, Brain (language-model prompt), PlaceParser, AppLog
  flow/   GuideController (the behaviour, Android-free), UiState, GuideViewModel, SettingsStore
  robot/  Robot interface, RealRobot (RobotAPI: map, navigation, faces, battery, charging),
          SimulatedRobot, Voice (AgentOS TTS), AgentBrain (AgentOS LLM)
  ui/     MainScreen (tiles, guiding, floor card), RobotFace, AdminScreen, PinDialog, theme
  MainActivity (PageAgent, kiosk), WayfinderApp (AppAgent)
```

## Notes

- RobotAPI rules followed: only the foreground app gets the chassis, `onSuspend`/`onRecovery` pause and resume,
  each listener overrides exactly one callback variant (the SDK calls both), a new drive is retried while the
  previous one is still stopping, special RobotOS points (charging pile, positioning point) are never offered.
- `assets/actionRegistry.json` uses the same AgentOS appId as the Smart Receptionist. Replace it if you apply for
  a separate one in the AgentOS backend.
- Speech output, recognition and navigation can only be verified on the real robot; everything else is covered by
  the unit tests and the demo mode.
