package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Highlights [PERSON_UUID] placeholders inserted by PiiShield in a distinct color
 * so the user can see exactly which names were detected and masked.
 */
class PiiHighlightTransformation(private val highlightColor: Color) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        PLACEHOLDER_REGEX.findAll(text.text).forEach { match ->
            spans.add(
                AnnotatedString.Range(
                    item = SpanStyle(
                        color = highlightColor,
                        background = highlightColor.copy(alpha = 0.15f),
                    ),
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            )
        }
        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\[PERSON_[a-fA-F0-9-]{36}\\]")
    }
}
