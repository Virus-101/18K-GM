package ma.goldmaroc18k.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ma.goldmaroc18k.R
import ma.goldmaroc18k.domain.DataStatus
import ma.goldmaroc18k.domain.GoldMath
import ma.goldmaroc18k.domain.PricePoint
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MA_ZONE = ma.goldmaroc18k.domain.RefreshSchedule.ZONE
private fun fmtTs(i: Instant?): String =
    if (i == null) "—" else DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.getDefault()).withZone(MA_ZONE).format(i)
private fun fmtZ(z: ZonedDateTime): String = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.getDefault()).format(z)
private fun money(v: BigDecimal?, digits: Int = 2): String {
    if (v == null) return "—"
    val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply { minimumFractionDigits = digits; maximumFractionDigits = digits }
    return nf.format(v.setScale(digits, java.math.RoundingMode.HALF_UP))
}
private fun pct(v: Double?): String? = v?.let { String.format(Locale.getDefault(), "%+.2f %%", it) }

@Composable
fun HomeScreen(vm: GoldViewModel) {
    val s by vm.ui.collectAsState()
    val warnings = buildList {
        if (s.freshness.status != DataStatus.UNAVAILABLE) {
            add(stringResource(R.string.warn_gold_delay))
            if (s.goldIsStale) add(stringResource(R.string.warn_gold_stale))
            if (s.freshness.goldMissedSlot) add(stringResource(R.string.warn_missed))
            if (s.freshness.fxOld) add(stringResource(R.string.warn_fx_old))
            if (s.freshness.unsynchronized) add(stringResource(R.string.warn_unsync))
        }
        s.lastError?.let { add(it) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val (label, color) = when (s.freshness.status) {
                DataStatus.UP_TO_DATE -> stringResource(R.string.status_ok) to Color(0xFF2E7D32)
                DataStatus.DELAYED -> stringResource(R.string.status_delayed) to Color(0xFFEF6C00)
                DataStatus.OLD -> stringResource(R.string.status_old) to Color(0xFFC62828)
                DataStatus.UNAVAILABLE -> stringResource(R.string.status_unavailable) to Color(0xFF757575)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 8.dp).size(12.dp).background(color, RoundedCornerShape(6.dp)))
                Text(stringResource(R.string.data_status, label), fontWeight = FontWeight.SemiBold, color = color)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.label_18k), style = MaterialTheme.typography.titleMedium)
                    if (s.p18 == null) {
                        Text(stringResource(R.string.no_data), Modifier.padding(top = 8.dp))
                    } else {
                        Text("${money(s.p18)} ${stringResource(R.string.unit_mad_g)}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        val prev = pct(s.changes.vsPreviousPct) ?: stringResource(R.string.na)
                        val d24 = pct(s.changes.vs24hPct) ?: stringResource(R.string.na)
                        Text(stringResource(R.string.change_prev, prev) + "  ·  " + stringResource(R.string.change_24h, d24), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(Modifier.weight(1f), stringResource(R.string.label_24k), money(s.p24), null)
                InfoCard(Modifier.weight(1f), stringResource(R.string.label_xau), money(s.xauUsd), stringResource(R.string.source_at, fmtTs(s.goldSourceTs)))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(Modifier.weight(1f), stringResource(R.string.label_fx), money(s.usdMad, 4), stringResource(R.string.source_at, fmtTs(s.fxSourceTs)))
                InfoCard(Modifier.weight(1f), stringResource(R.string.last_sync), fmtTs(s.goldCheckedAt), stringResource(R.string.next_refresh, fmtZ(s.nextSlot)))
            }
        }
        if (warnings.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Button(onClick = { vm.refreshNow() }, enabled = !s.refreshing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (s.refreshing) R.string.refreshing else R.string.refresh_now))
            }
            s.lastResult?.let { r ->
                val msg = when {
                    r.fullSuccess && r.goldSkipped && r.fxSkipped -> stringResource(R.string.msg_throttled)
                    r.fullSuccess -> stringResource(R.string.msg_ok)
                    r.partial -> stringResource(R.string.msg_partial, listOfNotNull(r.goldError, r.fxError).joinToString(" / "))
                    else -> stringResource(R.string.msg_fail, listOfNotNull(r.goldError, r.fxError).joinToString(" / "))
                }
                Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Text(stringResource(R.string.chart_title), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(Range.entries.toList()) { r ->
                    FilterChip(selected = s.range == r, onClick = { vm.setRange(r) }, label = { Text(stringResource(r.labelRes)) })
                }
            }
            if (s.chart.size < 2) {
                Text(stringResource(R.string.chart_empty, s.chart.size), style = MaterialTheme.typography.bodySmall)
            } else {
                PriceChart(s.chart)
            }
            Text(stringResource(R.string.chart_note), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            s.firstPointMs?.let { Text(stringResource(R.string.chart_span, fmtTs(Instant.ofEpochMilli(it))), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun InfoCard(modifier: Modifier, title: String, value: String, sub: String?) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, fontSize = 20.sp, maxLines = 2, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PriceChart(points: List<PricePoint>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = points.minOf { it.price18k }
    val max = points.maxOf { it.price18k }
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val t0 = points.first().timeMs
    val tSpan = (points.last().timeMs - t0).coerceAtLeast(1)
    Column {
        Text("${String.format(Locale.getDefault(), "%.2f", max)} MAD/g", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = (p.timeMs - t0).toFloat() / tSpan * size.width
                val y = size.height - ((p.price18k - min) / span).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        Text("${String.format(Locale.getDefault(), "%.2f", min)} MAD/g", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun CalculatorScreen(vm: GoldViewModel) {
    val s by vm.ui.collectAsState()
    var grams by remember { mutableIntStateOf(1) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.calc_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(stringResource(R.string.calc_grams))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(1, 5, 10, 20, 50, 100)) { g -> FilterChip(selected = grams == g, onClick = { grams = g }, label = { Text("$g g") }) }
            }
        }
        val p24 = s.p24
        if (p24 == null) {
            item { Text(stringResource(R.string.no_data)) }
        } else {
            items(listOf(24, 22, 21, 18)) { k ->
                val perG = GoldMath.pricePerGramKarat(p24, k)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("${k}K", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${money(perG)} MAD/g", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${money(GoldMath.total(perG, grams))} MAD", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        item { Text(stringResource(R.string.calc_disclaimer), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun SettingsScreen(
    notificationsEnabled: Boolean, onNotifications: (Boolean) -> Unit,
    above: Double?, below: Double?, onAlerts: (Double?, Double?) -> Unit,
    language: String, onLanguage: (String) -> Unit,
) {
    var aboveText by remember { mutableStateOf(above?.toString() ?: "") }
    var belowText by remember { mutableStateOf(below?.toString() ?: "") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_notifs))
                Switch(checked = notificationsEnabled, onCheckedChange = onNotifications)
            }
        }
        item {
            OutlinedTextField(aboveText, { aboveText = it }, label = { Text(stringResource(R.string.alert_above)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(belowText, { belowText = it }, label = { Text(stringResource(R.string.alert_below)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(onClick = { onAlerts(aboveText.replace(',', '.').toDoubleOrNull(), belowText.replace(',', '.').toDoubleOrNull()) }) {
                Text(stringResource(R.string.save))
            }
        }
        item {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(language == "en", { onLanguage("en") }); Text(stringResource(R.string.lang_en)) }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(language == "fr", { onLanguage("fr") }); Text(stringResource(R.string.lang_fr)) }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(language == "ar", { onLanguage("ar") }); Text(stringResource(R.string.lang_ar)) }
        }
        item {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_schedule), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
