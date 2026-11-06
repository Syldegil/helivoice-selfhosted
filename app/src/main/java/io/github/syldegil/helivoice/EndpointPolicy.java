package io.github.syldegil.helivoice;

import android.net.Uri;

import java.util.Locale;

final class EndpointPolicy {
    private EndpointPolicy() {}

    static String normalize(String value) {
        String candidate = value == null ? "" : value.trim();
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        Uri uri = Uri.parse(candidate);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Adresse du serveur invalide");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("https")) {
            throw new IllegalArgumentException("HTTPS est obligatoire");
        }
        return candidate;
    }

}
