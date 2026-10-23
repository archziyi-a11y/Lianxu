# Project Plan

Fitness AI Assistant MVP based strictly on the provided specification package.

## Project Brief

# Project Brief: Fitness AI Assistant MVP

## Features
1. **Dynamic AI Dashboard (Graphical Chat):** A fluid, 6-column grid interface where the AI dynamically updates, adds, removes, and combines cards. These cards represent daily nutrition goals, food portions, and training references, serving as both user input and assistant responses.
2. **Multi-Card Annotation & Correction:** Users can long-press a card to enter annotation mode (tap for text, swipe up for image), triggering a visual change (border/theme color). A bottom rectangular arrow submits all annotated cards as a batch for the AI to process contextually.
3. **Long-Term Archive & Profiling:** A dedicated second page for managing persistent data—body profiles, goals, historical records, and phase evaluations. This is not just a history list, but a shared data source that continuously influences the AI's ongoing analysis.
4. **Lifecycle Animations & Daily Reset:** A clear interaction model where sending data animates the current cards upwards (send animation), and the AI's response animates in from the bottom. The day's board remains active for multiple updates and is only formally archived at the user's local midnight.
5. **AI Protocol & Demo Mode:** Implements strict AI tool protocols (annotation anchoring, card IDs, version snapshots) to ensure accurate context parsing. An explicitly marked "Demo Mode" guarantees local recording and calculation continue functioning even without an AI API key.

## High-Level Tech Stack
* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (using Bungie's *Marathon* graphical and typographic design language principles)
* **Navigation & Adaptive Strategy:** **Jetpack Navigation 3** (state-driven) and the **Compose Material Adaptive** library for all layouts.
* **Concurrency & State Management:** Kotlin Coroutines and StateFlow (for robust MVI/MVVM state-driven UI).
* **Local Persistence:** Room Database & Preferences DataStore (Required to satisfy the strict "data persistence and failure recovery" and offline Demo Mode requirements).
* **Network & API:** Ktor or Retrofit (for AI Agent API communication when an API key is provided).

## Implementation Steps

### Task_1_SetupNavigationTheme: Set up Marathon-style theme, typography, high-contrast colors, and the minimalist dual-page swipeable architecture using Jetpack Compose HorizontalPager (Today and Archive screens).
- **Status:** COMPLETED
- **Updates:** Successfully set up Compose dependencies, Marathon Theme (colors, typography), custom bottom dash/dot indicator, and the HorizontalPager dual-page architecture for Today and Archive. The app builds successfully.
- **Acceptance Criteria:**
  - Marathon theme (high contrast, neon accents) applied
  - HorizontalPager functioning for Today and Archive screens with dot/dash indicators

### Task_2_LocalDataCalculation: Implement Room Database for local persistence of body profiles, historical records, and daily cards. Implement the local calculation engine for nutrition and goals.
- **Status:** COMPLETED
- **Updates:** Successfully set up Room DB schemas (Profile, GoalPhase, KnowledgeNote, etc.), implemented a local calculation engine for BMR/TDEE and macronutrients, and built the Archive UI to allow viewing and editing of the local profile independently of AI. The app builds without errors.
- **Acceptance Criteria:**
  - Room DB setup with schemas for profiles and cards
  - Local calculation engine processes and returns valid nutrition data
  - Archive page can view and edit local profiles independently of AI

### Task_3_GridDashboardAnimations: Build the interactive 6-column grid dashboard for dynamic cards on the Today screen, including lifecycle animations (send up, receive bottom).
- **Status:** COMPLETED
- **Updates:** Successfully implemented the 6-column `LazyVerticalGrid` supporting 1/3, 1/2, and full-width spans. Created Marathon-styled `DashboardCardItem` with neon borders. Implemented the send (slide up) and receive (slide in from bottom) animations using `AnimatedVisibility`. App builds successfully.
- **Acceptance Criteria:**
  - 6-column grid displays 1/3, 1/2, and full-width cards correctly
  - Send (animates upwards) and receive (animates from bottom) animations implemented

### Task_4_AnnotationAIIntegration: Implement multi-card annotation flow (long press to select, batch submit) and DeepSeek API integration with tool-calling protocol and a graceful Demo mode.
- **Status:** COMPLETED
- **Updates:** Successfully implemented multi-card annotation flow: long press for annotation mode, tap for text, swipe up for image. Added a batch submission bottom button. Implemented DeepSeek API integration with Retrofit and a `DataStore`-backed API Key configuration. Added Demo Mode fallback to simulate responses. App builds successfully.
- **Acceptance Criteria:**
  - Long-press card annotation (text/image) with visual state change works
  - Batch submission bottom arrow triggers network call
  - DeepSeek API_KEY integration implemented with fallback to Demo Mode if key is missing

### Task_5_RunAndVerify: Final assembly and verification. Instruct critic_agent to verify application stability, confirm alignment with Marathon aesthetic, and test Demo/AI modes.
- **Status:** COMPLETED
- **Updates:** Verification successful! The app deployed, ran, and functioned perfectly without crashes. The two-page layout, dot/dash indicator, Marathon aesthetic, and 6-column grid are all verified. The multi-card batch annotation flow (long press to select, tap text, swipe up image) works as expected. Demo mode also functions correctly. The critic suggested making annotation single-selection, but batch selection is actually the intended correct behavior based on the spec.
- **Acceptance Criteria:**
  - make sure all existing tests pass
  - build pass
  - app does not crash
  - Demo Mode works flawlessly without API key
- **Duration:** N/A

