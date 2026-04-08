package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LLM provider backed by Google AICore (Gemini Nano on-device).
 *
 * Requirements:
 * - Android 15 (API 35) or higher
 * - Device must declare the `android.software.ai.on_device` system feature
 *   (present on Pixel 8 Pro, Pixel 9 series, and future flagships)
 *
 * TODO: Wire the actual AICore inference once the `android.app.ai.SmallLanguageModel`
 *       API stabilises (it is in preview as of API 35). Replace [process] stub with:
 *         val model = SmallLanguageModel.Builder().build(context)
 *         model.generateTextAsync(prompt) { token -> emit(LlmResult.Token(token)) }
 *         emit(LlmResult.Complete)
 */
class NanoProvider(private val context: Context) : LlmProvider {

    override val id = "gemini_nano"

    override suspend fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < AICORE_MIN_SDK) return false
        return context.packageManager.hasSystemFeature(AICORE_FEATURE)
    }

    override fun process(prompt: String): Flow<LlmResult> = flow {
        // Stub — replaced once SmallLanguageModel API is stable and device has model weights.
        emit(
            LlmResult.Error(
                UnsupportedOperationException(
                    "Gemini Nano AICore integration not yet implemented. " +
                    "Requires android.app.ai.SmallLanguageModel (API 35 preview)."
                )
            )
        )
    }

    companion object {
        private const val AICORE_MIN_SDK = 35
        private const val AICORE_FEATURE = "android.software.ai.on_device"
    }
}
