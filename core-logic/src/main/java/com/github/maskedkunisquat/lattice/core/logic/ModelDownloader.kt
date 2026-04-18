package com.github.maskedkunisquat.lattice.core.logic

/** Platform-agnostic interface for enqueueing a model file download. */
fun interface ModelDownloader {
    fun enqueue(modelFile: String, url: String, sha256: String?)
}
