# 🚀 Crackrinth Master Guide: Management & Maintenance

Welcome to the official maintainer guide for **Crackrinth**. This document contains everything you need to know about managing keys, security, updates, and keeping the project open source.

---

## 🔑 1. Security & Signing Keys (The "Signature")

Tauri requires a digital signature to verify that updates are authentic. This is **free** and essential for the automatic updater to work.

### One-Time Setup:
If you haven't already, run this command in your terminal to generate your keys:
```powershell
pnpm --filter=@modrinth/app exec tauri signer generate -w ./apps/app/tauri.key
```

### What you get:
1.  **Public Key** (`tauri.key.pub`): This is embedded in the app. Paste its content into [tauri.conf.json](file:///c:/Users/elian/Desktop/Crackrinth/apps/app/tauri.conf.json) under `plugins.updater.pubkey`.
2.  **Private Key**: A long string of text shown in the terminal. **SAVE THIS SECRETS!**
3.  **Password**: The password you chose (or was generated) for the key.

### Where to store them?
**NEVER** commit your Private Key or Password to GitHub! Instead, use **GitHub Secrets**:
1.  Go to your GitHub Repository -> **Settings** -> **Secrets and variables** -> **Actions**.
2.  Add a secret named `TAURI_SIGNING_PRIVATE_KEY` with your private key.
3.  Add a secret named `TAURI_SIGNING_PASSWORD` with your password.

---

## 📦 2. Releasing a New Version

When you want to push an update to your users:

### Step 1: Versioning
Update the version number in [apps/app-frontend/package.json](file:///c:/Users/elian/Desktop/Crackrinth/apps/app-frontend/package.json).
Example: Change `"1.1.0"` to `"1.2.0"`.

### Step 2: Push a Tag
Commit your changes and push a tag to GitHub:
```powershell
git add .
git commit -m "feat: your new feature"
git tag -a v1.2.0 -m "Release v1.2.0"
git push origin v1.2.0
```

### Step 3: GitHub Actions
The build will start automatically. Once it's done, go to the **Actions** tab, find the latest build, and download the **Artifacts**.
- **For Users**: Upload the `.exe` (Windows) or `.dmg` (Mac) directly to a new GitHub Release.
- **For the Updater**: You need the `.zip` (e.g. `Crackrinth_1.2.0_x64_en-US.msi.zip`) and its signature.

### Step 4: Update `updater.json`
Update the [updater.json](file:///c:/Users/elian/Desktop/Crackrinth/updater.json) file in your repository:
1.  Change `"version"` to `"1.2.0"`.
2.  Paste the **new download URL** for the ZIP.
3.  Paste the **new signature** (found in the `.sig` file from the artifacts).

Users will see the update popup the next time they launch Crackrinth!

---

## 📂 3. Open Source & Privacy

Crackrinth is designed to be a cleaner, offline-friendly alternative to the Modrinth App.

### Keeping it clean:
- **Telemetry**: All Modrinth telemetry is **DISABLED** by default. Do not re-enable it.
- **Branding**: Always use "Crackrinth" for the app name to avoid legal issues.
- **License**: The project uses an Open Source license. Anyone can contribute via Pull Requests.

### GitHub Workflows:
- Use `Crackrinth Build` for all your builds.
- Legacy Modrinth workflows (`theseus-build.yml`, etc.) have been removed to prevent errors.

---

## 🛠️ 4. Local Development

To run the app locally for testing:
1.  `pnpm install`
2.  `pnpm tauri dev`

> [!TIP]
> **Signature Errors?** During local development, the updater might not work because the keys isn't signed by your official GitHub secret. This is normal. Always test updates with the official GitHub builds.

---

**Du bist nun bereit, Crackrinth wie ein Profi zu verwalten!** Wenn du Fragen hast, schau einfach in dieses Handbuch.
