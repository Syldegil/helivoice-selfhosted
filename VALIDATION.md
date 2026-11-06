# Export validation — 2026-11-06

PASS:Gradle8.13 wrapper and distribution hash pins; Android assembleDebug on clean isolated source copy (AGP8.13.2/SDK36), repeated after credential/endpoint binding fixes. PASS:7 gateway fixtures with Wyoming1.10.0:valid/invalid WAV, absent pairing, no request logging, authentication before body read, short-token rejection and non-loopback refusal.

Independent review found and verified fixes for endpoint/token mismatch during setup and during recording. Stored credentials are bound to an endpoint; recording captures both without later config reread. No actual audio, STT server, TLS deployment, device installation or end-to-end transcription was used:NOT_RUN. Android lifecycle/cancellation limitation remains open. Build deprecation warnings remain; local APK and signatures excluded.
