# CineCam, Stage 4a

New: OIS switch (Default / On / Off) in the Pro panel for lenses that have OIS (Wide),
and a Gyro sync test that saves a CSV of gyro and frame timing while you film.
Also: tapping the preview no longer fights manual focus, and minSdk is 29.

## Gyro test, please do this
1. Lens: Wide. Pro > turn on "Gyro sync test (1080p)".
2. Pro > OIS > Off. Point at a textured scene (bookshelf, tree, a wall with posters; NOT a blank wall).
3. Record about 10 seconds: slowly pan left and right a few times, then a few small shakes. Stop.
4. Repeat with OIS = On. Optionally repeat on Ultra.
5. Files appear in: Files app > Download > CineCam. Upload the .csv files to Claude.

Photos are disabled while Gyro sync test is on.

## Installing
This build uses a fixed debug key, so later GitHub builds install over this one without uninstalling.
The FIRST time you install this version, uninstall the old CineCam once (different signing key).
