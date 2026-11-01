# CineCam, Stage 4d

New: adaptive smoothing. The stabilizer uses the strongest smoothing that still fits inside the crop margin,
and eases toward lighter smoothing where the shake is bigger than the margin (instead of hitting a hard wall).
The status line now also says how much of the clip got full smoothing.

Tests
1. Re-run High on a wild handheld 1080p clip. Screenshot the status line, send original + stabilized files.
2. Record a short 4K clip (tap the 1080p button so it says 4K), tap Test, screenshot the status line.
