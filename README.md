# Aarogya

An Android app for tracking **calories in vs. calories out**, with a daily analysis of the difference.
Food can be logged from photos, typed descriptions or a built-in food list; calories burned come from
WHOOP, a base-burn estimate, or activities you log yourself. Much of the AI runs on the phone.

## Features

**Logging food**
- **Photos**: Gemini lists every food in the photo with a portion and calorie estimate. Offline or
  when Gemini is off, on-device models suggest dishes instead (a ~2,000-dish recognizer and SmolVLM).
- **Text**: "2 rotis and a bowl of dal" is split into items instantly; SmolLM2 or Gemini handles
  descriptions the rule parser can't place.
- **Food list**: ~250 common foods, including many Indian and Mexican dishes.
- **Quantities in any unit**: the food's own unit (taco, roti, fillet), weight (g, kg, oz, lb) or
  volume (ml, L, fl oz, cup, tbsp, tsp).
- **Background queue**: save without waiting; unclassified entries are retried up to 5 times, then
  offered as on-device suggestions or a "describe it" box.

**Tabs**
- **Day**: calories left, in / out / net, a daily analysis, and the day's meals and activities.
- **Foods**: every food ever logged, with search, edit, bulk delete, full-screen photos, and a tag for
  how each was classified (Gemini / LLM / Manual).
- **Weight**: weight in kg from the Google Health API, behind a 4-digit PIN.
- **Trends**: *This week* (7-day chart and daily balance) or *Overall* (weekly in/out, balance,
  day-of-week, food types, meals, top foods).
- **Chat**: ask about your data. Common questions are answered exactly from your numbers; anything
  else goes to SmolLM2 on the phone.

**Also**
- WHOOP sync on every app open for measured daily burn.
- Meal reminders that only fire if that meal isn't logged yet.
- One tracking time zone (default US Central) so travel doesn't shift days.
- Status dots for WHOOP and Google Health next to the app name.

## Building

Requirements: Android Studio with its bundled JDK, Android SDK 37, a device or emulator on Android 8.0+
(`minSdk 26`).

```sh
./gradlew :app:assembleDebug          # build
./gradlew :app:installDebug           # install on a connected device
./gradlew :app:testDebugUnitTest      # unit tests
```

Debug builds include `arm64-v8a` and `x86_64`; release builds are `arm64-v8a` only to keep the APK
small (ONNX Runtime's native library is ~34 MB per ABI).

## Configuration

Secrets go in `local.properties` (git-ignored, never committed). Every key is optional; the related
feature shows setup instructions in Settings until it's configured.

```properties
GEMINI_API_KEY=...            # Google AI Studio key, for photo/text analysis
GEMINI_MODEL=gemini-3.8-flash # optional; falls back to gemini-3.5-flash-lite when overloaded
WHOOP_CLIENT_ID=...
WHOOP_CLIENT_SECRET=...
```

Keys in `local.properties` are compiled into the app, so anyone with the APK can extract them. That's
fine for personal use; a public release should call these APIs through a small backend instead.

### WHOOP
Create an app at [developer-dashboard.whoop.com](https://developer-dashboard.whoop.com) with redirect
URI `aarogya://whoop-callback` and scopes `read:cycles`, `read:workout`, `offline`. New WHOOP apps are
limited to 10 connected users until approved.

### Google Health (weight)
Sign-in uses Android's built-in Google authorization, so no secret ships in the app. In your Google
Cloud project:
1. Enable the **Google Health API**.
2. Add the scope `https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly`.
3. Create an **Android** OAuth client with package `com.example.aarogya` and your signing key's SHA-1
   (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
4. Keep the app in *Testing* and add your account as a test user (Google Health scopes are
   restricted; public apps need Google's security review).
5. Sign in to the Google Health app on the phone once, then **Weight → Connect Google Health**.

`UNREGISTERED_ON_API_CONSOLE` during sign-in means the package name or SHA-1 doesn't match the
Android client.

### On-device models
Downloaded from Settings on demand (not bundled):

| Model | Size | Used for |
|---|---|---|
| SmolLM2 360M / 135M (ONNX int8) | 365 / 137 MB | meal text parsing, chat |
| SmolVLM 256M (ONNX int8) | 263 MB | describing plate photos |
| AIY food classifier (TFLite) | 21 MB | naming dishes in photos |

## Project structure

```
app/src/main/java/com/example/aarogya/
├── data/          SQLite store, food table, parser, units, analysis, chat answers, PIN, time zone
├── llm/           BPE tokenizer, SmolLM2 and SmolVLM ONNX runners, model downloads
├── vision/        photo storage and ML Kit labeling
├── gemini/        Gemini REST client (photo and text → foods with calories)
├── classify/      background classification queue (WorkManager)
├── whoop/         WHOOP OAuth and sync
├── googlehealth/  Google Health weight sync
├── reminders/     meal reminder alarms and notifications
├── ui/            Compose screens and components
├── MainActivity.kt, MainViewModel.kt, AarogyaApp.kt
app/src/main/assets/foods.csv   nutrition table (kcal per 100 g, serving size)
```

Built with Kotlin, Jetpack Compose (Material 3), plain SQLite, ONNX Runtime, ML Kit and WorkManager.

## Limitations

- Calories are estimates. Food-list values assume typical home preparation; Gemini's are its own
  estimates. Volume units assume about 1 g per ml, and a cup is 240 ml.
- The on-device LLMs are small: reliable for naming foods and amounts, not for calorie numbers or
  advice. Chat answers from SmolLM2 are marked "may be inaccurate".
- Photos sent to Gemini leave the device; the Settings switch turns this off.
- The app tracks calories only, not macronutrients.
- The package name is still the template's `com.example.aarogya`.
