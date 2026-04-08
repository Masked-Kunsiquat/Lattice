package com.github.maskedkunisquat.lattice.core.logic

/**
 * Streaming result type for LLM inference.
 *
 * Providers emit a sequence of [Token]s followed by exactly one terminal:
 * [Complete] on success or [Error] on failure.
 */
sealed class LlmResult {
    /** A partial token of generated text. Accumulate these to build the full response. */
    data class Token(val text: String) : LlmResult()

    /** Inference finished successfully. No more emissions will follow. */
    object Complete : LlmResult()

    /** Inference failed. [cause] carries the underlying exception. */
    data class Error(val cause: Throwable) : LlmResult()
}
