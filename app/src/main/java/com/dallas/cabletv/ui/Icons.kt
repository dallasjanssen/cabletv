package com.dallas.cabletv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn on a 24 by 24 grid. Outline icons use a 2 unit stroke. They are tinted
 * by whatever color the surrounding text uses, so they turn dark on a focused white button.
 */
object TvIcons {
    private fun outline(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    private fun filled(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d -> b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    val Play = filled("play", "M7 4.5v15l12.5-7.5z")
    val Pause = filled("pause", "M6 4h4v16H6zM14 4h4v16h-4z")
    val Next = filled("next", "M5 5v14l10-7zM16 5h3v14h-3z")
    val StarFilled = filled("starFilled", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z")
    val Star = outline("star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z")
    val Guide = outline("guide", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 10h18M9 10v10")
    val List = outline("list", "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01")
    val Replay = outline("replay", "M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5M12 7v5l3 2")
    val Captions = outline(
        "captions",
        "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z",
        "M10.5 10.2a2.5 2.5 0 1 0 0 3.6M17 10.2a2.5 2.5 0 1 0 0 3.6"
    )
    val ChevronDown = outline("chevronDown", "M6 9l6 6 6-6")
    val ChevronRight = outline("chevronRight", "M9 6l6 6-6 6")
    val Phone = outline("phone", "M9.5 2.5h5A2.5 2.5 0 0 1 17 5v14a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 19V5a2.5 2.5 0 0 1 2.5-2.5z", "M11 18h2")
    val Tv = outline("tv", "M4.5 5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8")
    val Film = outline(
        "film",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4"
    )
    val Eye = outline("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    val People = outline(
        "people",
        "M12.5 8a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z",
        "M2.5 20c.8-3.5 3.4-5.5 6.5-5.5s5.7 2 6.5 5.5M16 4.8a3.5 3.5 0 0 1 0 6.4M18.5 14.8c1.5.8 2.6 2.6 3 5.2"
    )
    val Lock = outline("lock", "M6 10.5h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 10.5V7.5a4 4 0 0 1 8 0v3")
    val Download = outline("download", "M12 3v12M7 10l5 5 5-5M4 20h16")
    val Search = outline("search", "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M20 20l-4-4")
    val Check = outline("check", "M5 12.5l4.5 4.5L19 7.5")
    val Plus = outline("plus", "M12 5v14M5 12h14")
    val Back = outline("back", "M15 6l-6 6 6 6")
    val Home = outline("home", "M3.5 11L12 4l8.5 7", "M5.5 9.5V20h13V9.5", "M10 20v-5.5h4V20")
}
