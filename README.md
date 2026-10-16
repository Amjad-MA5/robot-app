<p align="center">
  <a href="https://www.orionstar.com">
    <img src="docs/images/logo.png" alt="OrionStar" width="280"/>
  </a>
</p>

<h1 align="center">RobotSample</h1>

<p align="center">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License"/>
  <img src="https://img.shields.io/badge/platform-Android-brightgreen.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/minSdk-16-orange.svg" alt="minSdk"/>
  <img src="https://img.shields.io/badge/targetSdk-29-orange.svg" alt="targetSdk"/>
  <img src="https://img.shields.io/badge/Kotlin-1.7.10-purple.svg" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Java-8-red.svg" alt="Java"/>
  <img src="https://img.shields.io/badge/AGP-7.0.4-blue.svg" alt="AGP"/>
</p>

<p align="center">
  <b>语言 / Language：</b>
  <a href="#简体中文">简体中文</a> ·
  <a href="#english">English</a>
</p>

---

## 简体中文

OrionStar 机器人开放平台 Android SDK 官方示例工程。本项目演示了如何通过 `CoreService` 与 `SkillApi` 调用机器人的导航、语音、视觉、运动、充电、机械臂、电动门等核心能力，是接入 OrionStar 机器人 SDK 的最佳起点。

### 📑 目录

- [✨ 功能特性](#-功能特性)
- [🧩 功能模块](#-功能模块)
- [📂 项目结构](#-项目结构)
- [🛠️ 环境要求](#️-环境要求)
- [🚀 快速开始](#-快速开始)
- [🔌 核心 API 入口](#-核心-api-入口)
- [🔗 URL Scheme 拉起](#-url-scheme-拉起)
- [❓ 常见问题](#-常见问题)
- [📚 相关文档](#-相关文档)
- [📄 许可证](#-许可证)

### ✨ 功能特性

- 🧭 **导航定位**：单点导航、多点巡航、虚拟点定位
- 🗣️ **语音交互**：语音识别、语义理解、TTS 合成、技能注册
- 👁️ **视觉能力**：人脸检测、人形跟随、目标识别
- 🦿 **运动控制**：基础运动、原地转向、跟随模式
- 🔋 **充电管理**：自动回充、电量监控
- 🤖 **机械臂控制**：豹小秘 Pro 机型机械臂动作编排
- 🚪 **电动门控制**：舱门开闭与动作组合
- 🎙️ **音频采集**：麦克风原始音频流采集与 WAV 保存
- 📍 **引领功能**：跨地点路径引领

### 🧩 功能模块

| 模块 | Fragment | 功能说明 |
| :--- | :--- | :--- |
| 主页 | `MainFragment` | 场景入口，分发到各业务模块 |
| 引领 | `LeadFragment` | 跨地点引领、路径规划演示 |
| 运动 | `SportFragment` | 前进后退、转向、停止等基础运动 |
| 语音 | `SpeechFragment` | TTS、ASR、技能调用 |
| 视觉 | `VisionFragment` | 人脸检测、人形识别 |
| 充电 | `ChargeFragment` | 自动回充、桩点管理 |
| 定位 | `LocationFragment` | 重定位、地图与坐标 |
| 导航 | `NavigationFragment` / `NavFragment` | 单点 / 多点导航 |
| 跟随 | `BodyFollowFragment` | 人形跟随 |
| 音频 | `AudioFragment` | 麦克风原始音频采集（参见 `audio/AudioManager.java`） |
| 电动门 | `ElectricDoorControlFragment` / `ElectricDoorActionControlFragment` | 单次控制 / 动作组控制 |
| 机械臂 | `ArmControlFragment` | 机械臂动作（**仅豹小秘 Pro 支持**） |
| 失败页 | `FailedFragment` | CoreService 连接失败兜底 |
| 基类 | `BaseFragment` | 公共组件、返回栏、结果显示 |

### 📂 项目结构

```text
RobotSample/
├── app/
│   ├── libs/
│   │   └── robotservice.jar              # OrionStar SDK 核心 jar
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ainirobot/robotos/
│       │   ├── MainActivity.java         # 应用入口，处理 splash / scheme / 连接检查
│       │   ├── LogTools.java             # 日志收集工具
│       │   ├── application/
│       │   │   ├── RobotOSApplication.java   # Application 入口，连接 CoreService 并初始化 SkillApi
│       │   │   ├── ModuleCallback.java       # CoreService 底层事件回调
│       │   │   └── SpeechCallback.java       # SkillApi 语音回调
│       │   ├── audio/
│       │   │   ├── AudioManager.java         # 音频采集封装
│       │   │   └── WavHeader.java            # WAV 文件头工具
│       │   ├── fragment/                     # 各业务场景 Fragment
│       │   ├── maputils/                     # 地图、坐标、对话等工具集
│       │   └── view/                         # BackView / MapView / ResultView 公共控件
│       └── res/                              # 资源文件（layout / drawable / values 等）
├── build.gradle                              # 项目级构建脚本
├── settings.gradle
├── gradle/wrapper/
├── LICENSE                                   # Apache License 2.0
└── README.md
```

### 🛠️ 环境要求

| 项 | 版本 |
| :--- | :--- |
| JDK | **1.8（Java 8）** |
| Android Studio | 4.2 及以上 |
| Android Gradle Plugin | 7.0.4 |
| Gradle | 与 AGP 7.0.4 匹配的版本 |
| Kotlin | 1.7.10 |
| compileSdk / targetSdk | 29 |
| minSdk | 16 |
| 关键依赖 | `app/libs/robotservice.jar`（OrionStar SDK） |

### 🚀 快速开始

#### 1. 克隆项目

```bash
git clone <your-repo-url>
cd RobotSample
```

#### 2. 用 Android Studio 打开

打开 Android Studio → `Open` → 选择项目根目录 → 等待 Gradle Sync 完成。

#### 3. 构建并安装到机器人

将设备（机器人）通过 ADB 连接电脑，然后：

```bash
./gradlew installDebug
```

或在 Android Studio 中点击 `Run`。

#### 4. ⚠️ 启动方式（重要）

> **必须从机器人屏幕上点击应用图标启动**，**不要**直接点 IDE 的 `Debug` 按钮拉起，否则无法获得 `CoreService` 的 API 权限，所有机器人接口都会失败。

启动后若长时间停留在 Splash 页：通常意味着 `CoreService` 未连接，请确认机器人系统正常、SDK 服务在运行。`MainActivity#checkInit` 最多重试 10 次，超过会显示 `FailedFragment`。

### 🔌 核心 API 入口

#### 连接 CoreService

入口位于 `application/RobotOSApplication.java`：

```java
RobotApi.getInstance().connectServer(mContext, new ApiListener() {
    @Override public void handleApiConnected() {
        // 注册 ModuleCallback、初始化 SkillApi
    }
    @Override public void handleApiDisconnected() { /* 断开重连 */ }
    @Override public void handleApiDisabled()    { /* 服务被禁用 */ }
});
```

#### 注册语音回调

```java
SkillApi mSkillApi = new SkillApi();
mSkillApi.addApiEventListener(apiListener);
mSkillApi.connectApi(mContext);
// 连接成功后：
mSkillApi.registerCallBack(mSkillCallback);
```

#### 检查 API 是否可用

```java
if (RobotApi.getInstance().isApiConnectedService()
        && RobotApi.getInstance().isActive()) {
    // 可以放心调用机器人 API
}
```

### 🔗 URL Scheme 拉起

`MainActivity` 已注册 `jerry://` 方案，可由其他应用或 ADB 唤起：

```bash
adb shell am start -a android.intent.action.VIEW -d "jerry://main"
```

支持的 host：

| Scheme | 行为 |
| :--- | :--- |
| `jerry://main` | 直接进入主菜单，等同于点击 Launcher 图标 |

### ❓ 常见问题

<details>
<summary><b>Q1：从 IDE 点 Debug 启动后，所有机器人接口都没响应？</b></summary>

A：必须从机器人屏幕点击 App 图标启动。从 IDE Debug 启动会绕过授权流程，导致拿不到 `CoreService` 的 API 权限。

</details>

<details>
<summary><b>Q2：一直停在 Splash 页面进不去主界面？</b></summary>

A：`MainActivity#checkInit` 在轮询 `RobotApi#isApiConnectedService` 与 `isActive`，若 10 次后仍未就绪会跳转 `FailedFragment`。请检查：
- 机器人系统的 CoreService 是否正在运行
- 应用是否已被授予相关权限
- `robotservice.jar` 与设备系统版本是否匹配
</details>

<details>
<summary><b>Q3：机械臂相关功能调用失败？</b></summary>

A：`ArmControlFragment` 的能力**仅在豹小秘 Pro 机型上支持**，其他机型调用会失败。

</details>

<details>
<summary><b>Q4：编译时找不到 <code>robotservice.jar</code>？</b></summary>

A：检查 `app/libs/robotservice.jar` 是否存在；若仓库未携带，请从 OrionStar 开发者平台获取后放入此目录。`app/build.gradle` 中通过 `implementation files('libs\\robotservice.jar')` 引用（Windows 风格路径，macOS/Linux 同样可识别）。

</details>

### 📚 相关文档

- [OrionStar 开发者文档（中文）](https://doc.orionstar.com/)
- [OrionStar Developer Docs (English)](https://doc.orionstar.com/en/)
- [Android Developer Documentation](https://developer.android.com/)

### 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源协议发布。

```
Copyright (C) 2017 OrionStar Technology Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
```

---

## English

The official Android SDK sample project from the **OrionStar Robot Open Platform**. This project demonstrates how to access the robot's core capabilities — navigation, speech, vision, motion, charging, robotic arm, and electric door — through `CoreService` and `SkillApi`. It is the best starting point for integrating with the OrionStar Robot SDK.

### 📑 Table of Contents

- [✨ Features](#-features)
- [🧩 Modules](#-modules)
- [📂 Project Structure](#-project-structure)
- [🛠️ Requirements](#️-requirements)
- [🚀 Getting Started](#-getting-started)
- [🔌 Core API Entry Points](#-core-api-entry-points)
- [🔗 URL Scheme](#-url-scheme)
- [❓ FAQ](#-faq)
- [📚 References](#-references)
- [📄 License](#-license)

### ✨ Features

- 🧭 **Navigation & Localization**: single-point navigation, multi-point patrol, virtual point relocation
- 🗣️ **Speech Interaction**: ASR, NLU, TTS, skill registration
- 👁️ **Vision**: face detection, person tracking, target recognition
- 🦿 **Motion Control**: basic motion, in-place rotation, follow mode
- 🔋 **Charging**: auto-recharge, battery monitoring
- 🤖 **Robotic Arm**: arm motion sequences (Bao Xiaomi **Pro** only)
- 🚪 **Electric Door**: door open/close and action chains
- 🎙️ **Audio Capture**: raw mic stream and WAV file saving
- 📍 **Leading**: cross-location guided routing

### 🧩 Modules

| Module | Fragment | Description |
| :--- | :--- | :--- |
| Home | `MainFragment` | Scene entry, dispatches to feature modules |
| Lead | `LeadFragment` | Cross-location leading and path planning |
| Sport | `SportFragment` | Forward/backward, turning, stop, etc. |
| Speech | `SpeechFragment` | TTS, ASR, skill invocation |
| Vision | `VisionFragment` | Face / person detection |
| Charge | `ChargeFragment` | Auto-recharge, dock management |
| Location | `LocationFragment` | Relocation, map and coordinates |
| Navigation | `NavigationFragment` / `NavFragment` | Single / multi-point navigation |
| Body Follow | `BodyFollowFragment` | Human body follow |
| Audio | `AudioFragment` | Raw audio capture (see `audio/AudioManager.java`) |
| Electric Door | `ElectricDoorControlFragment` / `ElectricDoorActionControlFragment` | Single control / action group |
| Robotic Arm | `ArmControlFragment` | Arm actions (**Bao Xiaomi Pro only**) |
| Failure | `FailedFragment` | Fallback when CoreService connection fails |
| Base | `BaseFragment` | Common widgets, back bar, result view |

### 📂 Project Structure

```text
RobotSample/
├── app/
│   ├── libs/
│   │   └── robotservice.jar              # OrionStar SDK core jar
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ainirobot/robotos/
│       │   ├── MainActivity.java         # App entry: splash / scheme / connection check
│       │   ├── LogTools.java             # Logging utility
│       │   ├── application/
│       │   │   ├── RobotOSApplication.java   # Application entry, connects CoreService and initializes SkillApi
│       │   │   ├── ModuleCallback.java       # CoreService low-level callbacks
│       │   │   └── SpeechCallback.java       # SkillApi speech callbacks
│       │   ├── audio/
│       │   │   ├── AudioManager.java         # Audio capture wrapper
│       │   │   └── WavHeader.java            # WAV header utility
│       │   ├── fragment/                     # Feature scene Fragments
│       │   ├── maputils/                     # Map / coordinate / dialog utilities
│       │   └── view/                         # Common widgets: BackView / MapView / ResultView
│       └── res/                              # Resources (layouts, drawables, values, etc.)
├── build.gradle                              # Project-level build script
├── settings.gradle
├── gradle/wrapper/
├── LICENSE                                   # Apache License 2.0
└── README.md
```

### 🛠️ Requirements

| Item | Version |
| :--- | :--- |
| JDK | **1.8 (Java 8)** |
| Android Studio | 4.2+ |
| Android Gradle Plugin | 7.0.4 |
| Gradle | matching AGP 7.0.4 |
| Kotlin | 1.7.10 |
| compileSdk / targetSdk | 29 |
| minSdk | 16 |
| Key dependency | `app/libs/robotservice.jar` (OrionStar SDK) |

### 🚀 Getting Started

#### 1. Clone

```bash
git clone <your-repo-url>
cd RobotSample
```

#### 2. Open in Android Studio

Open Android Studio → `Open` → select the project root → wait for Gradle Sync to finish.

#### 3. Build and install on the robot

Connect the robot to your computer via ADB, then:

```bash
./gradlew installDebug
```

Or click `Run` in Android Studio.

#### 4. ⚠️ Launching (Important)

> **The app MUST be launched by tapping its icon on the robot's screen.** Do **NOT** start it via the IDE's `Debug` button — doing so bypasses the authorization flow and the app will fail to obtain the `CoreService` API permission, causing all robot APIs to fail.

If the splash screen never goes away: this usually means `CoreService` is not connected. Please verify the robot system is healthy and the SDK service is running. `MainActivity#checkInit` retries up to 10 times before showing `FailedFragment`.

### 🔌 Core API Entry Points

#### Connect to CoreService

Located in `application/RobotOSApplication.java`:

```java
RobotApi.getInstance().connectServer(mContext, new ApiListener() {
    @Override public void handleApiConnected() {
        // Register ModuleCallback and initialize SkillApi
    }
    @Override public void handleApiDisconnected() { /* reconnect */ }
    @Override public void handleApiDisabled()    { /* service disabled */ }
});
```

#### Register speech callback

```java
SkillApi mSkillApi = new SkillApi();
mSkillApi.addApiEventListener(apiListener);
mSkillApi.connectApi(mContext);
// Once connected:
mSkillApi.registerCallBack(mSkillCallback);
```

#### Check API availability

```java
if (RobotApi.getInstance().isApiConnectedService()
        && RobotApi.getInstance().isActive()) {
    // Safe to call robot APIs
}
```

### 🔗 URL Scheme

`MainActivity` registers the `jerry://` scheme, allowing other apps or ADB to launch it:

```bash
adb shell am start -a android.intent.action.VIEW -d "jerry://main"
```

Supported hosts:

| Scheme | Behavior |
| :--- | :--- |
| `jerry://main` | Open the main menu, equivalent to tapping the Launcher icon |

### ❓ FAQ

<details>
<summary><b>Q1: Robot APIs don't respond when I launch via IDE Debug.</b></summary>

A: You must launch the app from the robot's screen. Launching via IDE Debug bypasses authorization, so the app cannot obtain the `CoreService` API permission.

</details>

<details>
<summary><b>Q2: The splash screen never disappears.</b></summary>

A: `MainActivity#checkInit` polls `RobotApi#isApiConnectedService` and `isActive`. If not ready after 10 attempts, it switches to `FailedFragment`. Please check:
- Whether CoreService is running on the robot
- Whether the app has been granted the required permissions
- Whether `robotservice.jar` matches the device system version

</details>

<details>
<summary><b>Q3: Robotic arm calls fail.</b></summary>

A: `ArmControlFragment` works **only on Bao Xiaomi Pro**; calls on other models will fail.

</details>

<details>
<summary><b>Q4: Build fails because <code>robotservice.jar</code> cannot be found.</b></summary>

A: Make sure `app/libs/robotservice.jar` exists; if it isn't checked in, obtain it from the OrionStar developer platform. It is referenced in `app/build.gradle` as `implementation files('libs\\robotservice.jar')` (Windows-style path, also recognized on macOS/Linux).

</details>

### 📚 References

- [OrionStar Developer Docs (Chinese)](https://doc.orionstar.com/)
- [OrionStar Developer Docs (English)](https://doc.orionstar.com/en/)
- [Android Developer Documentation](https://developer.android.com/)

### 📄 License

This project is released under the [Apache License 2.0](LICENSE).

```
Copyright (C) 2017 OrionStar Technology Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
```
