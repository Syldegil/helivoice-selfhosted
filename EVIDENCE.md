# Evidence

Code analysis of the historical client1.0.2 and minimal server: WAV mono16kHz PCM16; bearer token; one transcription slot;40MiB/1200s input limits and1800s transcription timeout. No new full end-to-end hardware transcription was performed. Public adaptation changes namespace, requires HTTPS, replaces network pairing with local token entry, removes request logging and binds the gateway to loopback. Fresh build/fixture results are in VALIDATION.md; they do not qualify a complete deployment.

The insertion path calls getTextBeforeCursor(1,0), reading one existing character locally to decide whether to add a space. The read character is not passed to GatewayClient.transcribe and no transmission of it was found. The absolute claim that no existing text is read would therefore be false.
