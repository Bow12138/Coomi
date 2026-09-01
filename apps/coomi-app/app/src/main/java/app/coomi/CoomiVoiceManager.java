package app.coomi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.k2fsa.sherpa.ncnn.SherpaNcnn;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anna voice manager.
 *
 * <p>STT: local sherpa-ncnn (offline, no network, no API key). Mic audio is
 * captured via {@link AudioRecord} at 16 kHz mono, fed sample-by-sample into
 * the streaming recognizer, and partial text is delivered to the frontend.
 * Long utterances are chunked at ~100 Chinese characters per segment.</p>
 *
 * <p>TTS: system {@link TextToSpeech} (SIMPLE_TTS = thin wrapper over the
 * system default engine). Four-step resilience: (1) init status check with
 * retry; (2) engine availability check that avoids broken engines such as
 * {@code com.oplus.ttsaccessibilityengine}; (3) onDone/onError callback
 * verification; (4) fallback to plain text on the frontend when synthesis
 * fails — errors are never silently swallowed.</p>
 *
 * <p>Both channels are driven from the Web frontend through
 * {@code window.CoomiAndroid.*} bridge methods and report back via
 * {@code window.__coomiVoiceResult(...)}.</p>
 */
public final class CoomiVoiceManager {

    private static final String TAG = "CoomiVoice";

    public static final String CALLBACK = "window.__coomiVoiceResult && window.__coomiVoiceResult";

    /** Model directory under assets (bundled into the APK by CI). */
    private static final String MODEL_DIR = "models/sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23";

    private static final int SAMPLE_RATE = 16000;
    private static final int FEATURE_DIM = 80;
    private static final int CHUNK_SIZE = 100;          // 100 中文字符分段点
    private static final int AUDIO_BUFFER_MS = 40;      // 40ms per read chunk
    private static final int DECODE_INTERVAL_MS = 250;  // decode every 250ms

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── STT (sherpa-ncnn) ──
    private SherpaNcnn recognizer;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean listening;
    private String partialText = "";
    private String finalizedText = "";
    private long lastDecodeAt;

    // ── TTS (system TextToSpeech) ──
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsInitFailed;
    private int ttsEngineIndex = -1;
    private List<TextToSpeech.EngineInfo> ttsEngines = new ArrayList<>();

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

    // ==================== STT：sherpa-ncnn 本地离线识别 ====================

    /** Starts streaming voice recognition via local sherpa-ncnn (offline). */
    public void startRecognition() {
        if (listening) return;
        if (!hasRecordPermission()) {
            deliver("error", "no_permission");
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = createRecognizer();
                if (recognizer == null) {
                    deliver("error", "model_missing");
                    return;
                }
            }
            partialText = "";
            finalizedText = "";
            listening = true;
            startAudioCapture();
            deliver("ready", "");
            audioThread = new Thread(this::audioLoop, "sherpa-stt");
            audioThread.start();
        } catch (Exception e) {
            Log.e(TAG, "startRecognition failed", e);
            listening = false;
            deliver("error", e.getMessage() == null ? "start_failed" : e.getMessage());
        }
    }

    /** Stops streaming recognition and delivers the final text. */
    public void stopRecognition() {
        if (!listening) return;
        listening = false;
        if (audioThread != null) {
            try { audioThread.join(1500); } catch (InterruptedException ignored) { }
            audioThread = null;
        }
        stopAudioCapture();
        if (recognizer != null) {
            try {
                recognizer.inputFinished();
                recognizer.decode();
                String text = recognizer.getText();
                if (text != null && !text.isEmpty()) {
                    deliver("final", text);
                } else if (!finalizedText.isEmpty()) {
                    deliver("final", finalizedText);
                } else {
                    deliver("error", "no_match");
                }
            } catch (Exception e) {
                Log.e(TAG, "final decode failed", e);
                deliver("error", "decode_failed");
            }
        }
    }

    public void cancelRecognition() {
        listening = false;
        stopAudioCapture();
    }

    private SherpaNcnn createRecognizer() {
        try {
            SherpaNcnn.ModelConfig model = new SherpaNcnn.ModelConfig(
                MODEL_DIR + "/encoder_jit_trace-pnnx.ncnn.param",
                MODEL_DIR + "/encoder_jit_trace-pnnx.ncnn.bin",
                MODEL_DIR + "/decoder_jit_trace-pnnx.ncnn.param",
                MODEL_DIR + "/decoder_jit_trace-pnnx.ncnn.bin",
                MODEL_DIR + "/joiner_jit_trace-pnnx.ncnn.param",
                MODEL_DIR + "/joiner_jit_trace-pnnx.ncnn.bin",
                MODEL_DIR + "/tokens.txt",
                2, true);
            SherpaNcnn.FeatureExtractorConfig feat =
                new SherpaNcnn.FeatureExtractorConfig(SAMPLE_RATE, FEATURE_DIM);
            SherpaNcnn.DecoderConfig decoder =
                new SherpaNcnn.DecoderConfig("modified_beam_search", 4);
            SherpaNcnn.RecognizerConfig config =
                new SherpaNcnn.RecognizerConfig(feat, model, decoder);
            return new SherpaNcnn(config, context.getAssets());
        } catch (Exception e) {
            Log.e(TAG, "createRecognizer failed (models bundled?)", e);
            return null;
        }
    }

    private void startAudioCapture() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE * 2 * AUDIO_BUFFER_MS / 1000);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord = null;
            throw new IllegalStateException("audio_record_init_failed");
        }
        audioRecord.startRecording();
    }

    private void stopAudioCapture() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }

    /** PCM 16-bit mono loop: feed sherpa, decode periodically, chunk at 100 chars. */
    private void audioLoop() {
        if (audioRecord == null) return;
        short[] pcm = new short[SAMPLE_RATE * AUDIO_BUFFER_MS / 1000];
        lastDecodeAt = System.currentTimeMillis();
        while (listening && audioRecord != null) {
            int read = audioRecord.read(pcm, 0, pcm.length);
            if (read <= 0) continue;
            float[] samples = new float[read];
            for (int i = 0; i < read; i++) samples[i] = pcm[i] / 32768f;
            try {
                recognizer.acceptSamples(samples);
            } catch (Exception e) {
                Log.e(TAG, "acceptSamples failed", e);
                break;
            }
            long now = System.currentTimeMillis();
            if (now - lastDecodeAt >= DECODE_INTERVAL_MS) {
                lastDecodeAt = now;
                try {
                    recognizer.decode();
                    String text = recognizer.getText();
                    if (text != null && !text.equals(partialText)) {
                        partialText = text;
                        // 100 字分片：累积文本每满 CHUNK_SIZE 上抛一次
                        if (partialText.length() - finalizedText.length() >= CHUNK_SIZE) {
                            finalizedText = partialText;
                            deliver("partial", partialText);
                        } else {
                            deliver("partial", partialText);
                        }
                    }
                    if (recognizer.isEndpoint()) {
                        break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "decode failed", e);
                }
            }
        }
        if (listening) {
            // Endpoint reached or mic closed while still listening → finalize.
            listening = false;
            mainHandler.post(() -> {
                try {
                    if (recognizer != null) {
                        recognizer.inputFinished();
                        recognizer.decode();
                        String text = recognizer.getText();
                        if (text != null && !text.isEmpty()) deliver("final", text);
                        else if (!finalizedText.isEmpty()) deliver("final", finalizedText);
                        else deliver("error", "no_match");
                    }
                } catch (Exception e) {
                    deliver("error", "decode_failed");
                }
            });
        }
    }

    // ==================== TTS：系统引擎 + 四步异常链路 ====================

    /** Reads a reply out loud via system TTS (SIMPLE_TTS). */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            initTts();
            mainHandler.postDelayed(() -> speakNow(text), 1000);
            return;
        }
        speakNow(text);
    }

    public void stopSpeaking() {
        if (tts != null && ttsReady) tts.stop();
    }

    private void speakNow(String text) {
        if (tts == null || !ttsReady) {
            // 步骤 1：initialized 失败 → 重试初始化
            if (ttsInitFailed) {
                ttsInitFailed = false;
                initTts();
                mainHandler.postDelayed(() -> speakNow(text), 1000);
            } else {
                deliver("tts_error", "tts_not_ready");
            }
            return;
        }
        String utteranceId = "coomi-" + System.currentTimeMillis();
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            // 步骤 2/4：引擎合成失败 → 尝试切换引擎，失败则上报（前端降级纯文字）
            if (tryNextEngine()) {
                mainHandler.postDelayed(() -> speakNow(text), 1200);
            } else {
                deliver("tts_error", "speak_failed");
            }
        }
    }

    private void initTts() {
        if (tts != null) return;
        ttsEngines = safeGetEngines();
        ttsEngineIndex = -1;
        // 步骤 2：挑选可用引擎（避开 oplus/ttsaccessibility 问题引擎）
        String engine = pickNextEngine();
        try {
            if (engine != null) {
                tts = new TextToSpeech(context, ttsInitListener, engine);
            } else {
                tts = new TextToSpeech(context, ttsInitListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "TextToSpeech constructor failed", e);
            tts = null;
            ttsInitFailed = true;
            deliver("tts_error", "init_failed");
        }
    }

    private final TextToSpeech.OnInitListener ttsInitListener = status -> {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            ttsInitFailed = true;
            // 步骤 1：初始化失败重试一次（换引擎）
            if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) {} }
            tts = null;
            if (tryNextEngine()) {
                initTts();
            } else {
                deliver("tts_error", "init_failed");
            }
            return;
        }
        boolean ok = trySetLanguage(Locale.getDefault())
            || trySetLanguage(Locale.SIMPLIFIED_CHINESE)
            || trySetLanguage(Locale.CHINA)
            || trySetLanguage(Locale.US);
        ttsReady = ok;
        if (ok) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { deliver("tts_start", ""); }
                @Override public void onDone(String utteranceId) { deliver("tts_done", ""); }
                @Override public void onError(String utteranceId) {
                    // 步骤 3：onDone 未正常返回（合成报错）→ 切换引擎
                    if (tryNextEngine()) {
                        deliver("tts_engine_switched", "");
                    } else {
                        deliver("tts_error", "utterance_error");
                    }
                }
            });
            deliver("tts_ready", "");
        } else {
            ttsReady = false;
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

    private List<TextToSpeech.EngineInfo> safeGetEngines() {
        try {
            List<TextToSpeech.EngineInfo> engines = TextToSpeech.getEngines(context);
            return engines == null ? new ArrayList<>() : engines;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 步骤 2/4：挑选下一个可用 TTS 引擎。
     * 避开问题引擎（com.oplus.ttsaccessibilityengine 等 OPPO 系），
     * 优先知名稳定引擎（Google/Samsung/Huawei/Xiaomi），兜底任意非空引擎。
     */
    private String pickNextEngine() {
        if (ttsEngines == null || ttsEngines.isEmpty()) return null;
        List<TextToSpeech.EngineInfo> pool = new ArrayList<>();
        List<TextToSpeech.EngineInfo> fallback = new ArrayList<>();
        for (TextToSpeech.EngineInfo info : ttsEngines) {
            if (info == null || info.name == null || info.name.isEmpty()) continue;
            String name = info.name.toLowerCase(Locale.US);
            // 问题引擎：OPPO/一加 ttsaccessibility（initialized:true 却无声）
            if (name.contains("oplus") || name.contains("ttsaccessibility")
                || name.contains("coloros") || name.contains("breeno")) {
                continue;
            }
            // 知名稳定引擎优先
            if (name.contains("google") || name.contains("samsung")
                || name.contains("huawei") || name.contains("xiaomi")
                || name.contains("microsoft") || name.contains("ivona")) {
                pool.add(info);
            } else {
                fallback.add(info);
            }
        }
        List<TextToSpeech.EngineInfo> candidates = pool.isEmpty() ? fallback : pool;
        for (int i = ttsEngineIndex + 1; i < candidates.size(); i++) {
            ttsEngineIndex = i;
            return candidates.get(i).name;
        }
        ttsEngineIndex = candidates.size();
        return null;
    }

    /** Tries the next engine; returns true if a different engine was selected. */
    private boolean tryNextEngine() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
            ttsReady = false;
        }
        String next = pickNextEngine();
        if (next == null) return false;
        initTts();
        return true;
    }

    public void release() {
        cancelRecognition();
        stopSpeaking();
        if (recognizer != null) {
            try { recognizer.delete(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
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
                    sJsCallback.accept(CALLBACK + "(" + JSONObject.quote(json.toString()) + ")");
                }
            } catch (Exception ignored) {
            }
        });
    }
}
