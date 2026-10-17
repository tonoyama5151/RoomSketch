package com.tonoyama.roomsketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** 画面座標 = 世界座標(mm) × scale + offset */
data class ViewT(val scale: Float, val ox: Float, val oy: Float) {
    fun s(p: Pt) = Offset((p.x * scale + ox).toFloat(), (p.y * scale + oy).toFloat())
    fun w(o: Offset) = Pt(((o.x - ox) / scale).toDouble(), ((o.y - oy) / scale).toDouble())

    /** centroid を中心に zoom 倍し、pan だけずらす。 */
    fun transform(centroid: Offset, pan: Offset, zoom: Float): ViewT {
        val ns = (scale * zoom).coerceIn(0.005f, 2f)
        val wx = (centroid.x - ox) / scale; val wy = (centroid.y - oy) / scale
        return ViewT(ns, centroid.x - wx * ns + pan.x, centroid.y - wy * ns + pan.y)
    }

    companion object {
        fun fit(b: Bounds?, size: Size, pad: Float = 48f): ViewT {
            if (b == null || b.w <= 0 || b.h <= 0 || size.width <= 0f) return ViewT(0.08f, size.width / 2, size.height / 2)
            val sc = min((size.width - pad * 2) / b.w.toFloat(), (size.height - pad * 2) / b.h.toFloat()).coerceIn(0.005f, 2f)
            val ox = size.width / 2 - ((b.minX + b.maxX) / 2).toFloat() * sc
            val oy = size.height / 2 - ((b.minY + b.maxY) / 2).toFloat() * sc
            return ViewT(sc, ox, oy)
        }
    }
}

fun projectBounds(p: Project): Bounds? = bounds(p.rooms.flatMap { it.worldVertices() })

data class PlanStyle(
    val showFurniture: Boolean = true,
    val selectedPlacement: String? = null,
    val selectedRoom: String? = null,
    val issues: List<Issue> = emptyList(),
    val wallLabels: Boolean = false,       // 部屋エディタ用：壁番号と長さ
    val dims: List<DimLine> = emptyList(),
    val dimFurniture: Boolean = true,      // 家具名を描く
    val roomLabels: Boolean = true,
    val title: String? = null,             // 画像書き出し用の見出し
)

private val WALL = Color(0xFF37474F)
private val ROOM_FILL = Color(0xFFF7F5EF)
private val ROOM_SEL = Color(0xFFE3F0EA)
private val GRID = Color(0xFFE6E2D8)
private val DOOR = Color(0xFF6D4C41)
private val WINDOW = Color(0xFF4F8FC0)
private val SEL = Color(0xFF1E6FD9)
private val ERR = Color(0xFFD32F2F)
private val WARN = Color(0xFFEF8C00)
private val TEXT = Color(0xFF263238)
private val SUB = Color(0xFF607D8B)

private fun pathOf(t: ViewT, poly: List<Pt>): Path = Path().apply {
    poly.forEachIndexed { i, p -> val o = t.s(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    close()
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, size: Float, color: Color = TEXT, bold: Boolean = false, maxWidth: Int = 600) {
    val style = TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    val r = tm.measure(text, style, constraints = Constraints(maxWidth = max(1, maxWidth)))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

/** 1畳 = 1.62㎡（不動産広告の表示基準） */
fun areaText(poly: List<Pt>): String {
    val m2 = kotlin.math.abs(signedArea(poly)) / 1_000_000.0
    return "%.1f㎡ / %.1f畳".format(m2, m2 / 1.62)
}

fun DrawScope.drawPlan(project: Project, t: ViewT, tm: TextMeasurer, st: PlanStyle) {
    drawRect(Color.White)

    // 1m方眼
    projectBounds(project)?.let { b ->
        val x0 = Math.floor(b.minX / 1000) * 1000; val x1 = Math.ceil(b.maxX / 1000) * 1000
        val y0 = Math.floor(b.minY / 1000) * 1000; val y1 = Math.ceil(b.maxY / 1000) * 1000
        var x = x0
        while (x <= x1) { drawLine(GRID, t.s(Pt(x, y0)), t.s(Pt(x, y1)), 1f); x += 1000 }
        var y = y0
        while (y <= y1) { drawLine(GRID, t.s(Pt(x0, y)), t.s(Pt(x1, y)), 1f); y += 1000 }
    }

    val wallW = max(3f, 100f * t.scale) // 壁厚を見た目上 100mm 程度で描く

    for (r in project.rooms) {
        val v = r.worldVertices()
        if (v.size < 3) continue
        drawPath(pathOf(t, v), if (r.id == st.selectedRoom) ROOM_SEL else ROOM_FILL)
    }
    for (r in project.rooms) {
        val v = r.worldVertices()
        if (v.size < 3) continue
        drawPath(pathOf(t, v), if (r.id == st.selectedRoom) SEL else WALL, style = Stroke(width = wallW, join = androidx.compose.ui.graphics.StrokeJoin.Miter))
        drawOpenings(r, t, wallW)
    }

    // 家具
    if (st.showFurniture) {
        val levels = st.issues.groupBy { it.placementId }.mapValues { (_, l) -> if (l.any { it.level == Level.ERROR }) Level.ERROR else Level.WARN }
        for (pl in project.activeLayout().placements) {
            val f = project.furnitureById(pl.furnitureId) ?: continue
            val poly = furniturePoly(pl, f)
            val sel = pl.id == st.selectedPlacement
            for (z in clearanceZones(pl, f)) {
                drawPath(pathOf(t, z), Color(0x1A1E6FD9))
                drawPath(pathOf(t, z), Color(0x661E6FD9), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
            val base = Color(FURNITURE_COLORS[f.color.mod(FURNITURE_COLORS.size)])
            drawPath(pathOf(t, poly), base.copy(alpha = if (f.status == FurnitureStatus.CANDIDATE) 0.45f else 0.85f))
            val edge = when {
                sel -> SEL
                levels[pl.id] == Level.ERROR -> ERR
                levels[pl.id] == Level.WARN -> WARN
                else -> Color(0xFF5D4037)
            }
            val ew = if (sel || levels.containsKey(pl.id)) 4f else 1.5f
            val dash = if (f.status == FurnitureStatus.CANDIDATE) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null
            drawPath(pathOf(t, poly), edge, style = Stroke(ew, pathEffect = dash))
            // 前面の向き（短い線）
            val fr = frameOf(pl)
            if (f.shape == FurnitureShape.RECT) {
                val a = fr.c + fr.uy * (f.d / 2) - fr.ux * (f.w / 2 * 0.6)
                val b = fr.c + fr.uy * (f.d / 2) + fr.ux * (f.w / 2 * 0.6)
                drawLine(edge.copy(alpha = 0.7f), t.s(a), t.s(b), 3f, cap = StrokeCap.Round)
            }
            if (st.dimFurniture) {
                val px = (min(f.w, f.d) * t.scale).toFloat()
                if (px > 28f) {
                    val maxW = (max(f.w, f.d) * t.scale).toInt()
                    label(tm, f.name, t.s(fr.c), 11f, TEXT, bold = sel, maxWidth = maxW)
                }
            }
        }
    }

    // 部屋名
    if (st.roomLabels) for (r in project.rooms) {
        val v = r.worldVertices()
        if (v.size < 3) continue
        val c = labelPoint(v)
        label(tm, r.name, t.s(c) - Offset(0f, 9f), 13f, TEXT, bold = true)
        label(tm, areaText(v), t.s(c) + Offset(0f, 10f), 10f, SUB)
    }

    if (st.wallLabels) for (r in project.rooms) drawWallLabels(r, t, tm)

    // 寸法線
    for (d in st.dims) {
        val a = t.s(d.from); val b = t.s(d.to)
        drawLine(SEL, a, b, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        drawCircle(SEL, 4f, b)
        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
        val txt = fmtCm(d.length)
        val style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        val res = tm.measure(txt, style)
        drawRoundRect(SEL, topLeft = Offset(mid.x - res.size.width / 2f - 5f, mid.y - res.size.height / 2f - 2f),
            size = Size(res.size.width + 10f, res.size.height + 4f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
        drawText(res, topLeft = Offset(mid.x - res.size.width / 2f, mid.y - res.size.height / 2f))
    }

    st.title?.let {
        val r = tm.measure(it, TextStyle(fontSize = 16.sp, color = TEXT, fontWeight = FontWeight.SemiBold))
        drawText(r, topLeft = Offset(32f, 24f))
    }
}

/** 凹んだ部屋でも内側に入る、ラベル位置。重心が外なら内側の点を探す。 */
fun labelPoint(v: List<Pt>): Pt {
    val c = centroid(v)
    if (pointInPolygon(c, v)) return c
    val b = bounds(v) ?: return c
    var best = c; var bestD = -1.0
    for (i in 1..9) for (j in 1..9) {
        val p = Pt(b.minX + b.w * i / 10, b.minY + b.h * j / 10)
        if (!pointInPolygon(p, v)) continue
        val d = walls(Room(vertices = v)).minOf { w -> distToSeg(p, w.a, w.b) }
        if (d > bestD) { bestD = d; best = p }
    }
    return best
}

fun distToSeg(p: Pt, a: Pt, b: Pt): Double {
    val ab = b - a
    val l2 = ab.dot(ab)
    if (l2 == 0.0) return (p - a).len()
    val t = ((p - a).dot(ab) / l2).coerceIn(0.0, 1.0)
    return (p - (a + ab * t)).len()
}

private fun DrawScope.drawOpenings(r: Room, t: ViewT, wallW: Float) {
    val ws = walls(r)
    for (o in r.openings) {
        val w = ws.getOrNull(o.wallIndex) ?: continue
        val (p0, p1) = openingSegment(r, o) ?: continue
        when (o.type) {
            OpeningType.DOOR_SWING -> {
                drawLine(ROOM_FILL, t.s(p0), t.s(p1), wallW + 1f)
                val sw = doorSwing(r, o) ?: continue
                val path = Path().apply {
                    val h = t.s(sw[0]); moveTo(h.x, h.y)
                    sw.drop(1).forEach { val q = t.s(it); lineTo(q.x, q.y) }
                    close()
                }
                drawPath(path, Color(0x146D4C41))
                // 扉の板（開いた状態）と軌跡
                drawLine(DOOR, t.s(sw[0]), t.s(sw.last()), 3f)
                val arc = Path().apply { sw.drop(1).forEachIndexed { i, q -> val s = t.s(q); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) } }
                drawPath(arc, DOOR, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
            }
            OpeningType.DOOR_SLIDING -> {
                drawLine(ROOM_FILL, t.s(p0), t.s(p1), wallW + 1f)
                val off = w.inward * 40.0
                drawLine(DOOR, t.s(p0 + off), t.s(p0 + (p1 - p0) * 0.55 + off), 4f)
                drawLine(DOOR, t.s(p0 + (p1 - p0) * 0.45 - off), t.s(p1 - off), 4f)
            }
            OpeningType.WINDOW -> {
                drawLine(Color.White, t.s(p0), t.s(p1), wallW)
                drawLine(WINDOW, t.s(p0 + w.inward * 25.0), t.s(p1 + w.inward * 25.0), 2f)
                drawLine(WINDOW, t.s(p0 - w.inward * 25.0), t.s(p1 - w.inward * 25.0), 2f)
            }
            OpeningType.CLOSET -> {
                drawLine(ROOM_FILL, t.s(p0), t.s(p1), wallW + 1f)
                drawLine(DOOR, t.s(p0), t.s(p1), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                if (o.depth > 0) wallBox(r, o, o.depth)?.let { drawPath(pathOf(t, it), Color(0x106D4C41)) }
            }
            OpeningType.PILLAR -> wallBox(r, o, max(o.depth, 1.0))?.let { drawPath(pathOf(t, it), WALL) }
            OpeningType.OUTLET -> {
                val c = (p0 + p1) * 0.5 + w.inward * 60.0
                drawCircle(Color(0xFFFFB300), max(4f, 50f * t.scale), t.s(c))
            }
            OpeningType.AIRCON -> wallBox(r, o, 250.0)?.let {
                drawPath(pathOf(t, it), Color(0x554F8FC0))
                drawPath(pathOf(t, it), WINDOW, style = Stroke(1.5f))
            }
        }
    }
}

private fun DrawScope.drawWallLabels(r: Room, t: ViewT, tm: TextMeasurer) {
    walls(r).forEachIndexed { i, w ->
        val mid = (w.a + w.b) * 0.5
        val inPt = mid + w.inward * (28.0 / t.scale)
        val outPt = mid - w.inward * (26.0 / t.scale)
        // 壁の向き（始点→終点）の矢印
        val a = t.s(inPt - w.dir * (14.0 / t.scale)); val b = t.s(inPt + w.dir * (14.0 / t.scale))
        drawLine(SEL, a, b, 2f)
        val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        for (s in listOf(-1, 1)) {
            val aa = ang + PI + s * 0.5
            drawLine(SEL, b, Offset(b.x + (8 * kotlin.math.cos(aa)).toFloat(), b.y + (8 * kotlin.math.sin(aa)).toFloat()), 2f)
        }
        label(tm, "${i + 1}", t.s(inPt) + Offset(0f, -14f), 11f, SEL, bold = true)
        label(tm, fmtCm(w.length), t.s(outPt), 10f, SUB)
    }
    // 始点（壁1の始点）の印
    walls(r).firstOrNull()?.let { drawCircle(SEL, 6f, t.s(it.a)) }
}
