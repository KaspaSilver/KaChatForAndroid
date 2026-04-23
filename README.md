# KaChat Android

Android port of [KaChat iOS](https://github.com/vsmirn0v/KaChat) — encrypted peer-to-peer messaging and payments on the Kaspa blockchain.

**Language:** Kotlin  
**UI:** Jetpack Compose + Material 3  
**Min SDK:** 26 (Android 8.0)  
**Architecture:** MVVM + Repository, Hilt DI

---

## Phase Status

| Phase | Description                      | Status      |
|-------|----------------------------------|-------------|
| 1     | Project scaffold & navigation    | ✅ Complete  |
| 2     | Wallet & crypto core             | ✅ Complete  |
| 3     | Networking layer (REST + gRPC)   | 🚧 In Progress |
| 4     | Messaging core (ciph_msg)        | 🔲 Planned   |
| 5     | Chat UI                          | 🔲 Planned   |
| 6     | Voice messages                   | 🔲 Planned   |
| 7     | Push notifications (FCM)         | 🔲 Planned   |
| 8     | Multi-device sync                | 🔲 Planned   |
| 9     | Polish & Play Store release      | 🔲 Planned   |

---

## Project Structure

```
app/src/main/java/com/kachat/app/
├── KaChatApplication.kt       # Hilt application class
├── MainActivity.kt            # Single activity
├── ui/
│   ├── KaChatApp.kt           # Root composable + navigation
│   ├── theme/
│   │   ├── Theme.kt           # Material 3 color scheme (Kaspa brand)
│   │   └── Typography.kt
│   └── screens/
│       ├── OnboardingScreen.kt
│       ├── ChatsScreen.kt
│       └── Screens.kt         # ChatThread, Contacts, Wallet, Settings
├── viewmodels/
│   └── SettingsViewModel.kt
├── services/
│   ├── WalletManager.kt       # Keystore-backed key management
│   ├── KaspaApiClient.kt      # Retrofit API interfaces
│   └── database/
│       └── KaChatDatabase.kt  # Room database
├── repository/
│   └── AppSettingsRepository.kt  # DataStore wrapper
├── models/
│   └── Models.kt              # Room entities + in-memory models
├── di/
│   └── AppModule.kt           # Hilt DI providers
└── util/
    └── MessageProtocol.kt     # ciph_msg encode/decode
```

---

## Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK 35

### Build

```bash
# Open in Android Studio
# OR build from command line:

./gradlew assembleDebug

# Run tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

### First Run
The app will show the **onboarding screen** since no wallet exists yet.  
Wallet creation is implemented in Phase 2.  
To skip onboarding during UI development, set `hasWallet = true` in `KaChatApp.kt`.

---

## Architecture Notes

### iOS → Android mapping

| iOS                        | Android                        |
|----------------------------|--------------------------------|
| SwiftUI                    | Jetpack Compose                |
| `@EnvironmentObject`       | Hilt `@Inject` + ViewModel     |
| Core Data + CloudKit       | Room + (Phase 8: Drive/Firebase) |
| Secure Enclave             | Android Keystore (TEE/StrongBox) |
| UserDefaults               | DataStore Preferences          |
| URLSession                 | Retrofit + OkHttp              |
| gRPC Swift                 | grpc-kotlin                    |
| `*.lproj` localization     | `res/values-*/strings.xml`     |

### Key design decisions
- **Single Activity** — all screens are Compose composables, navigation via NavHost
- **Hilt** for DI — mirrors the `@EnvironmentObject` singleton pattern from iOS
- **Room** for local storage — entities mirror iOS Core Data models
- **DataStore** for settings — typed, coroutine-friendly, replaces SharedPreferences
- **Android Keystore** for key security — hardware-backed, equivalent to Secure Enclave

---

## Protocol Reference

See the iOS repo for protocol documentation:
- [MESSAGING.md](https://github.com/vsmirn0v/KaChat/blob/main/MESSAGING.md) — ciph_msg protocol
- [POOLS_v2.md](https://github.com/vsmirn0v/KaChat/blob/main/POOLS_v2.md) — node pool architecture
- [PUSH_NOTIFICATIONS.md](https://github.com/vsmirn0v/KaChat/blob/main/PUSH_NOTIFICATIONS.md) — push design
