# CineCam, Stage 4c

Changes from 4b
- More headroom: Standard ~7% crop, High ~13%, Action ~20%. Gentler limiter for big corrections.
- Only the slower part of the shake is corrected (the phone's lens stabilizer already handles very fast shake).
- Stabilized export is clipped to the original length (tries to remove the frozen tail).
- After every export the app checks the result and prints it in the status line:
  output vs input length, and where the output starts inside the original.
- New "Test" button: re-exports with NO correction, and also reports any frozen tail.
  Use it to find out if the start/end timing bug comes from the export library.

Test plan
1. Record a fresh 10-15 s clip (Wide, 1080p), walking slowly, textured scene.
2. Tap Test. When done, screenshot the status line (it starts with "Saved (Test)").
3. Tap High. Screenshot the status line again. Watch the saved High clip.
