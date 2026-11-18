package com.kimes.daq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.R

/*
 * "Paddock": 1950s-60s race graphics. Poster paper and ink, tall condensed numerals, number
 * roundels, heavy black borders, twin stripes and the checker. Dark ink on light paper also reads
 * best in direct sun. Color by meaning only: green ahead, red behind or a fault, yellow caution and
 * the course, violet best of the day, blue for anything you can tap.
 */

// Paper and ink
val Paper = Color(0xFFEFE6CF)
val Paper2 = Color(0xFFE4D8BA)
val Ink = Color(0xFF17140F)
val Ink2 = Color(0xFF5A5040)

// Fills (blocks, bars, whole fields)
val RedFill = Color(0xFFC8241B)
val Yellow = Color(0xFFF0B323)
val Violet = Color(0xFF6B2FB3)
val Blue = Color(0xFF1F3F8F)
val Sage = Color(0xFFA9D6A0)
val GreenFill = Color(0xFF4E8C55)
val BrakeRed = Color(0xFFE0322A)

// Names the screens use
val Bg = Paper
val Surface = Paper2
val Panel = Surface
val Rule = Ink
val RuleFaint = Color(0xFFC9BB98)
val Hi = Ink                      // values
val Dim = Ink2                    // labels, secondary
val Lo = Color(0xFF978B70)        // inactive
val Green = Color(0xFF2E7D3E)     // ahead (as text on paper)
val Red = Color(0xFFB01E16)       // behind, fault (as text on paper)
val Amber = Color(0xFF8A5A00)     // caution (as text on paper)
val Magenta = Violet              // best of the day / the reference
val Cyan = Blue                   // you can tap it
val OnCyan = Color.White
val ConeOrange = Color(0xFFF07A1A)
val OnMap = Paper                 // text over satellite imagery

// Type: tall condensed numerals for anything read at a glance, condensed caps for words
val Anton = FontFamily(Font(R.font.anton))
val Cond = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.Normal),
    Font(R.font.barlow_condensed_semibold, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.Black),
)
val BaseText = TextStyle(fontFamily = Cond, fontWeight = FontWeight.SemiBold, color = Ink)

private val sharp = RectangleShape

/** Small tracked caps label. */
/** Taller than wide: screens stack their panes top to bottom instead of side by side. */
@Composable
fun isPortrait(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.screenHeightDp > c.screenWidthDp
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Dim) =
    Text(text.uppercase(), modifier, color = color, fontSize = 12.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp, maxLines = 1, softWrap = false)

/** A number in the tall condensed face (its figures are all one width). */
@Composable
fun Num(text: String, size: TextUnit, color: Color = Hi, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") weight: FontWeight = FontWeight.Normal) =
    Text(text, modifier, color = color, fontSize = size, fontFamily = Anton, lineHeight = size, maxLines = 1, softWrap = false)

/** Section: a heavy rule across the top, a label, then content. */
@Composable
fun Section(label: String, modifier: Modifier = Modifier, right: String? = null, rightColor: Color = Dim, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().topRule(Rule, 2.dp).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Label(label, Modifier.weight(1f), Ink)
            right?.let { Label(it, color = rightColor) }
        }
        content()
    }
}

/** Key/value row with a rule under it. */
@Composable
fun KV(key: String, value: String, valueColor: Color = Hi, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().then(if (last) Modifier else Modifier.bottomRule(RuleFaint, 1.5.dp)).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = Dim, fontSize = 15.sp, fontFamily = Cond, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
        Text(value, color = valueColor, fontSize = 15.sp, fontFamily = Cond, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** The one main action on a screen: a blue block with an ink border. */
@Composable
fun Primary(text: String, modifier: Modifier = Modifier, height: Int = 60, enabled: Boolean = true, fill: Color = Blue, onClick: () -> Unit) {
    Box(
        modifier.height(height.dp).background(if (enabled) fill else Paper2).border(3.dp, if (enabled) Ink else Lo, sharp)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), color = if (enabled) Color.White else Lo, fontSize = 17.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1) }
}

/** Secondary action: white block, ink border, blue words. */
@Composable
fun Secondary(text: String, modifier: Modifier = Modifier, height: Int = 60, enabled: Boolean = true, color: Color = Cyan, onClick: () -> Unit) {
    Box(
        modifier.height(height.dp).background(if (enabled) Color.White else Paper2).border(3.dp, if (enabled) Ink else Lo, sharp)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), color = if (enabled) color else Lo, fontSize = 15.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, maxLines = 1) }
}

/** The way back. A real block, not a link: the app hides Android's own back and home buttons. */
@Composable
fun BackButton(text: String, modifier: Modifier = Modifier, height: Int = 40, onClick: () -> Unit) {
    Box(
        modifier.height(height.dp).background(Color.White).border(3.dp, Ink, sharp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text("‹  " + text.uppercase(), color = Cyan, fontSize = 17.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, maxLines = 1) }
}

/** Tertiary action: blue words. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = Cyan, onClick: () -> Unit) =
    Text(text.uppercase(), modifier.clickable(onClick = onClick).padding(vertical = 8.dp), color = color, fontSize = 15.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)

/** Row that navigates somewhere. */
@Composable
fun NavRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().bottomRule(Rule, 2.dp).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
        Text(text.uppercase(), color = Hi, fontSize = 17.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
        Text("›", color = Ink, fontSize = 20.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold)
    }
}

/** Segmented control: one cell inked in. */
@Composable
fun <T> Segmented(options: List<Pair<String, T>>, selected: T?, modifier: Modifier = Modifier, height: Int = 44, cellColor: (T) -> Color = { Hi }, onSelect: (T) -> Unit) {
    Row(modifier.height(height.dp).border(3.dp, Ink, sharp)) {
        options.forEachIndexed { i, (label, v) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).height(height.dp).background(if (on) Ink else Color.Transparent)
                    .then(if (i < options.size - 1) Modifier.rightRule(Ink, 3.dp) else Modifier)
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                val numeric = label.firstOrNull()?.let { it.isDigit() || it == '+' } == true
                Text(
                    label.uppercase(), color = if (on) Paper else cellColor(v), maxLines = 1,
                    fontSize = if (numeric) (height * 0.42f).sp else 15.sp,
                    fontFamily = if (numeric) Anton else Cond, fontWeight = FontWeight.ExtraBold, letterSpacing = if (numeric) 0.sp else 0.8.sp,
                )
            }
        }
    }
}

/** Race-number roundel: a white disc, ink ring, tall numerals. */
@Composable
fun Roundel(text: String, size: Dp, modifier: Modifier = Modifier, caption: String? = null) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White).border(size * 0.075f, Ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Num(text, (size.value * if (caption != null) 0.42f else 0.5f).sp, Ink)
            caption?.let { Text(it.uppercase(), fontSize = (size.value * 0.08f).sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, color = Ink, letterSpacing = 0.5.sp) }
        }
    }
}

/** The checkered strip along the bottom of a screen. */
@Composable
fun Checker(modifier: Modifier, cell: Dp = 12.dp) {
    Canvas(modifier.background(Paper)) {
        val c = cell.toPx()
        var x = 0; var col = 0
        while (x * c < size.width) {
            var y = 0
            while (y * c < size.height) {
                if ((x + y) % 2 == 0) drawRect(Ink, Offset(x * c, y * c), Size(c, c))
                y++
            }
            x++; col++
        }
    }
}

// Compatibility names used across screens
@Composable
fun BigButton(text: String, @Suppress("UNUSED_PARAMETER") color: Color, modifier: Modifier = Modifier, height: Int = 60, enabled: Boolean = true, onClick: () -> Unit) =
    Primary(text, modifier, height, enabled, onClick = onClick)

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = Cyan, onClick: () -> Unit) =
    Secondary(text, modifier, 48, true, color, onClick)

@Composable
fun Chip(text: String, selected: Boolean, color: Color = Hi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).background(if (selected) Ink else Color.White).border(3.dp, Ink, sharp)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = if (selected) Paper else color, fontSize = 14.sp, fontFamily = Cond, fontWeight = FontWeight.ExtraBold, maxLines = 1, letterSpacing = 0.6.sp)
    }
}

fun Modifier.topRule(c: Color, w: Dp = 1.dp) = drawBehind { drawLine(c, Offset(0f, 0f), Offset(size.width, 0f), w.toPx()) }
fun Modifier.bottomRule(c: Color, w: Dp = 1.dp) = drawBehind { drawLine(c, Offset(0f, size.height), Offset(size.width, size.height), w.toPx()) }
fun Modifier.rightRule(c: Color, w: Dp = 1.dp) = drawBehind { drawLine(c, Offset(size.width, 0f), Offset(size.width, size.height), w.toPx()) }

/** Inside [Panes]: size a pane for side-by-side (landscape) or stacked (portrait). */
class PaneScope(val port: Boolean, private val row: androidx.compose.foundation.layout.RowScope?, private val col: ColumnScope?) {
    /** A share of the space: [land] of the width side by side, [portW] of the height when stacked. */
    fun Modifier.share(land: Float, portW: Float = land): Modifier = this.then(
        if (port) with(col!!) { Modifier.weight(portW).fillMaxWidth() }
        else with(row!!) { Modifier.weight(land).fillMaxHeight() }
    )
    /** A fixed-width side pane; stacked, it takes [portW] of the height, or just its content's height. */
    fun Modifier.side(width: androidx.compose.ui.unit.Dp, portW: Float? = null): Modifier = this.then(
        if (port) (if (portW != null) with(col!!) { Modifier.weight(portW).fillMaxWidth() } else Modifier.fillMaxWidth())
        else Modifier.width(width).fillMaxHeight()
    )
}

/** Panes side by side in landscape, top to bottom in portrait, in the same order. */
@Composable
fun Panes(modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f), port: Boolean = isPortrait(), content: @Composable PaneScope.() -> Unit) {
    if (port) Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) { PaneScope(true, null, this).content() }
    else Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) { PaneScope(false, this, null).content() }
}
