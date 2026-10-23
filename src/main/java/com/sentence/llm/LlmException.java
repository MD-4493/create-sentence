package com.sentence.llm;

/** LLM 调用失败（消息直接展示给用户，中文）。 */
public final class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
