package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Highlights resolved @mention, #tag, and !place tokens in distinct colors so the user
 * can see inline references at a glance. Sentinel forms ([PERSON_UUID], [PLACE_UUID])
 * are only in the database — the editor always shows human-readable display names.
 *
 * Multi-word names (e.g. "@John Smith", "!Central Park") require exact resolved-name
 * patterns; pass [resolvedPersonNames] / [resolvedPlaceNames] so the transformer knows
 * where each token ends. Un-resolved in-progress mentions (@word, !word) are covered
 * by the fallback single-word regexes.
 *
 * @param highlightColor      Color for `@person` mentions.
 * @param tagHighlightColor   Color for `#tag` tokens. Null disables tag highlighting.
 * @param placeHighlightColor Color for `!place` mentions. Null disables place highlighting.
 * @param resolvedPersonNames Display names from [EditorUiState.resolvedPersons].
 * @param resolvedPlaceNames  Display names from [EditorUiState.resolvedPlaces].
 */
class PiiHighlightTransformation(
    private val highlightColor: Color,
    private val tagHighlightColor: Color? = null,
    private val placeHighlightColor: Color? = null,
    private val resolvedPersonNames: Set<String> = emptySet(),
    private val resolvedPlaceNames: Set<String> = emptySet(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

        // Resolved multi-word names first (longest first to avoid "Jo" shadowing "John Smith"),
        // then fall back to the single-word regex for in-progress mentions.
        val personPatterns = resolvedPersonNames
            .sortedByDescending { it.length }
            .map { Regex("@${Regex.escape(it)}") } + listOf(MENTION_FALLBACK_REGEX)

        personPatterns.forEach { regex ->
            regex.findAll(text.text).forEach { match ->
                spans.add(span(highlightColor, match.range))
            }
        }

        tagHighlightColor?.let { tagColor ->
            TAG_REGEX.findAll(text.text).forEach { match ->
                spans.add(span(tagColor, match.range))
            }
        }

        placeHighlightColor?.let { placeColor ->
            val placePatterns = resolvedPlaceNames
                .sortedByDescending { it.length }
                .map { Regex("!${Regex.escape(it)}") } + listOf(PLACE_FALLBACK_REGEX)

            placePatterns.forEach { regex ->
                regex.findAll(text.text).forEach { match ->
                    spans.add(span(placeColor, match.range))
                }
            }
        }

        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }

    private fun span(color: Color, range: IntRange) = AnnotatedString.Range(
        item = SpanStyle(color = color, background = color.copy(alpha = 0.15f)),
        start = range.first,
        end = range.last + 1,
    )

    companion object {
        // Fallback for un-resolved / in-progress single-word mentions
        private val MENTION_FALLBACK_REGEX = Regex("@\\S+")
        private val TAG_REGEX              = Regex("#[^\\s#]+")
        private val PLACE_FALLBACK_REGEX   = Regex("!\\S+")
    }
}
