package worldcup.helper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.caverock.androidsvg.SVG

/**
 * 加载 assets/flags/ 中的国旗 SVG 并渲染为圆形 Drawable。
 * 支持 FIFA 2字母码和3字母TLA码的自动映射。
 */
class CircleFlagLoader(private val context: Context) {

    companion object {
        private const val TAG = "CircleFlagLoader"
        private const val DEFAULT_SIZE = 56

        /** 3字母TLA到2字母国旗文件名的映射 */
        private val tlaToFlag: Map<String, String> = mapOf(
            "MEX" to "mx", "RSA" to "za", "KOR" to "kr", "CZE" to "cz",
            "CAN" to "ca", "BIH" to "ba", "QAT" to "qa", "SUI" to "ch",
            "BRA" to "br", "MAR" to "ma", "HAI" to "ht", "SCO" to "gb-sct",
            "USA" to "us", "PAR" to "py", "AUS" to "au", "TUR" to "tr",
            "GER" to "de", "CUW" to "cw", "CIV" to "ci", "ECU" to "ec",
            "NED" to "nl", "JPN" to "jp", "SWE" to "se", "TUN" to "tn",
            "ESP" to "es", "CPV" to "cv", "BEL" to "be", "EGY" to "eg",
            "KSA" to "sa", "URU" to "uy", "IRN" to "ir", "NZL" to "nz",
            "FRA" to "fr", "SEN" to "sn", "IRQ" to "iq", "NOR" to "no",
            "ARG" to "ar", "ALG" to "dz", "AUT" to "at", "JOR" to "jo",
            "POR" to "pt", "COD" to "cd", "UZB" to "uz", "COL" to "co",
            "ENG" to "gb-eng", "CRO" to "hr", "GHA" to "gh", "PAN" to "pa",
            "DEN" to "dk", "SRB" to "rs", "ITA" to "it", "NGA" to "ng",
            "CMR" to "cm", "CHI" to "cl", "PER" to "pe", "UKR" to "ua",
            "CRC" to "cr", "GRE" to "gr", "WAL" to "gb-wls",
            "ROU" to "ro", "VEN" to "ve", "FIN" to "fi", "BOL" to "bo",
            "HUN" to "hu", "BUL" to "bg", "ISL" to "is", "ALB" to "al",
            "MKD" to "mk", "SVN" to "si", "MNE" to "me", "GEO" to "ge",
            "SVK" to "sk",
        )

        /** 2字母码直接映射到文件名 */
        private val ccToFlag: Map<String, String> = mapOf(
            "mx" to "mx", "za" to "za", "kr" to "kr", "cz" to "cz",
            "ca" to "ca", "ba" to "ba", "qa" to "qa", "ch" to "ch",
            "br" to "br", "ma" to "ma", "ht" to "ht", "xs" to "gb-sct",
            "us" to "us", "py" to "py", "au" to "au", "tr" to "tr",
            "de" to "de", "cw" to "cw", "ci" to "ci", "ec" to "ec",
            "nl" to "nl", "jp" to "jp", "se" to "se", "tn" to "tn",
            "es" to "es", "cv" to "cv", "be" to "be", "eg" to "eg",
            "sa" to "sa", "uy" to "uy", "ir" to "ir", "nz" to "nz",
            "fr" to "fr", "sn" to "sn", "iq" to "iq", "no" to "no",
            "ar" to "ar", "dz" to "dz", "at" to "at", "jo" to "jo",
            "pt" to "pt", "cd" to "cd", "uz" to "uz", "co" to "co",
            "gb-eng" to "gb-eng", "hr" to "hr", "gh" to "gh", "pa" to "pa",
            "dk" to "dk", "rs" to "rs", "it" to "it", "ng" to "ng",
            "cm" to "cm", "cl" to "cl", "pe" to "pe", "ua" to "ua",
            "cr" to "cr", "gr" to "gr", "gb-wls" to "gb-wls",
            "ro" to "ro", "ve" to "ve", "fi" to "fi", "bo" to "bo",
            "hu" to "hu", "bg" to "bg", "is" to "is", "al" to "al",
            "mk" to "mk", "si" to "si", "me" to "me", "ge" to "ge",
            "sk" to "sk",
        )
    }

    private val cache = mutableMapOf<String, BitmapDrawable>()

    fun loadFlag(countryCode: String): Drawable? = loadFlag(countryCode, DEFAULT_SIZE)

    fun loadFlag(countryCode: String, sizePx: Int): Drawable? {
        if (countryCode.isBlank()) return null

        val cacheKey = "${countryCode}_$sizePx"
        cache[cacheKey]?.let { return it }

        // Try 3-letter TLA first, then 2-letter, then lowercase
        val filename = tlaToFlag[countryCode.uppercase()]
            ?: ccToFlag[countryCode.lowercase()]
            ?: countryCode.lowercase()

        return try {
            val svg = SVG.getFromAsset(context.assets, "flags/$filename.svg")
            if (svg == null) {
                Log.w(TAG, "SVG not found: flags/$filename.svg")
                return null
            }

            val renderSize = sizePx * 2
            svg.setDocumentWidth(renderSize.toFloat())
            svg.setDocumentHeight(renderSize.toFloat())

            val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(renderSize / 2f, renderSize / 2f, renderSize / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.saveLayer(0f, 0f, renderSize.toFloat(), renderSize.toFloat(), null)
            svg.renderToCanvas(canvas)
            canvas.restore()

            val drawable = BitmapDrawable(context.resources, bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            cache[cacheKey] = drawable
            drawable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load flag $countryCode: ${e.message}", e)
            null
        }
    }
}
