package com.k2fsa.sherpa.ncnn;

import android.content.res.AssetManager;

/**
 * Java 版 sherpa-ncnn JNI 封装（由 SherpaNcnn.kt 转换）。
 * 类名/包名/native 方法名必须与 libsherpa-ncnn-jni.so 导出的 JNI 符号一致：
 * Java_com_k2fsa_sherpa_ncnn_SherpaNcnn_<method>（已用 readelf 验证 11 个符号）。
 */
public final class SherpaNcnn {
    // ── 配置类（原 Kotlin data class → POJO） ──
    public static final class FeatureExtractorConfig {
        public float sampleRate;
        public int featureDim;
        public FeatureExtractorConfig(float sampleRate, int featureDim) {
            this.sampleRate = sampleRate; this.featureDim = featureDim;
        }
    }

    public static final class ModelConfig {
        public String encoderParam, encoderBin, decoderParam, decoderBin;
        public String joinerParam, joinerBin, tokens;
        public int numThreads = 1;
        public boolean useGPU = true;
        public ModelConfig(String encoderParam, String encoderBin, String decoderParam,
                           String decoderBin, String joinerParam, String joinerBin,
                           String tokens, int numThreads, boolean useGPU) {
            this.encoderParam = encoderParam; this.encoderBin = encoderBin;
            this.decoderParam = decoderParam; this.decoderBin = decoderBin;
            this.joinerParam = joinerParam; this.joinerBin = joinerBin;
            this.tokens = tokens; this.numThreads = numThreads; this.useGPU = useGPU;
        }
    }

    public static final class DecoderConfig {
        public String method = "modified_beam_search";
        public int numActivePaths = 4;
        public DecoderConfig(String method, int numActivePaths) {
            this.method = method; this.numActivePaths = numActivePaths;
        }
    }

    public static final class RecognizerConfig {
        public FeatureExtractorConfig featConfig;
        public ModelConfig modelConfig;
        public DecoderConfig decoderConfig;
        public boolean enableEndpoint = true;
        public float rule1MinTrailingSilence = 2.4f;
        public float rule2MinTrailingSilence = 1.0f;
        public float rule3MinUtteranceLength = 30.0f;
        public String hotwordsFile = "";
        public float hotwordsScore = 1.5f;
        public RecognizerConfig(FeatureExtractorConfig featConfig, ModelConfig modelConfig, DecoderConfig decoderConfig) {
            this.featConfig = featConfig; this.modelConfig = modelConfig; this.decoderConfig = decoderConfig;
        }
    }

    // ── JNI 状态 ──
    private long ptr;
    private final RecognizerConfig config;

    static { System.loadLibrary("sherpa-ncnn-jni"); }

    public SherpaNcnn(RecognizerConfig config, AssetManager assetManager) {
        this.config = config;
        ptr = assetManager != null ? newFromAsset(assetManager, config) : newFromFile(config);
    }

    @Override protected void finalize() throws Throwable {
        try { if (ptr != 0) delete(ptr); } finally { super.finalize(); }
    }

    // 与原 Kotlin external fun 一一对应（符号已验证）
    private native long newFromAsset(AssetManager assetManager, RecognizerConfig config);
    private native long newFromFile(RecognizerConfig config);
    private native void delete(long ptr);
    private native void acceptWaveform(long ptr, float[] samples, float sampleRate);
    private native void inputFinished(long ptr);
    private native boolean isReady(long ptr);
    private native void decode(long ptr);
    private native boolean isEndpoint(long ptr);
    private native void reset(long ptr, boolean recreate);
    private native String getText(long ptr);

    /** Releases native resources. Safe to call multiple times. */
    public void delete() {
        if (ptr != 0) { delete(ptr); ptr = 0; }
    }

    // ── 公开 API（与 Kotlin 一致） ──
    public void acceptSamples(float[] samples) {
        acceptWaveform(ptr, samples, config.featConfig.sampleRate);
    }
    public boolean isReady() { return isReady(ptr); }
    public void decode() { decode(ptr); }
    public void inputFinished() { inputFinished(ptr); }
    public boolean isEndpoint() { return isEndpoint(ptr); }
    public void reset(boolean recreate) { reset(ptr, recreate); }
    public String getText() { return getText(ptr); }
}
