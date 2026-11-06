package io.github.syldegil.helivoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HeliVoiceImeService extends InputMethodService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final VoiceRecorder recorder = new VoiceRecorder();
    private TextView status;
    private ProgressBar level;
    private Button microphone;
    private volatile boolean processing;
    private boolean windowVisible;

    @Override
    public View onCreateInputView() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 18));
        column.setBackgroundColor(Color.rgb(16, 19, 24));

        status = Ui.text(this, "Prêt", 17, Color.rgb(226, 232, 240));
        status.setGravity(android.view.Gravity.CENTER);
        column.addView(status);

        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 8));
        levelParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        level.setLayoutParams(levelParams);
        column.addView(level);

        microphone = Ui.button(this, "●  Parler");
        microphone.setOnClickListener(view -> {
            if (recorder.isRunning()) recorder.stop();
            else if (!processing) startRecording();
        });
        column.addView(microphone);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = Ui.button(this, "Annuler");
        Button keyboard = Ui.button(this, "⌨  HeliBoard");
        Button settings = Ui.button(this, "Réglages");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        actionParams.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        cancel.setLayoutParams(actionParams);
        keyboard.setLayoutParams(new LinearLayout.LayoutParams(actionParams));
        settings.setLayoutParams(new LinearLayout.LayoutParams(actionParams));
        actions.addView(cancel);
        actions.addView(keyboard);
        actions.addView(settings);
        column.addView(actions);

        cancel.setOnClickListener(view -> {
            recorder.cancel();
            processing = false;
            switchToPreviousInputMethod();
        });
        keyboard.setOnClickListener(view -> {
            if (recorder.isRunning()) recorder.cancel();
            switchToPreviousInputMethod();
        });
        settings.setOnClickListener(view -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        return column;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (!windowVisible) {
            windowVisible = true;
            main.postDelayed(this::startRecording, 180);
        }
    }

    @Override
    public void onWindowHidden() {
        windowVisible = false;
        if (recorder.isRunning()) recorder.cancel();
        super.onWindowHidden();
    }

    private void startRecording() {
        if (!windowVisible || processing || recorder.isRunning()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Autorise le microphone dans l’application HeliVoice");
            return;
        }
        final String endpoint = AppConfig.getEndpoint(this);
        String token = SecretStore.loadToken(this, endpoint);
        if (token == null) {
            showError("Associe d’abord HeliVoice au serveur");
            return;
        }
        setStatus("Je t’écoute…", "■  Terminer");
        level.setProgress(0);
        recorder.start(new VoiceRecorder.Listener() {
            @Override
            public void onLevel(int value) {
                main.post(() -> level.setProgress(value));
            }

            @Override
            public void onFinished(byte[] pcm, int pcmLength, boolean heardSpeech) {
                if (!heardSpeech) {
                    main.post(() -> showError("Je n’ai pas détecté de parole"));
                    return;
                }
                processing = true;
                main.post(() -> setStatus("Transcription par Whisper…", "Patiente…"));
                transcribe(pcm, pcmLength, token, endpoint);
            }

            @Override
            public void onCanceled() {
                main.post(() -> setStatus("Annulé", "●  Parler"));
            }

            @Override
            public void onError(String message) {
                main.post(() -> showError(message));
            }
        });
    }

    private void transcribe(byte[] pcm, int pcmLength, String token, String endpoint) {
        network.execute(() -> {
            try {
                String text = GatewayClient.transcribe(endpoint, token, pcm, pcmLength);
                main.post(() -> commitAndReturn(text));
            } catch (Exception exception) {
                main.post(() -> showError(safeMessage(exception)));
            } finally {
                processing = false;
            }
        });
    }

    private void commitAndReturn(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            showError("Le champ de texte n’est plus actif");
            return;
        }
        CharSequence before = connection.getTextBeforeCursor(1, 0);
        String insertion = text.trim();
        if (before != null && before.length() == 1
                && !Character.isWhitespace(before.charAt(0))
                && !insertion.isEmpty()
                && Character.isLetterOrDigit(insertion.charAt(0))) {
            insertion = " " + insertion;
        }
        connection.commitText(insertion, 1);
        setStatus("Texte inséré", "●  Parler");
        main.postDelayed(this::switchToPreviousInputMethod, 180);
    }

    private void showError(String message) {
        processing = false;
        setStatus(message, "Réessayer");
        if (status != null) status.setTextColor(Color.rgb(255, 180, 171));
    }

    private void setStatus(String message, String button) {
        if (status != null) {
            status.setText(message);
            status.setTextColor(Color.rgb(226, 232, 240));
        }
        if (microphone != null) {
            microphone.setText(button);
            microphone.setEnabled(!button.equals("Patiente…"));
        }
    }

    @Override
    public void onDestroy() {
        recorder.cancel();
        network.shutdownNow();
        super.onDestroy();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "Erreur de transcription" : message;
    }
}
