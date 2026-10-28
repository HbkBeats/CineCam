# CineCam, Stage 3 (compiled by you, not by Claude: expect small fixes)

New in Stage 3:
- Lens picker (Wide / Ultra / Front) by exact Camera2 ID.
- "Pro" panel: manual ISO and shutter, exposure compensation, manual focus, white balance presets.
- Bigger "Info" report: gyro, frame timestamp source, high-speed FPS, AWB/AF modes, stream use cases.
- minSdk is now 28.

## Build
1. Unzip this folder next to your old CineCam folder. File > Open the NEW CineCam folder.
2. Wait for Gradle sync (libraries are cached, so it should be much faster than before).
3. Pick your Samsung in the device box and press Run.

## Test checklist
1. Info > Copy > paste the report to Claude (it has new lines we need for stabilization).
2. Switch Wide / Ultra / Front. Each should show a live preview.
3. Pro: turn on Manual exposure, drag ISO and Shutter; preview should visibly change.
4. Pro: Manual focus on Wide; drag the slider while pointing at a near object.
5. Pro: try white balance presets.
6. Take a photo and a 4K video in manual mode; check they save and play.

Not implemented yet: stabilization, filters/LUTs, RAW capture, 60 fps, discreet mode, vault.
