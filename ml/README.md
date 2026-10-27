# ml

Offline checks for the on-device fall rules live in `validation/`. That folder replays the Kotlin `FallDetector` on CAUCAFall, GMDCSA-24, and UR Fall Detection.

`train/` fits the small pose-window model the app runs next to those rules. See `train/README.md` and `validation/README.md`.
