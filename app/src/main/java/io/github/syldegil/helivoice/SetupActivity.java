package io.github.syldegil.helivoice;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class SetupActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText endpoint;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        refreshStatus();
    }

    private ScrollView createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 19, 24));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(this, 24), Ui.dp(this, 36), Ui.dp(this, 24), Ui.dp(this, 36));
        scroll.addView(column);

        column.addView(Ui.text(this, "HeliVoice", 30, Color.rgb(226, 232, 240)));
        column.addView(Ui.spacer(this, 8));
        column.addView(Ui.text(this,
                "Dictée vers le serveur de votre choix. Le son est envoyé en HTTPS ; ce n’est pas une transcription hors réseau.",
                16, Color.rgb(170, 182, 197)));
        column.addView(Ui.spacer(this, 22));

        status = Ui.text(this, "", 15, Color.rgb(143, 203, 255));
        column.addView(status);
        column.addView(Ui.spacer(this, 16));

        column.addView(Ui.text(this, "Serveur", 14, Color.rgb(170, 182, 197)));
        endpoint = new EditText(this);
        endpoint.setText(AppConfig.getEndpoint(this));
        endpoint.setTextColor(Color.rgb(226, 232, 240));
        endpoint.setHintTextColor(Color.GRAY);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        column.addView(endpoint);

        Button permission = Ui.button(this, "1. Autoriser le microphone");
        permission.setOnClickListener(view -> requestPermissions(
                new String[] {Manifest.permission.RECORD_AUDIO}, 10));
        column.addView(permission);

        tokenInput = new EditText(this);
        tokenInput.setHint("Jeton du serveur (saisi localement)");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(tokenInput);
        Button pair = Ui.button(this, "2. Enregistrer le serveur et le jeton");
        pair.setOnClickListener(view -> pair());
        column.addView(pair);

        Button enable = Ui.button(this, "3. Activer le clavier vocal HeliVoice");
        enable.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        column.addView(enable);

        Button choose = Ui.button(this, "4. Choisir HeliVoice une première fois");
        choose.setOnClickListener(view -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            manager.showInputMethodPicker();
        });
        column.addView(choose);

        column.addView(Ui.spacer(this, 24));
        column.addView(Ui.text(this,
                "Dans HeliBoard, garde la touche micro dans la barre d’outils. Un appui basculera vers HeliVoice, l’enregistrement démarrera, puis le texte sera inséré et HeliBoard reviendra automatiquement.",
                15, Color.rgb(170, 182, 197)));
        return scroll;
    }

    private void pair() {
        final String normalized;
        try {
            normalized = EndpointPolicy.normalize(endpoint.getText().toString());
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
            return;
        }
        try {
            String token = tokenInput.getText().toString().trim();
            if (!token.matches("[A-Za-z0-9_-]{32,}")) throw new IllegalArgumentException("Jeton invalide (32 caractères URL-safe minimum)");
            SecretStore.saveToken(this, token, normalized);
            AppConfig.setEndpoint(this, normalized);
            tokenInput.setText("");
            refreshStatus();
        } catch (Exception exception) {
            status.setText("Configuration refusée");
        }
    }

    private void refreshStatus() {
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean paired = SecretStore.loadToken(this) != null;
        status.setText("Microphone : " + (microphone ? "autorisé" : "à autoriser")
                + "  •  Serveur : " + (paired ? "associé" : "à associer"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "erreur réseau" : message;
    }
}
