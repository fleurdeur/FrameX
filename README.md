# FrameX

![GitHub stars](https://img.shields.io/github/stars/fleurdeur/FrameX?style=for-the-badge&logo=github) ![GitHub forks](https://img.shields.io/github/forks/fleurdeur/FrameX?style=for-the-badge&logo=github) ![GitHub issues](https://img.shields.io/github/issues/fleurdeur/FrameX?style=for-the-badge&logo=github) ![Last commit](https://img.shields.io/github/last-commit/fleurdeur/FrameX?style=for-the-badge&logo=github)

## 📑 Table of Contents

- [Description](#description)
- [Screenshots](#screenshots)
- [Tech Stack](#tech-stack)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Deployment](#deployment)
- [Contributors](#contributors)
- [Contributing](#contributing)

## 📝 Description

editing app

## 🛠️ Tech Stack

![Android (Native)](https://img.shields.io/badge/Android%20(Native)-3DDC84?style=for-the-badge&logo=android&logoColor=white) ![Java (Gradle)](https://img.shields.io/badge/Java%20(Gradle)-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)

## ⚡ Quick Start

```bash

# 1. Clone the repository
git clone https://github.com/fleurdeur/FrameX.git

# See the Development Setup section below
```

## 📁 Project Structure

```
.
├── .aistudio
│   └── artifacts
│       └── brain
│           └── 590f5abc-e593-4bb6-8295-1f0d39768b04
│               └── .tempmediaStorage
│                   └── ...
├── .env.example
├── app
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src
│       ├── androidTest
│       │   └── java
│       │       └── com
│       │           └── ...
│       ├── main
│       │   ├── AndroidManifest.xml
│       │   ├── java
│       │   │   └── com
│       │   │       └── ...
│       │   └── res
│       │       ├── drawable
│       │       │   └── ...
│       │       ├── font
│       │       │   └── ...
│       │       ├── mipmap-anydpi-v26
│       │       │   └── ...
│       │       ├── mipmap-hdpi
│       │       │   └── ...
│       │       ├── mipmap-mdpi
│       │       │   └── ...
│       │       ├── mipmap-xhdpi
│       │       │   └── ...
│       │       ├── mipmap-xxhdpi
│       │       │   └── ...
│       │       ├── mipmap-xxxhdpi
│       │       │   └── ...
│       │       ├── values
│       │       │   └── ...
│       │       └── xml
│       │           └── ...
│       └── test
│           └── java
│               └── com
│                   └── ...
├── build.gradle.kts
├── depth-engine
│   ├── build.gradle.kts
│   └── src
│       └── main
│           ├── cpp
│           │   └── depth_anything_jni.cpp
│           └── java
│               └── com
│                   └── ...
├── firebase-blueprint.json
├── firebase.json
├── firestore.rules
├── firestore.test.js
├── gradle
│   ├── libs.versions.toml
│   └── wrapper
│       └── gradle-wrapper.properties
├── gradle.properties
├── metadata.json
├── public
│   └── assets
│       └── aistudio
│           ├── KeyEase_38e4fe05.zip
│           ├── KeyEase_Editor_Upgrade_Agent_Instructions_short_9ac66ef7.txt
│           ├── New_Project_25_d896cc7c.xml
│           ├── PLUGIN_MAKER_GUIDE_88f67588.txt
│           ├── PROMPT_TEMPLATE_FOR_AI_eb3ce931.txt
│           ├── Screenshot_20261102-154532_6288e7f1.jpg
│           ├── Screenshot_20261102-154541_fe9a5163.jpg
│           ├── Screenshot_20261102-154550_a40757ac.jpg
│           ├── Screenshot_20261102-154552_87886b99.jpg
│           ├── Textevo_2026.1.0_44c54978.zip
│           ├── image_4dc1df4e.png
│           └── rena_-_copines___1__a27650f7.xml
├── roto-engine
│   ├── build.gradle.kts
│   └── src
│       └── main
│           ├── cpp
│           │   └── roto_flow_jni.cpp
│           └── java
│               └── com
│                   └── ...
├── script-engine
│   ├── build.gradle.kts
│   └── src
│       └── main
│           ├── assets
│           │   └── ae_text_animator_mapping.json
│           ├── cpp
│           │   └── extendscript_jni_stub.cpp
│           └── java
│               └── com
│                   └── ...
├── security_spec.md
└── settings.gradle.kts
```

## 🚢 Deployment

### Firebase Hosting

`firebase deploy`

## 👥 Contributors

Thanks to everyone who has contributed to this project:

<p align="left">
<a href="https://github.com/fleurdeur" title="fleurdeur"><img src="https://avatars.githubusercontent.com/u/319274093?v=4&s=64" width="64" height="64" alt="fleurdeur" style="border-radius:50%" /></a>
</p>

[See the full list of contributors →](https://github.com/fleurdeur/FrameX/graphs/contributors)

## 👥 Contributing

Contributions are welcome! Here's the standard flow:

1. **Fork** the repository
2. **Clone** your fork: `git clone https://github.com/fleurdeur/FrameX.git`
3. **Branch**: `git checkout -b feature/your-feature`
4. **Commit**: `git commit -m 'feat: add some feature'`
5. **Push**: `git push origin feature/your-feature`
6. **Open** a pull request

Please follow the existing code style and include tests for new behavior where applicable.

---

<div align="center">

[![Made with ReadmeBuddy](https://img.shields.io/badge/Made%20with-ReadmeBuddy-8B5CFF?style=for-the-badge&logo=markdown&logoColor=white)](https://readmebuddy.com)

<sub>Generate beautiful READMEs in seconds → <a href="https://readmebuddy.com">readmebuddy.com</a></sub>

</div>
