package com.github.maskedkunisquat.lattice.core.logic

/**
 * A single labeled training sample for the distortion MLP head.
 *
 * [embedding] is a 384-dim Arctic Embed XS vector produced from the sample text.
 * [labels] is a 12-element array aligned to [CognitiveDistortion.entries] ordinal order;
 * `true` at position [i] means the distortion at ordinal [i] is present in the text.
 *
 * Training corpus: Shreevastava et al. (10 classes) + Claude-generated synthetic
 * examples for [CognitiveDistortion.DISQUALIFYING_POSITIVE] and [CognitiveDistortion.BLAME].
 */
data class DistortionSample(
    val embedding: FloatArray,
    val labels: BooleanArray,
) {
    init {
        require(embedding.size == EmbeddingProvider.EMBEDDING_DIM) {
            "Embedding must be ${EmbeddingProvider.EMBEDDING_DIM}-dim, got ${embedding.size}"
        }
        require(labels.size == CognitiveDistortion.entries.size) {
            "labels must have ${CognitiveDistortion.entries.size} elements, got ${labels.size}"
        }
    }

    // FloatArray and BooleanArray equality is reference-based in Kotlin data classes; override
    // so that two samples with identical values compare and hash correctly in tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DistortionSample) return false
        return embedding.contentEquals(other.embedding) && labels.contentEquals(other.labels)
    }

    override fun hashCode(): Int = 31 * embedding.contentHashCode() + labels.contentHashCode()
}
