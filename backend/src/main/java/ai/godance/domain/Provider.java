package ai.godance.domain;

/**
 * Upstream model providers integrated in v1.
 * Per outcome brief §"Integrations (v1)".
 */
public enum Provider {
    // Western frontier
    OPENAI,
    ANTHROPIC,
    GOOGLE_VERTEX,
    XAI,

    // Multimodal Chinese
    VOLCENGINE_ARK,        // Doubao + Seedream + Seedance

    // Chinese text/LLM
    DEEPSEEK,
    MOONSHOT,              // Kimi
    ALIBABA_DASHSCOPE,     // Qwen
    ZHIPU,                 // GLM
    MINIMAX,

    // Image
    BFL,                   // Black Forest Labs — Flux
    STABILITY,

    // Video
    KLING,
    RUNWAY,

    // Audio
    ELEVENLABS,
    CARTESIA,
    DEEPGRAM
}
