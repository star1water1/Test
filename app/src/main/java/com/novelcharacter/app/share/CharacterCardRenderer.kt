package com.novelcharacter.app.share

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.FieldDefinition

enum class CardTheme { LIGHT, DARK, FANTASY, MODERN }
enum class CardRatio(val widthPx: Int, val heightPx: Int) {
    SQUARE(1080, 1080),
    PORTRAIT_3_4(810, 1080),
    STORY_9_16(608, 1080)
}

class CharacterCardRenderer(private val context: Context) {

    data class CardConfig(
        val theme: CardTheme = CardTheme.LIGHT,
        val ratio: CardRatio = CardRatio.PORTRAIT_3_4,
        val includeImage: Boolean = true,
        val includeRelationships: Boolean = true,
        val selectedFieldIds: Set<Long> = emptySet(),
        val borderColor: Int? = null
    )

    fun render(
        character: Character,
        fieldValues: List<CharacterFieldValue>,
        fieldDefinitions: List<FieldDefinition>,
        relationships: List<Pair<String, String>>, // name to type
        characterBitmap: Bitmap?,
        config: CardConfig
    ): Bitmap {
        val w = config.ratio.widthPx
        val h = config.ratio.heightPx
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = getThemeColors(config.theme)

        // Background
        canvas.drawColor(colors.background)

        // Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.borderColor ?: colors.accent
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(3f, 3f, w - 3f, h - 3f, borderPaint)

        var yOffset = 24f
        val margin = 32f

        // Character image
        if (config.includeImage && characterBitmap != null) {
            val imgSize = (w * 0.35f).toInt()
            val imgX = (w - imgSize) / 2f
            val scaledBmp = Bitmap.createScaledBitmap(characterBitmap, imgSize, imgSize, true)

            // Circular clip
            val circleBitmap = Bitmap.createBitmap(imgSize, imgSize, Bitmap.Config.ARGB_8888)
            val circleCanvas = Canvas(circleBitmap)
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            circleCanvas.drawCircle(imgSize / 2f, imgSize / 2f, imgSize / 2f, circlePaint)
            circlePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            circleCanvas.drawBitmap(scaledBmp, 0f, 0f, circlePaint)

            canvas.drawBitmap(circleBitmap, imgX, yOffset, null)
            yOffset += imgSize + 16f
            scaledBmp.recycle()
            circleBitmap.recycle()
        }

        // Name
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textPrimary
            textSize = 42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(character.name, w / 2f, yOffset + 42f, namePaint)
        yOffset += 52f

        // Another name
        if (character.anotherName.isNotBlank()) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.textSecondary
                textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(character.anotherName, w / 2f, yOffset + 24f, subPaint)
            yOffset += 34f
        }

        // Separator
        yOffset += 8f
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.separator
            strokeWidth = 2f
        }
        canvas.drawLine(margin, yOffset, w - margin, yOffset, sepPaint)
        yOffset += 16f

        // Fields
        val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textPrimary
            textSize = 22f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary
            textSize = 20f
        }

        val fieldsToShow = if (config.selectedFieldIds.isEmpty()) {
            fieldDefinitions
        } else {
            fieldDefinitions.filter { it.id in config.selectedFieldIds }
        }

        for (fd in fieldsToShow) {
            if (yOffset > h - 80f) break
            val value = fieldValues.find { it.fieldDefinitionId == fd.id }?.value ?: continue
            if (value.isBlank()) continue

            canvas.drawText("${fd.name}:", margin, yOffset + 20f, labelPaint)
            canvas.drawText(value, margin + 180f, yOffset + 20f, fieldPaint)
            yOffset += 28f
        }

        // Relationships
        if (config.includeRelationships && relationships.isNotEmpty()) {
            yOffset += 8f
            canvas.drawLine(margin, yOffset, w - margin, yOffset, sepPaint)
            yOffset += 16f

            val relTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.accent
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("관계", margin, yOffset + 22f, relTitlePaint)
            yOffset += 32f

            for ((name, type) in relationships) {
                if (yOffset > h - 40f) break
                canvas.drawText("$name ($type)", margin + 16f, yOffset + 20f, fieldPaint)
                yOffset += 26f
            }
        }

        // Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary
            textSize = 16f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("NovelCharacter", w / 2f, h - 12f, footerPaint)

        return bitmap
    }

    private data class ThemeColors(
        val background: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val separator: Int
    )

    private fun getThemeColors(theme: CardTheme): ThemeColors = when (theme) {
        CardTheme.LIGHT -> ThemeColors(
            background = Color.WHITE,
            textPrimary = Color.parseColor("#212121"),
            textSecondary = Color.parseColor("#757575"),
            accent = Color.parseColor("#5C6BC0"),
            separator = Color.parseColor("#E0E0E0")
        )
        CardTheme.DARK -> ThemeColors(
            background = Color.parseColor("#1E1E1E"),
            textPrimary = Color.WHITE,
            textSecondary = Color.parseColor("#BDBDBD"),
            accent = Color.parseColor("#7986CB"),
            separator = Color.parseColor("#424242")
        )
        CardTheme.FANTASY -> ThemeColors(
            background = Color.parseColor("#1A0A2E"),
            textPrimary = Color.parseColor("#FFD700"),
            textSecondary = Color.parseColor("#C0A060"),
            accent = Color.parseColor("#FFD700"),
            separator = Color.parseColor("#3D2066")
        )
        CardTheme.MODERN -> ThemeColors(
            background = Color.parseColor("#F5F5F5"),
            textPrimary = Color.parseColor("#37474F"),
            textSecondary = Color.parseColor("#78909C"),
            accent = Color.parseColor("#FF7043"),
            separator = Color.parseColor("#CFD8DC")
        )
    }
}
