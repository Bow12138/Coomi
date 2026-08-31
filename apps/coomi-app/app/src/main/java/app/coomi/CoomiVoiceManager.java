package app.coomi;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * CoomiGONG voice manager.
 *
 * <p>Wraps the system {@link SpeechRecognizer} (voice input) and
 * {@link TextToSpeech} (reply reading). Both channels are driven from the
 * Web frontend through {@code window.CoomiAndroid.*} bridge methods and
 * report back via {@code window.__coomiVoiceResult(...)}.</p>
 *
 * <p>Read-aloud is opt-in and off by default. Voice input inserts the
 * recognised text into the composer, and the frontend auto-sends it after a
 * short delay when configured to do so.</p>
 */
public final class CoomiVoiceManager {

    private static final String TAG = "CoomiVoice";

    /** JS callback name used to deliver recognition / TTS results. */
    public static final String CALLBACK = "window.__coomiVoiceResult && window.__coomiVoiceResult";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;

    /** Bridge to the active WebView, set by CoomiActivity on create. */
    private static java.util.function.Consumer<String> sJsCallback;

    public static void setJsCallback(java.util.function.Consumer<String> callback) {
        sJsCallback = callback;
    }

    public CoomiVoiceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Whether the microphone permission is currently granted. */
    public boolean hasRecordPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * Starts one-shot voice recognition. Results are delivered to
     * {@code window.__coomiVoiceResult('partial'/'final'|'error', text)}.
     * No-op when the recognizer is unavailable or the mic permission is
     * missing (frontend should ask for permission first).
     */
    public void startRecognition() {
        if (listening) return;
        if (!hasRecordPermission()) {
            deliver("error", "no_permission");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            deliver("error", "unavailable");
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
            recognizer.setRecognitionListener(listener);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            listening = true;
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "startRecognition failed", e);
            listening = false;
            deliver("error", e.getMessage() == null ? "start_failed" : e.getMessage());
        }
    }

    public void stopRecognition() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
    }

    public void cancelRecognition() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            deliver("ready", "");
        }

        @Override
        public void onBeginningOfSpeech() {
            deliver("begin", "");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            listening = false;
            deliver("end", "");
        }

        @Override
        public void onError(int error) {
            listening = false;
            String code;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH: code = "no_match"; break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "speech_timeout"; break;
                case SpeechRecognizer.ERROR_NETWORK: code = "network"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "no_permission"; break;
                default: code = "error_" + error;
            }
            deliver("error", code);
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (matches == null || matches.isEmpty()) ? "" : matches.get(0);
            deliver("final", text == null ? "" : text);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                deliver("partial", matches.get(0));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    /** Reads a reply out loud. No-op when TTS is not ready. */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            initTts();
            // Wait for async TTS init (up to ~2s), then try to speak.
            mainHandler.postDelayed(() -> speakNow(text), 800);
            return;
        }
        speakNow(text);
    }

    public void stopSpeaking() {
        if (tts != null && ttsReady) {
            tts.stop();
        }
    }

    private void speakNow(String text) {
        if (tts == null || !ttsReady) {
            deliver("tts_error", "tts_not_ready");
            return;
        }
        String utteranceId = "coomi-" + System.currentTimeMillis();
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            deliver("tts_error", "speak_failed");
        }
    }

    private void initTts() {
        if (tts != null) return;
        // Pick the best available engine explicitly. OPPO ships an offline
        // Breeno TTS engine; prefer it, otherwise use the system default.
        String engine = pickEngine();
        if (engine != null) {
            tts = new TextToSpeech(context, ttsInitListener, engine);
        } else {
            tts = new TextToSpeech(context, ttsInitListener);
        }
    }

    private final TextToSpeech.OnInitListener ttsInitListener = status -> {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            deliver("tts_error", "init_failed");
            return;
        }
        // Try device locale first, then Chinese, then any available voice.
        boolean ok = trySetLanguage(Locale.getDefault());
        if (!ok) ok = trySetLanguage(Locale.SIMPLIFIED_CHINESE);
        if (!ok) ok = trySetLanguage(Locale.CHINA);
        if (!ok) ok = trySetLanguage(Locale.US);
        ttsReady = ok;
        if (ttsReady) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    deliver("tts_start", "");
                }

                @Override
                public void onDone(String utteranceId) {
                    deliver("tts_done", "");
                }

                @Override
                public void onError(String utteranceId) {
                    deliver("tts_error", "utterance_error");
                }
            });
            deliver("tts_ready", "");
        } else {
            deliver("tts_error", "no_language");
        }
    };

    private boolean trySetLanguage(Locale locale) {
        if (locale == null) return false;
        try {
            int result = tts.setLanguage(locale);
            return result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Pick a TTS engine: prefer OPPO/Breeno offline engine, else system default. */
    private String pickEngine() {
        try {
            java.util.List<TextToSpeech.EngineInfo> engines =
                TextToSpeech.getEngines(context);
            if (engines == null || engines.isEmpty()) return null;
            // 1) OPPO / Breeno offline engine
            for (TextToSpeech.EngineInfo info : engines) {
                if (info == null || info.name == null) continue;
                String name = info.name.toLowerCase(Locale.US);
                if (name.contains("oplus") || name.contains("breeno")
                    || name.contains("coloros") || name.contains("ttsaccessibility")) {
                    return info.name;
                }
            }
            // 2) any engine with a non-empty package name
            for (TextToSpeech.EngineInfo info : engines) {
                if (info != null && info.name != null && !info.name.isEmpty()) return info.name;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void release() {
        cancelRecognition();
        stopSpeaking();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
            ttsReady = false;
        }
    }

    /** Helper to invoke the JS callback on the main thread. */
    private void deliver(String type, String payload) {
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", type);
                json.put("data", payload);
                if (sJsCallback != null) {
                    sJsCallback.accept(
                        CALLBACK + "(" + JSONObject.quote(json.toString()) + ")");
                }
            } catch (Exception ignored) {
            }
        });
    }
}
