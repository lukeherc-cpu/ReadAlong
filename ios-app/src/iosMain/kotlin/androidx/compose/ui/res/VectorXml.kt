// Desktop Compose's loadXmlImageVector, for the iPhone: reads the phone's Android vector drawables (paths, groups,
// fills and strokes, which is everything ReadAlong's icons use) into ImageVectors.
package androidx.compose.ui.res

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import org.xml.sax.InputSource

fun loadXmlImageVector(source: InputSource, @Suppress("UNUSED_PARAMETER") density: Density): ImageVector {
    val document = Ksoup.parse(source.reader.readText(), Parser.xmlParser(), "")
    val root = requireNotNull(document.children().firstOrNull { it.tagName() == "vector" }) { "Not a vector drawable" }
    fun Element.a(name: String): String? = attr("android:$name").ifEmpty { null }
    fun dp(value: String?) = value?.removeSuffix("dp")?.toFloatOrNull() ?: 24f
    val builder = ImageVector.Builder(
        defaultWidth = dp(root.a("width")).dp, defaultHeight = dp(root.a("height")).dp,
        viewportWidth = root.a("viewportWidth")?.toFloatOrNull() ?: 24f, viewportHeight = root.a("viewportHeight")?.toFloatOrNull() ?: 24f,
        autoMirror = root.a("autoMirrored") == "true")

    fun color(value: String?): Color? {
        val v = value?.trim() ?: return null
        if (!v.startsWith("#")) return if (v.endsWith("transparent")) null else if (v.endsWith("white")) Color.White else if (v.endsWith("black")) Color.Black else null
        val hex = v.drop(1)
        val argb = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            else -> hex
        }.toLong(16)
        return Color(argb.toInt()).takeIf { it.alpha > 0f }
    }

    fun walk(element: Element) {
        for (child in element.children()) when (child.tagName()) {
            "group" -> {
                builder.addGroup(
                    name = child.a("name") ?: "", rotate = child.a("rotation")?.toFloatOrNull() ?: 0f,
                    pivotX = child.a("pivotX")?.toFloatOrNull() ?: 0f, pivotY = child.a("pivotY")?.toFloatOrNull() ?: 0f,
                    scaleX = child.a("scaleX")?.toFloatOrNull() ?: 1f, scaleY = child.a("scaleY")?.toFloatOrNull() ?: 1f,
                    translationX = child.a("translateX")?.toFloatOrNull() ?: 0f, translationY = child.a("translateY")?.toFloatOrNull() ?: 0f)
                walk(child)
                builder.clearGroup()
            }
            "path" -> {
                val nodes = PathParser().parsePathString(child.a("pathData") ?: "").toNodes()
                val fill = color(child.a("fillColor"))
                val stroke = color(child.a("strokeColor"))
                builder.addPath(
                    pathData = nodes,
                    pathFillType = if (child.a("fillType") == "evenOdd") PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = fill?.let(::SolidColor), fillAlpha = child.a("fillAlpha")?.toFloatOrNull() ?: 1f,
                    stroke = stroke?.let(::SolidColor), strokeAlpha = child.a("strokeAlpha")?.toFloatOrNull() ?: 1f,
                    strokeLineWidth = child.a("strokeWidth")?.toFloatOrNull() ?: 0f,
                    strokeLineCap = when (child.a("strokeLineCap")) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                    strokeLineJoin = when (child.a("strokeLineJoin")) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                    strokeLineMiter = child.a("strokeMiterLimit")?.toFloatOrNull() ?: 4f)
            }
        }
    }
    walk(root)
    return builder.build()
}
