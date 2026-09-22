# Offline Pitch Cutter

A single-file, dependency-free HTML app for trimming an audio/video clip and shifting its pitch, with a live pitch-shifted preview and export to a downloadable file. Runs entirely client-side (Web Audio API, `<canvas>`/`MediaRecorder` for video) — no build step, no backend.

- `pitch-cutter.html` — the original standalone file (works offline by itself, no server needed).
- `public/` — the same app packaged as an installable PWA (manifest + service worker), deployed to Firebase Hosting at https://offline-cutter-app.web.app.
- `firebase.json` / `.firebaserc` — Firebase Hosting config.

## Local preview

```
node .claude/static-server.js
```

Then open http://localhost:5195.

## Deploy

```
firebase deploy --only hosting
```
