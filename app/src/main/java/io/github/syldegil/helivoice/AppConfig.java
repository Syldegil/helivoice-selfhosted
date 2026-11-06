package io.github.syldegil.helivoice;

import android.content.Context;
import android.content.SharedPreferences;

final class AppConfig {
    static final String DEFAULT_ENDPOINT = "https://example.org";
    private static final String PREFS = "helivoice_config";
    private static final String ENDPOINT = "endpoint";

    private AppConfig() {}

    static String getEndpoint(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ENDPOINT, DEFAULT_ENDPOINT);
    }

    static void setEndpoint(Context context, String endpoint) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(ENDPOINT, EndpointPolicy.normalize(endpoint));
        editor.apply();
    }
}
