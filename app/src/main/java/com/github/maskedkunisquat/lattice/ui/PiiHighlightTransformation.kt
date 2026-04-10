package com.github.maskedkunisquat.lattice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Highlights [PERSON_UUID] placeholders (PiiShield-masked names) and resolved
 * #tag tokens in distinct colors so the user can see inline tags at a glance.
 *
 * @param highlightColor Color for `[PERSON_UUID]` placeholders.
 * @param tagHighlightColor Color for `#tag` tokens. Null disables tag highlighting.
 * @param placeHighlightColor Color for `[PLACE_UUID]` placeholders. Null disables place highlighting.
 */
class PiiHighlightTransformation(
    private val highlightColor: Color,
    private val tagHighlightColor: Color? = null,
    private val placeHighlightColor: Color? = null,
) : VisualTransformation {

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

        tagHighlightColor?.let { tagColor ->
            TAG_REGEX.findAll(text.text).forEach { match ->
                spans.add(
                    AnnotatedString.Range(
                        item = SpanStyle(
                            color = tagColor,
                            background = tagColor.copy(alpha = 0.15f),
                        ),
                        start = match.range.first,
                        end = match.range.last + 1,
                    )
                )
            }
        }

        placeHighlightColor?.let { placeColor ->
            PLACE_REGEX.findAll(text.text).forEach { match ->
                spans.add(
                    AnnotatedString.Range(
                        item = SpanStyle(
                            color = placeColor,
                            background = placeColor.copy(alpha = 0.15f),
                        ),
                        start = match.range.first,
                        end = match.range.last + 1,
                    )
                )
            }
        }

        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\[PERSON_[a-fA-F0-9-]{36}\\]")
        private val TAG_REGEX         = Regex("#\\w+")
        private val PLACE_REGEX       = Regex("\\[PLACE_[a-fA-F0-9-]{36}\\]")
    }
}
