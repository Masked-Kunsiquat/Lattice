package com.github.maskedkunisquat.lattice.core.logic

/** Platform-agnostic logger injected into core-logic classes to avoid android.util.Log imports. */
interface Logger {
    fun debug(tag: String, msg: String)
    fun info(tag: String, msg: String)
    fun warn(tag: String, msg: String, throwable: Throwable? = null)
}
