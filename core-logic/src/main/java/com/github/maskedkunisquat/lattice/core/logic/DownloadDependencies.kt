package com.github.maskedkunisquat.lattice.core.logic

/**
 * Interface implemented by the application to provide access to the local model provider
 * for background workers without creating a circular dependency on the app module.
 */
interface DownloadDependencies {
    val localFallbackProvider: LocalModelProvider
}
