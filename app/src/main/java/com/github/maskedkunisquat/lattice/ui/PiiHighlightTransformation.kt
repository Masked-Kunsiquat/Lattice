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
 * @param highlightColor Color for `@person` mentions.
 * @param tagHighlightColor Color for `#tag` tokens. Null disables tag highlighting.
 * @param placeHighlightColor Color for `!place` mentions. Null disables place highlighting.
 */
class PiiHighlightTransformation(
    private val highlightColor: Color,
    private val tagHighlightColor: Color? = null,
    private val placeHighlightColor: Color? = null,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()

        MENTION_REGEX.findAll(text.text).forEach { match ->
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
        private val MENTION_REGEX = Regex("@\\S+")
        private val TAG_REGEX     = Regex("#[^\\s#]+")
        private val PLACE_REGEX   = Regex("!\\S+")
    }
}
