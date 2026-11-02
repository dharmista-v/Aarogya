package com.example.aarogya.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aarogya.OverallUi
import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.Meal
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Chart colours for the Overall view: dataviz reference slots 1–2 (blue = in / deficit,
 * orange = out / surplus), validated as a pair in both modes. Orange sits at 2.8:1 on the light
 * card, so every chart also carries text values and the weekly table (the "relief" rule).
 */
private object ChartColors {
    val blue @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val orange @Composable get() = if (isSystemInDarkTheme()) Color(0xFFD95926) else Color(0xFFEB6834)
}

private val weekFmt = DateTimeFormatter.ofPattern("d MMM")

private fun signed(v: Double): String {
    val n = v.roundToInt()
    return when { n > 0 -> "+${n.kcal()}"; n < 0 -> "−${(-n).kcal()}"; else -> "0" }
}

/** The Overall view's items, appended to the Trends list. */
fun LazyListScope.overallItems(ui: OverallUi) {
    val o = ui.overall
    if (o == null) {
        item(key = "o-empty") {
            Text(
                "Log meals on a few days (with WHOOP, a base burn or activities for the “out” side) to see your all-time trends.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    item(key = "o-head") { HeadlineCard(o, ui) }
    if (o.weeks.size >= 2) {
        item(key = "o-lines") { ChartCard("Weekly average: in vs out", "kcal per day") { WeeklyLines(o.weeks) } }
        item(key = "o-balance") { ChartCard("Weekly balance", "average daily deficit or surplus") { BalanceBars(o.weeks) } }
    }
    item(key = "o-dow") { ChartCard("Intake by day of week", "average kcal on days with food logged") { WeekdayBars(o.byWeekday) } }
    if (ui.byCategory.isNotEmpty()) item(key = "o-types") { ChartCard("By food type", "share of all logged calories") { FoodTypes(ui.byCategory) } }
    if (ui.mealTotals.isNotEmpty()) item(key = "o-meals") { ChartCard("Where calories come from", "share of all logged calories") { MealShares(ui.mealTotals) } }
    if (ui.topFoods.isNotEmpty()) item(key = "o-foods") { ChartCard("Top foods", "by total calories") { TopFoods(ui.topFoods) } }
    item(key = "o-table-h") { SectionTitle("Weekly table", "in · out · net") }
    o.weeks.reversed().forEach { w ->
        item(key = "o-w${w.weekStart}") {
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.4f)) {
                        Text("Week of ${w.weekStart.format(weekFmt)}", style = MaterialTheme.typography.bodyMedium)
                        Text("${w.days} day${if (w.days == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(w.avgIn.kcal(), Modifier.weight(0.7f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                    Text(w.avgOut.kcal(), Modifier.weight(0.7f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                    Text(signed(w.avgNet), Modifier.weight(0.7f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun HeadlineCard(o: Analyzer.Overall, ui: OverallUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val kg = o.estimatedKgChange
            Text(
                (if (kg <= 0) "−" else "+") + "%.1f kg".format(abs(kg)),
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "estimated from your calorie balance" + (ui.firstDay?.let { " since ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}" } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Days", o.completeDays.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Stat("Avg in", o.avgIn.kcal(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Stat("Avg out", o.avgOut.kcal(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Stat("Avg net", signed(o.avgNet), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Caption line showing the tapped point's exact values (the mobile stand-in for a hover tooltip). */
@Composable
private fun Caption(text: String) {
    Text(text, Modifier.padding(bottom = 8.dp).height(20.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Tap index across [count] equal slots of a chart's width. */
private fun Modifier.tapSlot(count: Int, onSlot: (Int) -> Unit) = pointerInput(count) {
    detectTapGestures { p -> onSlot((p.x / size.width * count).toInt().coerceIn(0, count - 1)) }
}

@Composable
private fun WeeklyLines(weeks: List<Analyzer.WeekStat>) {
    val inC = ChartColors.blue
    val outC = ChartColors.orange
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    var sel by remember(weeks) { mutableStateOf(weeks.lastIndex) }
    val w = weeks[sel]
    Caption("Week of ${w.weekStart.format(weekFmt)}: in ${w.avgIn.kcal()} · out ${w.avgOut.kcal()} · net ${signed(w.avgNet)}")
    val max = weeks.maxOf { maxOf(it.avgIn, it.avgOut) } * 1.1
    Canvas(Modifier.fillMaxWidth().height(170.dp).tapSlot(weeks.size) { sel = it }) {
        val slot = size.width / weeks.size
        fun x(i: Int) = slot * i + slot / 2
        fun y(v: Double) = size.height * (1 - (v / max).toFloat())
        for (f in listOf(0.0, 0.5, 1.0)) {
            val gy = y(max / 1.1 * f)
            drawLine(grid.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
        }
        drawLine(grid, Offset(x(sel), 0f), Offset(x(sel), size.height), 1.dp.toPx()) // crosshair
        for ((color, value) in listOf(inC to { s: Analyzer.WeekStat -> s.avgIn }, outC to { s: Analyzer.WeekStat -> s.avgOut })) {
            val path = Path()
            weeks.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(value(s))) else path.lineTo(x(i), y(value(s))) }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            weeks.forEachIndexed { i, s ->
                val r = if (i == sel) 5.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, r + 2.dp.toPx(), Offset(x(i), y(value(s)))) // 2px surface ring
                drawCircle(color, r, Offset(x(i), y(value(s))))
            }
        }
    }
    AxisLabels(weeks.map { it.weekStart.format(weekFmt) })
    Legend(listOf(inC to "In (eaten)", outC to "Out (burned)"))
}

@Composable
private fun BalanceBars(weeks: List<Analyzer.WeekStat>) {
    val deficit = ChartColors.blue
    val surplus = ChartColors.orange
    val zero = MaterialTheme.colorScheme.outline
    var sel by remember(weeks) { mutableStateOf(weeks.lastIndex) }
    val w = weeks[sel]
    Caption("Week of ${w.weekStart.format(weekFmt)}: ${if (w.avgNet <= 0) "deficit" else "surplus"} ${signed(w.avgNet)} kcal/day")
    val extent = weeks.maxOf { abs(it.avgNet) }.coerceAtLeast(100.0) * 1.1
    Canvas(Modifier.fillMaxWidth().height(150.dp).tapSlot(weeks.size) { sel = it }) {
        val slot = size.width / weeks.size
        val bar = (slot - 2.dp.toPx()).coerceAtMost(18.dp.toPx())
        val mid = size.height / 2
        weeks.forEachIndexed { i, s ->
            val h = (abs(s.avgNet) / extent).toFloat() * mid
            val left = slot * i + (slot - bar) / 2
            val color = (if (s.avgNet <= 0) deficit else surplus).let { if (i == sel) it else it.copy(alpha = 0.75f) }
            val top = if (s.avgNet > 0) mid - h else mid
            if (h > 0) drawRoundRect(color, Offset(left, top), Size(bar, h), CornerRadius(4.dp.toPx()))
        }
        drawLine(zero, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx()) // neutral midpoint
    }
    AxisLabels(weeks.map { it.weekStart.format(weekFmt) })
    Legend(listOf(deficit to "Deficit (below 0)", surplus to "Surplus (above 0)"))
}

@Composable
private fun WeekdayBars(byWeekday: List<Double?>) {
    val color = ChartColors.blue
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val max = byWeekday.filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    var sel by remember(byWeekday) { mutableStateOf(byWeekday.indices.maxBy { byWeekday[it] ?: -1.0 }) }
    Caption("${names[sel]}: " + (byWeekday[sel]?.let { "${it.kcal()} kcal on average" } ?: "nothing logged yet"))
    Canvas(Modifier.fillMaxWidth().height(130.dp).tapSlot(7) { sel = it }) {
        val slot = size.width / 7
        val bar = (slot * 0.55f).coerceAtMost(28.dp.toPx())
        byWeekday.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val h = (v / (max * 1.1)).toFloat() * size.height
            drawRoundRect(
                if (i == sel) color else color.copy(alpha = 0.7f),
                Offset(slot * i + (slot - bar) / 2, size.height - h), Size(bar, h), CornerRadius(4.dp.toPx()),
            )
        }
    }
    AxisLabels(names.map { it.first().toString() }, maxShown = 7) // all seven days fit
}

@Composable
private fun AxisLabels(labels: List<String>, maxShown: Int = 6) {
    // Thin out labels so they never collide: at most [maxShown] shown.
    val step = ((labels.size + maxShown - 1) / maxShown).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        labels.forEachIndexed { i, l ->
            Text(
                if (i % step == 0 || i == labels.lastIndex) l else "", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
    }
}

/** Horizontal bar rows: label · bar · value text (single hue; values always written out). */
@Composable
private fun BarRow(label: String, fraction: Float, value: String) {
    val color = ChartColors.blue
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(track, size = size, cornerRadius = CornerRadius(4.dp.toPx()))
            drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(4.dp.toPx()))
        }
        Text(value, Modifier.width(104.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun MealShares(totals: Map<Meal, Double>) {
    val sum = totals.values.sum().coerceAtLeast(1.0)
    Meal.entries.forEach { m ->
        val v = totals[m] ?: 0.0
        BarRow(m.label, (v / sum).toFloat(), "${(v / sum * 100).roundToInt()}% · ${v.kcal()}")
    }
}

@Composable
private fun FoodTypes(byCategory: List<Pair<com.example.aarogya.data.FoodCategory, Double>>) {
    val sum = byCategory.sumOf { it.second }.coerceAtLeast(1.0)
    byCategory.forEach { (c, v) -> BarRow(c.label, (v / sum).toFloat(), "${(v / sum * 100).roundToInt()}% · ${v.kcal()}") }
}

@Composable
private fun TopFoods(foods: List<com.example.aarogya.data.Store.FoodStat>) {
    val max = foods.maxOf { it.kcal }.coerceAtLeast(1.0)
    foods.forEach { f ->
        BarRow(f.name.replaceFirstChar { it.uppercase() }, (f.kcal / max).toFloat(), "${f.kcal.kcal()} · ${f.times}×")
    }
}
