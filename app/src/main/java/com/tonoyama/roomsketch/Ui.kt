package com.tonoyama.roomsketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** mm の数値入力。外から値が変わったときだけ表示を書き換える。 */
@Composable
fun NumField(label: String, value: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier, suffix: String = "mm", allowNegative: Boolean = false) {
    var text by remember { mutableStateOf(fmtNum(value)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = fmtNum(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val ok = if (allowNegative) s.matches(Regex("-?\\d*\\.?\\d*")) else s.matches(Regex("\\d*\\.?\\d*"))
            if (ok) { text = s; s.toDoubleOrNull()?.let(onChange) }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun fmtNum(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/**
 * 平面図キャンバス。1本指で要素をドラッグ（hitTest が id を返したとき）、
 * それ以外は1本指でパン・2本指でズーム。
 */
@Composable
fun PlanCanvas(
    project: Project,
    style: PlanStyle,
    view: ViewT?,
    onView: (ViewT) -> Unit,
    modifier: Modifier = Modifier,
    hitTest: (Pt) -> String? = { null },
    onDragStart: (String) -> Unit = {},
    onDrag: (Pt) -> Unit = {},
    onDragEnd: (moved: Boolean) -> Unit = {},
    onTap: (Pt, String?) -> Unit = { _, _ -> },
) {
    val tm = rememberTextMeasurer()
    var boxSize by remember { mutableStateOf(Size.Zero) }
    val cur by rememberUpdatedState(view)
    val hit by rememberUpdatedState(hitTest)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val tap by rememberUpdatedState(onTap)
    val setView by rememberUpdatedState(onView)
    val proj by rememberUpdatedState(project)

    // view が null のとき（初回・「全体表示」）に全体が入るよう合わせる
    LaunchedEffect(boxSize, view == null) {
        if (boxSize.width > 0f && view == null) onView(ViewT.fit(projectBounds(project), boxSize))
    }

    Canvas(
        modifier
            .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v0 = cur ?: ViewT.fit(projectBounds(proj), boxSize)
                    val id = hit(v0.w(down.position))
                    if (id != null) {
                        start(id)
                        var moved = false
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                val d = ch.position - last
                                val sc = (cur ?: v0).scale
                                drag(Pt((d.x / sc).toDouble(), (d.y / sc).toDouble()))
                                last = ch.position
                            }
                            ch.consume()
                        }
                        end(moved)
                        if (!moved) tap(v0.w(down.position), id)
                    } else {
                        var total = 0f
                        var multi = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } > 1) multi = true
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid(useCurrent = false)
                            total += pan.getDistance()
                            if ((zoom != 1f || pan != Offset.Zero) && c != Offset.Unspecified) {
                                val v = cur ?: v0
                                setView(v.transform(c, pan, zoom))
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                        if (!multi && total < viewConfiguration.touchSlop) tap(v0.w(down.position), null)
                    }
                }
            }
    ) {
        val v = view ?: ViewT.fit(projectBounds(project), this.size)
        drawPlan(project, v, tm, style)
    }
}
