package com.tonoyama.roomsketch

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 画面座標 = 世界座標(mm) × scale + offset */
data class ViewT(val scale: Float, val ox: Float, val oy: Float) {
    fun s(p: Pt) = Offset((p.x * scale + ox).toFloat(), (p.y * scale + oy).toFloat())
    fun w(o: Offset) = Pt(((o.x - ox) / scale).toDouble(), ((o.y - oy) / scale).toDouble())

    /** centroid を中心に zoom 倍し、pan だけずらす。 */
    fun transform(centroid: Offset, pan: Offset, zoom: Float): ViewT {
        val ns = (scale * zoom).coerceIn(0.005f, 3f)
        val wx = (centroid.x - ox) / scale; val wy = (centroid.y - oy) / scale
        return ViewT(ns, centroid.x - wx * ns + pan.x, centroid.y - wy * ns + pan.y)
    }

    companion object {
        fun fit(b: Bounds?, size: Size, pad: Float = 48f): ViewT {
            if (b == null || b.w <= 0 || b.h <= 0 || size.width <= 0f) return ViewT(0.08f, size.width / 2, size.height / 2)
            val sc = min((size.width - pad * 2) / b.w.toFloat(), (size.height - pad * 2) / b.h.toFloat()).coerceIn(0.005f, 3f)
            val ox = size.width / 2 - ((b.minX + b.maxX) / 2).toFloat() * sc
            val oy = size.height / 2 - ((b.minY + b.maxY) / 2).toFloat() * sc
            return ViewT(sc, ox, oy)
        }
    }
}

fun projectBounds(p: Project): Bounds? = bounds(p.rooms.flatMap { it.worldVertices() } + p.partitions.flatMap { listOf(it.a, it.b) })

/** 全体表示の範囲。部屋がなければ下絵の範囲。 */
fun fitBounds(p: Project): Bounds? {
    val pts = p.rooms.flatMap { it.worldVertices() } + p.partitions.flatMap { listOf(it.a, it.b) }
    if (pts.isNotEmpty()) return bounds(pts)
    val bg = p.background ?: return null
    return Bounds(bg.x, bg.y, bg.x + bg.wPx * bg.mmPerPx, bg.y + bg.hPx * bg.mmPerPx)
}

data class PlanStyle(
    val showFurniture: Boolean = true,
    val selectedPlacement: String? = null,
    val selectedRoom: String? = null,
    val selectedPartition: String? = null,
    val issues: List<Issue> = emptyList(),
    val wallLabels: Boolean = false,       // 部屋エディタ用：壁番号と長さ
    val dims: List<DimLine> = emptyList(),
    val dimFurniture: Boolean = true,      // 家具名を描く
    val roomLabels: Boolean = true,
    val title: String? = null,             // 画像書き出し用の見出し
    val bg: ImageBitmap? = null,           // 下絵
    val draft: List<Pt> = emptyList(),     // なぞり中の点
    val handles: List<Pt> = emptyList(),   // ドラッグできる点（角・間仕切りの端）
    val selectedHandle: Pt? = null,
    val selectedSegment: Pair<Pt, Pt>? = null,
    val marks: List<Pt> = emptyList(),     // 縮尺合わせの目印
    val partitionLabels: Boolean = false,
)

private val WALL = Color(0xFF37474F)
private val GRID = Color(0xFFE6E2D8)
private val DOOR = Color(0xFF6D4C41)
private val WINDOW = Color(0xFF4F8FC0)
private val SEL = Color(0xFF1E6FD9)
private val ERR = Color(0xFFD32F2F)
private val WARN = Color(0xFFEF8C00)
private val TEXT = Color(0xFF263238)
private val SUB = Color(0xFF607D8B)
private val DRAFT = Color(0xFFE91E63)

fun roomFill(r: Room) = Color(r.kind.fill)

private fun pathOf(t: ViewT, poly: List<Pt>): Path = Path().apply {
    poly.forEachIndexed { i, p -> val o = t.s(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    close()
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, size: Float, color: Color = TEXT, bold: Boolean = false, maxWidth: Int = 600) {
    val style = TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    val r = tm.measure(text, style, constraints = Constraints(maxWidth = max(1, maxWidth)))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

private fun DrawScope.pill(tm: TextMeasurer, text: String, center: Offset, bg: Color) {
    val res = tm.measure(text, TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
    drawRoundRect(bg, topLeft = Offset(center.x - res.size.width / 2f - 5f, center.y - res.size.height / 2f - 2f),
        size = Size(res.size.width + 10f, res.size.height + 4f), cornerRadius = CornerRadius(8f))
    drawText(res, topLeft = Offset(center.x - res.size.width / 2f, center.y - res.size.height / 2f))
}

/** 1畳 = 1.62㎡（不動産広告の表示基準） */
fun areaText(poly: List<Pt>): String {
    val m2 = kotlin.math.abs(signedArea(poly)) / 1_000_000.0
    return "%.1f㎡ / %.1f畳".format(m2, m2 / 1.62)
}

fun DrawScope.drawPlan(project: Project, t: ViewT, tm: TextMeasurer, st: PlanStyle) {
    drawRect(Color.White)

    // 下絵
    val bgd = project.background
    if (st.bg != null && bgd != null && bgd.visible) {
        val tl = t.s(Pt(bgd.x, bgd.y))
        val wpx = (bgd.wPx * bgd.mmPerPx * t.scale).roundToInt()
        val hpx = (bgd.hPx * bgd.mmPerPx * t.scale).roundToInt()
        if (wpx > 0 && hpx > 0) drawImage(st.bg, srcOffset = IntOffset.Zero, srcSize = IntSize(st.bg.width, st.bg.height),
            dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = IntSize(wpx, hpx), alpha = bgd.opacity)
    }

    // 1m方眼
    projectBounds(project)?.let { b ->
        val x0 = Math.floor(b.minX / 1000) * 1000; val x1 = Math.ceil(b.maxX / 1000) * 1000
        val y0 = Math.floor(b.minY / 1000) * 1000; val y1 = Math.ceil(b.maxY / 1000) * 1000
        var x = x0
        while (x <= x1) { drawLine(GRID, t.s(Pt(x, y0)), t.s(Pt(x, y1)), 1f); x += 1000 }
        var y = y0
        while (y <= y1) { drawLine(GRID, t.s(Pt(x0, y)), t.s(Pt(x1, y)), 1f); y += 1000 }
    }

    val wt = project.wallThickness.coerceAtLeast(20.0)
    val wallPx = max(2f, (wt * t.scale).toFloat())
    val alpha = if (st.bg != null && bgd?.visible == true) 0.75f else 1f

    // 壁は部屋の外側に描く：塗り → 太線（中心が部屋の輪郭）→ もう一度塗り で内側半分を隠す
    for (r in project.rooms) if (r.vertices.size >= 3) drawPath(pathOf(t, r.worldVertices()), roomFill(r).copy(alpha = alpha))
    for (r in project.rooms) if (r.vertices.size >= 3) {
        drawPath(pathOf(t, r.worldVertices()), if (r.id == st.selectedRoom) SEL else WALL, style = Stroke(width = wallPx, join = StrokeJoin.Miter))
    }
    for (r in project.rooms) if (r.vertices.size >= 3) {
        val fill = roomFill(r)
        drawPath(pathOf(t, r.worldVertices()), if (r.id == st.selectedRoom) Color(0xFFE3F0EA) else fill)
        if (r.kind == RoomKind.TATAMI) drawTatamiHatch(r, t)
    }
    if (st.selectedRoom != null) project.rooms.firstOrNull { it.id == st.selectedRoom }?.let {
        drawPath(pathOf(t, it.worldVertices()), SEL, style = Stroke(width = 3f))
    }

    // 間仕切り
    for (p in project.partitions) drawPartition(p, t, p.id == st.selectedPartition)

    // ドア・窓（全部の壁を描いた後に描くので、隣の部屋の壁も抜ける）
    for (r in project.rooms) drawOpenings(r, t, wt)
    for (p in project.partitions) drawPartitionOpenings(p, t)

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
            val fr = frameOf(pl)
            if (f.shape == FurnitureShape.RECT) {
                val a = fr.c + fr.uy * (f.d / 2) - fr.ux * (f.w / 2 * 0.6)
                val b = fr.c + fr.uy * (f.d / 2) + fr.ux * (f.w / 2 * 0.6)
                drawLine(edge.copy(alpha = 0.7f), t.s(a), t.s(b), 3f, cap = StrokeCap.Round)
            }
            if (st.dimFurniture) {
                val px = (min(f.w, f.d) * t.scale).toFloat()
                if (px > 28f) label(tm, f.name, t.s(fr.c), 11f, TEXT, bold = sel, maxWidth = (max(f.w, f.d) * t.scale).toInt())
            }
        }
    }

    // 部屋名
    if (st.roomLabels) for (r in project.rooms) {
        val v = r.worldVertices()
        if (v.size < 3) continue
        val c = labelPoint(v)
        val big = kotlin.math.abs(signedArea(v)) * t.scale * t.scale > 9000
        label(tm, r.name, t.s(c) - Offset(0f, if (big) 9f else 0f), if (big) 13f else 10f, TEXT, bold = true)
        if (big && r.kind != RoomKind.STORAGE) label(tm, areaText(v), t.s(c) + Offset(0f, 10f), 10f, SUB)
    }

    if (st.wallLabels) for (r in project.rooms) drawWallLabels(r, t, tm)
    if (st.partitionLabels) for (p in project.partitions) {
        val mid = (p.a + p.b) * 0.5
        pill(tm, fmtCm((p.b - p.a).len()), t.s(mid), if (p.id == st.selectedPartition) SEL else SUB)
    }

    // 寸法線
    for (d in st.dims) {
        val a = t.s(d.from); val b = t.s(d.to)
        drawLine(SEL, a, b, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        drawCircle(SEL, 4f, b)
        pill(tm, fmtCm(d.length), Offset((a.x + b.x) / 2, (a.y + b.y) / 2), SEL)
    }

    // 編集用のつまみ・選択中の線
    st.selectedSegment?.let { (a, b) -> drawLine(SEL.copy(alpha = 0.5f), t.s(a), t.s(b), 10f, cap = StrokeCap.Round) }
    for (h in st.handles) {
        val o = t.s(h)
        val sel = st.selectedHandle?.let { (it - h).len() < 0.5 } == true
        drawCircle(Color.White, if (sel) 12f else 9f, o)
        drawCircle(SEL, if (sel) 12f else 9f, o, style = Stroke(3f))
        if (sel) drawCircle(SEL, 5f, o)
    }

    // なぞり中の線
    if (st.draft.isNotEmpty()) {
        val path = Path().apply { st.draft.forEachIndexed { i, p -> val o = t.s(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path, DRAFT, style = Stroke(4f, join = StrokeJoin.Round))
        if (st.draft.size >= 3) drawLine(DRAFT.copy(alpha = 0.4f), t.s(st.draft.last()), t.s(st.draft.first()), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        st.draft.forEachIndexed { i, p -> drawCircle(if (i == 0) DRAFT else Color.White, 8f, t.s(p)); drawCircle(DRAFT, 8f, t.s(p), style = Stroke(3f)) }
        for (i in 1 until st.draft.size) {
            val a = st.draft[i - 1]; val b = st.draft[i]
            pill(tm, fmtCm((b - a).len()), t.s((a + b) * 0.5), DRAFT)
        }
    }
    for (m in st.marks) {
        val o = t.s(m)
        drawLine(DRAFT, o - Offset(16f, 0f), o + Offset(16f, 0f), 3f)
        drawLine(DRAFT, o - Offset(0f, 16f), o + Offset(0f, 16f), 3f)
    }
    if (st.marks.size == 2) drawLine(DRAFT, t.s(st.marks[0]), t.s(st.marks[1]), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))

    st.title?.let {
        val r = tm.measure(it, TextStyle(fontSize = 16.sp, color = TEXT, fontWeight = FontWeight.SemiBold))
        drawText(r, topLeft = Offset(32f, 24f))
    }
}

private fun DrawScope.drawTatamiHatch(r: Room, t: ViewT) {
    val b = bounds(r.worldVertices()) ?: return
    // 畳の目の代わりに薄い縦線
    var x = b.minX + 450
    val c = Color(0x2233691E)
    while (x < b.maxX) {
        val a = Pt(x, b.minY); val e = Pt(x, b.maxY)
        if (pointInPolygon(Pt(x, (b.minY + b.maxY) / 2), r.worldVertices())) drawLine(c, t.s(a), t.s(e), 1f)
        x += 450
    }
}

/** 凹んだ部屋でも内側に入る、ラベル位置。重心が外なら内側の点を探す。 */
fun labelPoint(v: List<Pt>): Pt {
    val c = centroid(v)
    val ws = walls(Room(vertices = v))
    fun clearance(p: Pt) = ws.minOf { w -> distToSeg(p, w.a, w.b) }
    val b = bounds(v) ?: return c
    var best = c; var bestD = if (pointInPolygon(c, v)) clearance(c) else -1.0
    if (bestD > min(b.w, b.h) * 0.3) return c
    for (i in 1..11) for (j in 1..11) {
        val p = Pt(b.minX + b.w * i / 12, b.minY + b.h * j / 12)
        if (!pointInPolygon(p, v)) continue
        val d = clearance(p)
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

private fun DrawScope.drawPartition(p: Partition, t: ViewT, selected: Boolean) {
    val color = when (p.kind) {
        PartitionKind.WALL -> WALL
        PartitionKind.LOW_WALL -> Color(0xFF8D6E63)
        PartitionKind.SLIDING -> Color(0xFFBCAAA4)
    }
    for (s in partitionSolids(p)) {
        drawPath(pathOf(t, s), if (p.kind == PartitionKind.SLIDING) color.copy(alpha = 0.6f) else color)
        if (p.kind == PartitionKind.SLIDING) {
            // 引き戸の間仕切り：2本のレール線
            val w = partitionWall(p)
            val a = s[0] + w.inward * (p.thickness * 0.25); val b = s[1] + w.inward * (p.thickness * 0.25)
            drawLine(DOOR, t.s(a), t.s(b), 2f)
            drawLine(DOOR, t.s(a + w.inward * (p.thickness * 0.5)), t.s(b + w.inward * (p.thickness * 0.5)), 2f)
        }
    }
    if (selected) drawLine(SEL, t.s(p.a), t.s(p.b), max(4f, (p.thickness * t.scale).toFloat() + 4f).coerceAtMost(30f), cap = StrokeCap.Round, alpha = 0.35f)
}

private fun DrawScope.drawDoorSwing(sw: List<Pt>, t: ViewT) {
    val path = Path().apply {
        val h = t.s(sw[0]); moveTo(h.x, h.y)
        sw.drop(1).forEach { val q = t.s(it); lineTo(q.x, q.y) }
        close()
    }
    drawPath(path, Color(0x146D4C41))
    drawLine(DOOR, t.s(sw[0]), t.s(sw.last()), 3f)
    val arc = Path().apply { sw.drop(1).forEachIndexed { i, q -> val s = t.s(q); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) } }
    drawPath(arc, DOOR, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
}

private fun DrawScope.drawOpenings(r: Room, t: ViewT, wt: Double) {
    val ws = walls(r)
    val fill = roomFill(r)
    for (o in r.openings) {
        val w = ws.getOrNull(o.wallIndex) ?: continue
        val (p0, p1) = openingSegment(w, o)
        // 壁の外側（厚み）の中心線
        val out = w.inward * (-wt / 2)
        val gapPx = (wt * t.scale).toFloat() + 3f
        fun cutWall(color: Color) = drawLine(color, t.s(p0 + out), t.s(p1 + out), gapPx)
        when (o.type) {
            OpeningType.DOOR_SWING -> {
                cutWall(fill)
                doorSwing(w, o)?.let { drawDoorSwing(it, t) }
            }
            OpeningType.DOOR_SLIDING -> {
                cutWall(fill)
                val a = w.inward * (-wt * 0.25); val b = w.inward * (-wt * 0.75)
                drawLine(DOOR, t.s(p0 + a), t.s(p0 + (p1 - p0) * 0.55 + a), 4f)
                drawLine(DOOR, t.s(p0 + (p1 - p0) * 0.45 + b), t.s(p1 + b), 4f)
            }
            OpeningType.WINDOW -> {
                cutWall(Color.White)
                drawLine(WINDOW, t.s(p0 + w.inward * (-wt * 0.2)), t.s(p1 + w.inward * (-wt * 0.2)), 2f)
                drawLine(WINDOW, t.s(p0 + w.inward * (-wt * 0.8)), t.s(p1 + w.inward * (-wt * 0.8)), 2f)
            }
            OpeningType.CLOSET -> {
                cutWall(fill)
                drawLine(DOOR, t.s(p0 + out), t.s(p1 + out), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                if (o.depth > 0) drawPath(pathOf(t, wallBox(w, o, o.depth)), Color(0x106D4C41))
            }
            OpeningType.PILLAR -> drawPath(pathOf(t, wallBox(w, o, max(o.depth, 1.0))), WALL)
            OpeningType.OUTLET -> drawCircle(Color(0xFFFFB300), max(4f, 50f * t.scale), t.s((p0 + p1) * 0.5 + w.inward * 60.0))
            OpeningType.AIRCON -> {
                val box = wallBox(w, o, 250.0)
                drawPath(pathOf(t, box), Color(0x554F8FC0))
                drawPath(pathOf(t, box), WINDOW, style = Stroke(1.5f))
            }
        }
    }
}

private fun DrawScope.drawPartitionOpenings(p: Partition, t: ViewT) {
    val w = partitionWall(p)
    for ((o, seg) in partitionOpenings(p)) {
        val (p0, p1) = seg
        when (o.type) {
            OpeningType.DOOR_SWING -> doorSwing(w, o)?.let { drawDoorSwing(it, t) }
            OpeningType.DOOR_SLIDING, OpeningType.CLOSET -> {
                val a = w.inward * (p.thickness * 0.25); val b = w.inward * (-p.thickness * 0.25)
                drawLine(DOOR, t.s(p0 + a), t.s(p0 + (p1 - p0) * 0.55 + a), 4f)
                drawLine(DOOR, t.s(p0 + (p1 - p0) * 0.45 + b), t.s(p1 + b), 4f)
            }
            else -> {}
        }
    }
}

private fun DrawScope.drawWallLabels(r: Room, t: ViewT, tm: TextMeasurer) {
    walls(r).forEachIndexed { i, w ->
        val mid = (w.a + w.b) * 0.5
        val inPt = mid + w.inward * (28.0 / t.scale)
        val outPt = mid - w.inward * (30.0 / t.scale)
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
    walls(r).firstOrNull()?.let { drawCircle(SEL, 6f, t.s(it.a)) }
}
