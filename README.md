# Vi-Flow

A JavaFX-based desktop application for photo management, digital image processing, and multimedia synthesis. Built as a group project for WIG3003 Multimedia Programming (2026).

---

## Team

| Name | Role | GitHub |
|---|---|---|
| Yirou | GUI & Navigation Lead, Repo Owner |
| Emily | DIP — Radiometric & Aesthetic |
| Chyntia | DIP — Geometric & Object Extraction | 
| Winnie | Multimedia Synthesis |
| Sam | Social Integration, App Entry Point, Report Lead |

---

## Tech Stack

| Component | Choice | Version |
|---|---|---|
| Language | Java (OpenJDK) | 11+ |
| GUI Framework | JavaFX | 21 LTS |
| Image Processing | OpenCV (openpnp) | 4.9.0-0 |
| Email | Jakarta Mail | 2.0.1 |
| Build Tool | Apache Maven | 3.9.x |

Full library justifications and API reference: [`docs/TechStack_v1.0.pdf`](docs/TechStack_v1.0.pdf)

---

## Prerequisites

- **JDK 11 or higher** — [Download OpenJDK](https://adoptium.net/)
- **Apache Maven 3.9.x** — [Download Maven](https://maven.apache.org/download.cgi)
- **No manual OpenCV installation needed** — the openpnp wrapper bundles native libraries inside the JAR and loads them automatically
- **Gmail App Password** (for the email feature only) — standard Gmail passwords will not work with SMTP. Generate one at: Google Account → Security → 2-Step Verification → App Passwords. See [Google's guide](https://support.google.com/accounts/answer/185833)

---

## Getting Started

```bash
# 1. Clone the repository
git clone https://github.com/<org>/WIG3003-PhotoApp.git
cd WIG3003-PhotoApp

# 2. The data/ directory is gitignored and created automatically on first run.
#    If it is not, create it manually:
mkdir -p data/annotations data/output

# 3. Build and run
mvn clean javafx:run
```

---

## Project Structure

```
WIG3003-PhotoApp/
├── pom.xml
├── src/main/
│   ├── java/com/wig3003/photoapp/
│   │   ├── app/          → Sam      — entry point, OpenCV init, FXML wiring
│   │   ├── ui/           → Yirou    — main window, browser, annotations, heart overlay
│   │   ├── dip/
│   │   │   ├── radiometric/  → Emily    — brightness, contrast, grayscale
│   │   │   ├── aesthetic/    → Emily    — borders
│   │   │   └── geometric/    → Chyntia  — resize, translate, rotate, object extraction
│   │   ├── synthesis/    → Winnie   — mosaic, video, overlays, media player
│   │   ├── social/       → Sam      — email sharing
│   │   └── util/         → Yirou (owns), ALL members use
│   │       ├── ImageUtils.java        ← shared Mat ↔ BufferedImage ↔ WritableImage bridge
│   │       ├── MetadataStore.java     ← annotation persistence
│   │       └── FavouritesManager.java ← favourites list for video compilation
│   └── resources/com/wig3003/photoapp/
│       ├── fxml/         → FXML views (one file per module)
│       ├── css/          → app.css
│       └── assets/       → icons, fonts
├── data/                 ← LOCAL ONLY — gitignored, not committed
│   ├── annotations/
│   └── output/           ← generated mosaics, extracted objects, AVI files
└── docs/
    ├── TechStack_v1.0.pdf
    ├── TaskAllocation.pdf
    └── report/
```

---

## Git Workflow

### Branch Structure

```
main          ← stable milestone releases only (Yirou merges here)
└── dev       ← integration branch (Yirou merges PRs here)
    └── feature/<member>-<task>   ← where all work happens
```

### Rules

1. **Never push directly to `main` or `dev`.** All work goes through a feature branch → PR → Yirou reviews → merges to `dev`.
2. **Branch naming:** `feature/<your-name>-<short-task>` — e.g. `feature/emily-grayscale`, `feature/chyntia-rotation`
3. **Before raising a PR:** confirm `mvn clean compile` passes with no errors, and no `data/` files are staged.
4. **Keep PRs small** — one PR per task, not one PR per person.
5. **Before starting new work,** always sync with `dev` first:

```bash
git checkout dev
git pull origin dev
git checkout -b feature/your-new-task
```

### Milestone Tags

| Tag | Condition |
|---|---|
| `v0.1` | `ImageUtils.java` stable + basic GUI browser working |
| `v0.2` | All DIP features merged and working |
| `v0.3` | Mosaic + video complete |
| `v1.0` | All features integrated, email working, demo-ready |

---

## Critical Development Rules

### 1. OpenCV initialization
`nu.pattern.OpenCV.loadLocally()` is called once in `MainApp.java` at startup. **Never call any OpenCV API outside the running application without this initialization** — it will throw `UnsatisfiedLinkError`.

### 2. JavaFX thread safety
OpenCV operations are slow — run them on a background thread. **Any update to a JavaFX UI element (`ImageView`, `Label`, `Slider`) must be wrapped in `Platform.runLater()`** when called from a background thread. Failing to do this causes `IllegalStateException` or silent UI corruption.

```java
// Correct pattern
new Thread(() -> {
    Mat result = performOpenCVOperation();
    Platform.runLater(() -> imageView.setImage(matToImage(result)));
}).start();
```

### 3. FXML file ownership
Each FXML file has one owner. **Only the owner of an FXML file edits it.** Cross-module wiring is routed through `MainController.java` (Sam). Editing someone else's FXML without coordination is the most likely source of merge conflicts.

### 4. `data/` is local only
The `data/` directory is in `.gitignore` and must never be committed. This includes user photos, annotations, generated mosaics, and AVI files. `MainApp.java` creates the required subdirectories automatically on first run.

### 5. Gmail SMTP requires an App Password
Standard Gmail passwords are blocked for SMTP. Use a Gmail App Password when testing the email feature. Do not commit credentials — use a local config file or environment variable. See Prerequisites section above for setup link.

---

## Module Ownership Quick Reference

| Module | Owner | Key Files | Depends On |
|---|---|---|---|
| App entry & wiring | Sam | `app/MainApp.java`, `app/MainController.java` | Nothing (initializes first) |
| GUI & navigation | Yirou | `ui/*`, `util/ImageUtils.java` | Nothing |
| Shared utilities | Yirou | `util/ImageUtils.java`, `util/MetadataStore.java`, `util/FavouritesManager.java` | Nothing |
| DIP radiometric & aesthetic | Emily | `dip/radiometric/*`, `dip/aesthetic/*` | `ImageUtils.java` |
| DIP geometric & object | Chyntia | `dip/geometric/*` | `ImageUtils.java` |
| Multimedia synthesis | Winnie | `synthesis/*` | `ImageUtils.java`, `FavouritesManager.java` |
| Email sharing | Sam | `social/EmailSender.java` | Nothing |

---

## Contribution Log

The full Team Contribution Log is maintained in [`docs/report/`](docs/report/) per submission requirements, and updated by throughout the project.

