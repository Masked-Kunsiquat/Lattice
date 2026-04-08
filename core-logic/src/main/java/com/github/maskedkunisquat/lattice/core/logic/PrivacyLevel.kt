package com.github.maskedkunisquat.lattice.core.logic

/**
 * Represents the current data-sovereignty state of the app.
 *
 * The UI observes this via [LlmOrchestrator.privacyState] to show the appropriate
 * border color: blue for [LocalOnly], amber for [CloudTransit].
 */
sealed class PrivacyLevel {
    /** All inference is running on-device. User data has not left the device. */
    object LocalOnly : PrivacyLevel()

    /**
     * A cloud LLM provider is active. The UI must show an amber warning.
     *
     * @param providerName Identifier of the cloud provider (e.g. "cloud_claude").
     * @param sinceTimestamp Epoch-ms when the cloud session began.
     */
    data class CloudTransit(
        val providerName: String,
        val sinceTimestamp: Long
    ) : PrivacyLevel()
}
