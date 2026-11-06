# Build and reproduce

Client: JDK17, Android SDK36, Gradle8.13, AGP8.13.2, minSDK28. Set ANDROID_HOME, then:

```sh
python3 bootstrap-wrapper.py
sh gradlew --no-daemon --max-workers=2 assembleDebug
```

Bootstrap checks official Gradle wrapper/distribution hashes. The public namespace io.github.syldegil.helivoice does not update the old private app. No APK/signing key is released and no installation is performed here.

Gateway: Python3.11+, `python3 -m venv .venv`, `.venv/bin/pip install -r gateway/requirements.txt`; run host fixtures with `.venv/bin/python -m unittest discover -s gateway -v`. Wyoming1.10.0 is pinned. A separate compatible Wyoming STT server is required (for example a separately installed Wyoming Faster Whisper service); this repository does not bundle its model, GPU image or deployment. A current complete client→TLS→gateway→STT recipe remains NOT_QUALIFIED.

Create a fresh URL-safe bearer token of at least32 characters in a mode0600 file, provision it locally on the client setup screen, and set HELIVOICE_TOKEN_FILE. Never put the token in a URL or commit it. Configure the remaining environment from gateway/config.example.env and run `.venv/bin/python gateway/server.py`. It binds loopback only. Terminate HTTPS with a trusted certificate at a separately configured reverse proxy on the same host; forward Authorization, set request/body/time limits, disable or redact access logs and never expose backend HTTP directly. Use only local Wyoming or an independently encrypted/authenticated backend channel; plain remote tcp is not confidential. Android requires HTTPS and does not bypass system TLS validation. `/pair` no longer exists; there is no token-distribution endpoint.
