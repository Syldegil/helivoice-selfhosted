package io.github.syldegil.helivoice;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class GatewayClient {
    private GatewayClient() {}

    static String transcribe(String endpoint, String token, byte[] pcm, int pcmLength) throws Exception {
        HttpURLConnection connection = open(EndpointPolicy.normalize(endpoint) + "/v1/transcribe", "POST");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(1_800_000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "audio/wav");
        connection.setFixedLengthStreamingMode(WavEncoder.encodedLength(pcmLength));
        connection.setDoOutput(true);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                WavEncoder.writePcm16Mono16k(output, pcm, pcmLength);
            }
            JSONObject response = readJson(connection);
            String text = response.optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("Aucune parole reconnue");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        return connection;
    }

    private static JSONObject readJson(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(readAll(stream), StandardCharsets.UTF_8);
        JSONObject json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (code < 200 || code >= 300) {
            String message = json.optString("error", "Erreur serveur " + code);
            if (code == 401) message = "Jeton refusé : vérifie la configuration locale";
            if (code == 403) message = "Accès refusé par le serveur choisi";
            if (code == 429) message = "Whisper est déjà occupé, réessaie dans un instant";
            throw new IOException(message);
        }
        return json;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
