#!/usr/bin/env python3
"""Small authenticated HTTP bridge from HeliVoice to Wyoming STT."""

from __future__ import annotations

import asyncio
import hmac
import io
import json
import os
import threading
import wave
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from wyoming.asr import Transcribe, Transcript
from wyoming.audio import wav_to_chunks
from wyoming.client import AsyncClient
from wyoming.error import Error


BIND = os.environ.get("HELIVOICE_BIND", "127.0.0.1")
PORT = int(os.environ.get("HELIVOICE_PORT", "11990"))
WYOMING_URI = os.environ.get("HELIVOICE_WYOMING_URI", "tcp://127.0.0.1:10300")
LANGUAGE = os.environ.get("HELIVOICE_LANGUAGE", "fr")
TOKEN_FILE = Path(os.environ.get("HELIVOICE_TOKEN_FILE", "/run/secrets/helivoice-token"))
MAX_AUDIO_BYTES = int(os.environ.get("HELIVOICE_MAX_AUDIO_BYTES", str(40 * 1024 * 1024)))
MAX_DURATION_SECONDS = float(os.environ.get("HELIVOICE_MAX_DURATION_SECONDS", "1200"))
TRANSCRIBE_TIMEOUT_SECONDS = float(os.environ.get("HELIVOICE_TRANSCRIBE_TIMEOUT_SECONDS", "1800"))

_transcription_slot = threading.BoundedSemaphore(value=1)


def read_token() -> str:
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if len(token) < 32:
        raise RuntimeError("HeliVoice token is missing or too short")
    return token


def validate_wav(payload: bytes) -> wave.Wave_read:
    try:
        wav_file = wave.open(io.BytesIO(payload), "rb")
    except (EOFError, wave.Error) as exc:
        raise ValueError("invalid WAV payload") from exc

    if wav_file.getnchannels() != 1:
        wav_file.close()
        raise ValueError("audio must be mono")
    if wav_file.getsampwidth() != 2:
        wav_file.close()
        raise ValueError("audio must use signed 16-bit PCM")
    if wav_file.getframerate() != 16000:
        wav_file.close()
        raise ValueError("audio must use a 16 kHz sample rate")

    duration = wav_file.getnframes() / wav_file.getframerate()
    if duration < 0.15:
        wav_file.close()
        raise ValueError("audio is too short")
    if duration > MAX_DURATION_SECONDS:
        wav_file.close()
        raise ValueError("audio is too long")
    return wav_file


async def transcribe(payload: bytes) -> str:
    wav_file = validate_wav(payload)
    try:
        async with AsyncClient.from_uri(WYOMING_URI) as client:
            await client.write_event(Transcribe(language=LANGUAGE).event())
            for chunk in wav_to_chunks(
                wav_file,
                samples_per_chunk=1024,
                start_event=True,
                stop_event=True,
            ):
                await client.write_event(chunk.event())

            while True:
                event = await client.read_event()
                if event is None:
                    raise RuntimeError("Wyoming STT disconnected before returning text")
                if Transcript.is_type(event.type):
                    return Transcript.from_event(event).text.strip()
                if Error.is_type(event.type):
                    error = Error.from_event(event)
                    raise RuntimeError(f"Wyoming STT error {error.code}")
    finally:
        wav_file.close()


class Handler(BaseHTTPRequestHandler):
    server_version = "HeliVoice/1.0.2"

    def log_message(self, fmt: str, *args: object) -> None:
        # Never log tokens, audio bytes, URLs with query strings, or transcripts.
        return  # Access logs deliberately disabled; proxy logs need their own policy.

    def send_json(self, status: HTTPStatus, body: dict[str, object]) -> None:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "ok"})
            return
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self) -> None:
        self.connection.settimeout(30)
        if self.path.split("?", 1)[0] != "/v1/transcribe":
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return

        expected = f"Bearer {read_token()}"
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, expected):
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return

        if self.headers.get_content_type() not in {"audio/wav", "audio/x-wav"}:
            self.send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Content-Type must be audio/wav"})
            return
        try:
            content_length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            content_length = -1
        if content_length <= 0 or content_length > MAX_AUDIO_BYTES:
            self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "invalid audio size"})
            return

        if not _transcription_slot.acquire(blocking=False):
            self.send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "transcriber is busy"})
            return
        try:
            payload = self.rfile.read(content_length)
            if len(payload) != content_length:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "incomplete request body"})
                return
            try:
                text = asyncio.run(
                    asyncio.wait_for(transcribe(payload), timeout=TRANSCRIBE_TIMEOUT_SECONDS)
                )
            except ValueError as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            except TimeoutError:
                self.send_json(HTTPStatus.GATEWAY_TIMEOUT, {"error": "transcription timed out"})
                return
            except Exception:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"error": "speech service unavailable"})
                return

            if not text:
                self.send_json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": "no speech recognized"})
                return
            self.send_json(HTTPStatus.OK, {"text": text})
        finally:
            _transcription_slot.release()


def main() -> None:
    if BIND not in ('127.0.0.1', 'localhost'):
        raise RuntimeError('Bind to loopback and use a TLS reverse proxy')
    read_token()
    server = ThreadingHTTPServer((BIND, PORT), Handler)
    print(f"HeliVoice gateway listening on {BIND}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
