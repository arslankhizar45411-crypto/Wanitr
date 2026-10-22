package com.collabcode.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

private val KEYWORDS = setOf(
    "package", "import", "fun", "val", "var", "class", "object", "data", "return",
    "if", "else", "when", "for", "while", "private", "override", "null", "true", "false"
)

private val TOKEN = Regex("\"[^\"\\n]*\"|//.*|\\b\\d+\\b|\\b[A-Za-z_]\\w*\\b|\\s+|.")

fun kotlinColored(src: String, cc: CCColors): AnnotatedString = buildAnnotatedString {
    for (m in TOKEN.findAll(src)) {
        val t = m.value
        val color: Color? = when {
            t.startsWith("//") -> cc.comment
            t.startsWith("\"") -> cc.str
            t[0].isDigit() -> cc.green
            t in KEYWORDS -> cc.purple
            t[0].isLetter() || t[0] == '_' -> {
                var j = m.range.last + 1
                while (j < src.length && src[j] == ' ') j++
                if (j < src.length && src[j] == '(') cc.yellow else null
            }
            else -> null
        }
        if (color != null) {
            withStyle(SpanStyle(color = color)) { append(t) }
        } else {
            append(t)
        }
    }
}

class KotlinVisualTransformation(private val cc: CCColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(kotlinColored(text.text, cc), OffsetMapping.Identity)
}
