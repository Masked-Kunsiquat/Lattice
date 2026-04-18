package com.github.maskedkunisquat.lattice.core.logic

import java.io.InputStream

/** Platform-agnostic abstraction over asset file access, replacing android.content.Context.assets. */
fun interface AssetSource {
    fun open(path: String): InputStream
}
