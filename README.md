# CineCam, Stage 4b: stabilize after recording

How it works
1. Record normally (Pro > "Record gyro" is ON by default). The phone's gyroscope is recorded alongside the video.
2. When the clip finishes, "Stabilize last clip" appears: Standard / High / Action.
3. The app measures real picture motion in ~50 spots of the clip, lines it up with the gyro (finds the time offset
   and gains by itself), smooths the camera path, then re-renders the video with each frame shifted and zoomed.
4. Result: Movies/CineCam/CineCam_stab_<mode>_<time>.mp4. The original is untouched.

Modes (first guesses): Standard = light smoothing, ~5% crop. High = ~9% crop. Action = ~15% crop, strongest smoothing.

First test: Wide lens, 1080p, record 10-15 s handheld while walking slowly, pointing at a textured scene.
Stabilize only works on the last clip recorded in this session. No roll/horizon correction yet.
