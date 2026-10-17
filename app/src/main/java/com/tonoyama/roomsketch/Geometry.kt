package com.tonoyama.roomsketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun rad(deg: Double) = deg * PI / 180.0
private fun deg(rad: Double) = rad * 180.0 / PI

// ───────── 多角形の基本演算 ─────────

fun signedArea(poly: List<Pt>): Double {
    var s = 0.0
    for (i in poly.indices) s += poly[i].cross(poly[(i + 1) % poly.size])
    return s / 2.0
}

fun centroid(poly: List<Pt>): Pt = Pt(poly.sumOf { it.x } / poly.size, poly.sumOf { it.y } / poly.size)

fun pointInPolygon(p: Pt, poly: List<Pt>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y)) {
            val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (p.x < x) inside = !inside
        }
        j = i
    }
    return inside
}

/** 端点の接触は除く、真の交差のみ。 */
fun segmentsCross(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
    val d1 = (b - a).cross(c - a)
    val d2 = (b - a).cross(d - a)
    val d3 = (d - c).cross(a - c)
    val d4 = (d - c).cross(b - c)
    return ((d1 > 1e-6 && d2 < -1e-6) || (d1 < -1e-6 && d2 > 1e-6)) &&
        ((d3 > 1e-6 && d4 < -1e-6) || (d3 < -1e-6 && d4 > 1e-6))
}

/** 凸多角形同士の重なり（分離軸定理）。接している程度（tol mm 以下）は重なりとみなさない。 */
fun convexOverlap(p: List<Pt>, q: List<Pt>, tol: Double = 1.0): Boolean {
    for (poly in listOf(p, q)) {
        for (i in poly.indices) {
            val e = poly[(i + 1) % poly.size] - poly[i]
            val axis = Pt(-e.y, e.x).norm()
            if (axis.len() == 0.0) continue
            val (minP, maxP) = project(p, axis)
            val (minQ, maxQ) = project(q, axis)
            if (maxP - minQ <= tol || maxQ - minP <= tol) return false
        }
    }
    return true
}

private fun project(poly: List<Pt>, axis: Pt): Pair<Double, Double> {
    var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
    for (v in poly) { val t = v.dot(axis); if (t < mn) mn = t; if (t > mx) mx = t }
    return mn to mx
}

/** 多角形を重心に向かって少し縮める（境界上の判定ゆれを避けるため）。 */
fun shrink(poly: List<Pt>, mm: Double): List<Pt> {
    val c = centroid(poly)
    return poly.map { v -> val d = v - c; val l = d.len(); if (l <= mm) c else c + d * ((l - mm) / l) }
}

/** 凸多角形 poly が（凹でもよい）部屋の多角形 room の内側に収まるか。 */
fun insidePolygon(poly: List<Pt>, room: List<Pt>): Boolean {
    val s = shrink(poly, 2.0)
    if (!s.all { pointInPolygon(it, room) }) return false
    for (i in s.indices) for (j in room.indices) {
        if (segmentsCross(s[i], s[(i + 1) % s.size], room[j], room[(j + 1) % room.size])) return false
    }
    return true
}

// ───────── 家具の形 ─────────

data class Frame(val c: Pt, val ux: Pt, val uy: Pt)

fun frameOf(p: Placement): Frame {
    val t = rad(p.rot)
    return Frame(Pt(p.x, p.y), Pt(cos(t), sin(t)), Pt(-sin(t), cos(t)))
}

/** 家具の外形（凸多角形）。円形は16角形で近似。 */
fun furniturePoly(p: Placement, f: Furniture): List<Pt> {
    val fr = frameOf(p)
    val hw = f.w / 2; val hd = f.d / 2
    return if (f.shape == FurnitureShape.ROUND) {
        (0 until 16).map { k ->
            val a = 2 * PI * k / 16
            fr.c + fr.ux * (hw * cos(a)) + fr.uy * (hd * sin(a))
        }
    } else rectPoly(fr, 0.0, hw, hd)
}

/** フレームの中心から uy 方向に off ずらした位置の、半幅 hw・半奥行 hd の矩形。 */
private fun rectPoly(fr: Frame, off: Double, hw: Double, hd: Double): List<Pt> {
    val c = fr.c + fr.uy * off
    return listOf(
        c - fr.ux * hw - fr.uy * hd,
        c + fr.ux * hw - fr.uy * hd,
        c + fr.ux * hw + fr.uy * hd,
        c - fr.ux * hw + fr.uy * hd,
    )
}

/** 前面（+uy 側）・背面（−uy 側）に必要な空き。 */
fun clearanceZones(p: Placement, f: Furniture): List<List<Pt>> {
    val fr = frameOf(p)
    val out = mutableListOf<List<Pt>>()
    if (f.front > 0) out += rectPoly(fr, f.d / 2 + f.front / 2, f.w / 2, f.front / 2)
    if (f.back > 0) out += rectPoly(fr, -(f.d / 2 + f.back / 2), f.w / 2, f.back / 2)
    return out
}

// ───────── 壁と開口部 ─────────

data class Wall(val a: Pt, val b: Pt, val inward: Pt) {
    val dir: Pt get() = (b - a).norm()
    val length: Double get() = (b - a).len()
}

fun walls(room: Room): List<Wall> {
    val v = room.worldVertices()
    if (v.size < 3) return emptyList()
    val ccw = signedArea(v) > 0
    return v.indices.map { i ->
        val a = v[i]; val b = v[(i + 1) % v.size]
        val u = (b - a).norm()
        val left = Pt(-u.y, u.x)
        Wall(a, b, if (ccw) left else left * -1.0)
    }
}

/** 間仕切りを「壁1本」として扱う。inward は a→b の左側（画面上で a→b に向かって左）。 */
fun partitionWall(p: Partition): Wall {
    val u = (p.b - p.a).norm()
    return Wall(p.a, p.b, Pt(-u.y, u.x))
}

/** 開き戸の開閉範囲（扇形を凸多角形で近似）。 */
fun doorSwing(w: Wall, o: Opening): List<Pt>? {
    if (o.type != OpeningType.DOOR_SWING) return null
    val p0 = w.a + w.dir * o.offset
    val p1 = w.a + w.dir * (o.offset + o.width)
    val hinge = if (o.hingeAtEnd) p1 else p0
    val other = if (o.hingeAtEnd) p0 else p1
    val d0 = (other - hinge).norm()
    val n = if (o.swingInside) w.inward else w.inward * -1.0
    val pts = mutableListOf(hinge)
    for (k in 0..8) {
        val t = rad(90.0 * k / 8)
        pts += hinge + (d0 * cos(t) + n * sin(t)) * o.width
    }
    return pts
}

fun doorSwing(room: Room, o: Opening): List<Pt>? = walls(room).getOrNull(o.wallIndex)?.let { doorSwing(it, o) }

/** 壁から内側に張り出す矩形（柱、収納扉の前の空きなど）。 */
fun wallBox(w: Wall, o: Opening, depth: Double): List<Pt> {
    val p0 = w.a + w.dir * o.offset
    val p1 = w.a + w.dir * (o.offset + o.width)
    return listOf(p0, p1, p1 + w.inward * depth, p0 + w.inward * depth)
}

fun wallBox(room: Room, o: Opening, depth: Double): List<Pt>? = walls(room).getOrNull(o.wallIndex)?.let { wallBox(it, o, depth) }

fun openingSegment(w: Wall, o: Opening): Pair<Pt, Pt> = (w.a + w.dir * o.offset) to (w.a + w.dir * (o.offset + o.width))

fun openingSegment(room: Room, o: Opening): Pair<Pt, Pt>? = walls(room).getOrNull(o.wallIndex)?.let { openingSegment(it, o) }

private val PASSABLE = setOf(OpeningType.DOOR_SWING, OpeningType.DOOR_SLIDING, OpeningType.CLOSET)

/** 間仕切りのうち、ドア等の開口を除いた「実体のある部分」（厚み付きの矩形）。 */
fun partitionSolids(p: Partition): List<List<Pt>> {
    val w = partitionWall(p)
    val len = w.length
    if (len < 1) return emptyList()
    val cuts = p.openings.filter { it.type in PASSABLE }.map { it.offset.coerceIn(0.0, len) to (it.offset + it.width).coerceIn(0.0, len) }.sortedBy { it.first }
    val spans = mutableListOf<Pair<Double, Double>>()
    var cur = 0.0
    for ((s0, e0) in cuts) { if (s0 > cur + 1) spans += cur to s0; cur = maxOf(cur, e0) }
    if (cur < len - 1) spans += cur to len
    val half = w.inward * (p.thickness / 2)
    return spans.map { (s0, e0) ->
        val a = w.a + w.dir * s0; val b = w.a + w.dir * e0
        listOf(a - half, b - half, b + half, a + half)
    }
}

/** 間仕切りの開口の通り道を描くための区間。 */
fun partitionOpenings(p: Partition): List<Pair<Opening, Pair<Pt, Pt>>> {
    val w = partitionWall(p)
    return p.openings.map { it to openingSegment(w, it) }
}

// ───────── 部屋の形の作成 ─────────

fun rectRoom(w: Double, d: Double) = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, d), Pt(0.0, d))

/** 辺の入力。turn は「この辺を進んだ後に曲がる角度」。右（画面上で時計回り）が＋。 */
data class EdgeSpec(val length: Double, val turn: Double = 90.0)

data class BuildResult(val vertices: List<Pt>, val closureError: Double)

/**
 * 辺の列から多角形を作る。最後の辺の終点が始点に戻らない分（閉合誤差）は、
 * correct=true なら辺の累積長に比例して按分補正する。
 */
fun buildFromEdges(edges: List<EdgeSpec>, startHeading: Double = 0.0, correct: Boolean = true): BuildResult {
    if (edges.size < 3) return BuildResult(emptyList(), 0.0)
    var h = startHeading
    var p = Pt(0.0, 0.0)
    val pts = mutableListOf(p)
    val cum = mutableListOf(0.0)
    var total = 0.0
    for (e in edges) {
        p = p + Pt(cos(rad(h)), sin(rad(h))) * e.length
        total += e.length
        pts += p; cum += total
        h += e.turn
    }
    val err = pts.last() // 始点(0,0)からのずれ
    val errLen = err.len()
    val raw = pts.dropLast(1)
    val verts = if (correct && total > 0) raw.mapIndexed { i, v -> v - err * (cum[i] / total) } else raw
    return BuildResult(verts, errLen)
}

/** 頂点列から辺の入力を復元する（編集用）。 */
fun edgesFromVertices(v: List<Pt>): Pair<List<EdgeSpec>, Double> {
    if (v.size < 3) return emptyList<EdgeSpec>() to 0.0
    val heads = v.indices.map { i -> val d = v[(i + 1) % v.size] - v[i]; deg(atan2(d.y, d.x)) }
    val edges = v.indices.map { i ->
        var t = heads[(i + 1) % v.size] - heads[i]
        while (t > 180) t -= 360
        while (t <= -180) t += 360
        EdgeSpec((v[(i + 1) % v.size] - v[i]).len(), Math.round(t).toDouble()) // 角度の初期値は整数
    }
    return edges to heads[0]
}

/** 部屋の外接矩形（住戸全体の位置合わせ用）。 */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val w get() = maxX - minX
    val h get() = maxY - minY
}

fun bounds(points: List<Pt>): Bounds? =
    if (points.isEmpty()) null else Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

/**
 * 部屋をドラッグ中、他の部屋の壁に吸着させる移動量。
 * 向かい合う壁は gap（壁の厚さ）だけ離し、同じ向きの壁は面をそろえる。
 */
fun snapRoomDelta(moving: Room, others: List<Room>, tol: Double, gap: Double = 0.0): Pt {
    val mine = walls(moving)
    var bestX: Double? = null; var bestY: Double? = null
    for (o in others) for (w2 in walls(o)) for (w1 in mine) {
        val vert1 = abs(w1.a.x - w1.b.x) < 1; val vert2 = abs(w2.a.x - w2.b.x) < 1
        val hor1 = abs(w1.a.y - w1.b.y) < 1; val hor2 = abs(w2.a.y - w2.b.y) < 1
        val facing = w1.inward.dot(w2.inward) < 0
        val off = if (facing) w2.inward * -gap else Pt(0.0, 0.0)
        if (vert1 && vert2) {
            val dx = w2.a.x + off.x - w1.a.x
            if (abs(dx) < tol && (bestX == null || abs(dx) < abs(bestX))) bestX = dx
        }
        if (hor1 && hor2) {
            val dy = w2.a.y + off.y - w1.a.y
            if (abs(dy) < tol && (bestY == null || abs(dy) < abs(bestY))) bestY = dy
        }
    }
    return Pt(bestX ?: 0.0, bestY ?: 0.0)
}

/** 家具を壁に吸着させる（回転が90°の倍数のときのみ）。 */
fun snapFurnitureDelta(poly: List<Pt>, rooms: List<Room>, tol: Double): Pt {
    val b = bounds(poly) ?: return Pt(0.0, 0.0)
    var bestX: Double? = null; var bestY: Double? = null
    for (r in rooms) for (w in walls(r)) {
        if (abs(w.a.x - w.b.x) < 1) { // 縦の壁
            val lo = minOf(w.a.y, w.b.y); val hi = maxOf(w.a.y, w.b.y)
            if (b.maxY > lo && b.minY < hi) {
                for (dx in listOf(w.a.x - b.minX, w.a.x - b.maxX)) if (abs(dx) < tol && (bestX == null || abs(dx) < abs(bestX))) bestX = dx
            }
        } else if (abs(w.a.y - w.b.y) < 1) { // 横の壁
            val lo = minOf(w.a.x, w.b.x); val hi = maxOf(w.a.x, w.b.x)
            if (b.maxX > lo && b.minX < hi) {
                for (dy in listOf(w.a.y - b.minY, w.a.y - b.maxY)) if (abs(dy) < tol && (bestY == null || abs(dy) < abs(bestY))) bestY = dy
            }
        }
    }
    return Pt(bestX ?: 0.0, bestY ?: 0.0)
}

// ───────── 寸法線 ─────────

data class DimLine(val from: Pt, val to: Pt) { val length get() = (to - from).len() }

private fun raySegment(o: Pt, d: Pt, a: Pt, b: Pt): Double? {
    val e = b - a
    val den = d.cross(e)
    if (abs(den) < 1e-9) return null
    val t = (a - o).cross(e) / den
    val u = (a - o).cross(d) / den
    return if (t > 0.5 && u >= 0.0 && u <= 1.0) t else null
}

/** 選択した家具の四辺の中点から外向きに、最も近い壁・家具までの距離。 */
fun dimensionLines(p: Placement, f: Furniture, rooms: List<Room>, obstacles: List<List<Pt>>): List<DimLine> {
    val fr = frameOf(p)
    val hw = f.w / 2; val hd = f.d / 2
    val starts = listOf(
        fr.c + fr.ux * hw to fr.ux, fr.c - fr.ux * hw to fr.ux * -1.0,
        fr.c + fr.uy * hd to fr.uy, fr.c - fr.uy * hd to fr.uy * -1.0,
    )
    val segs = mutableListOf<Pair<Pt, Pt>>()
    rooms.forEach { r -> walls(r).forEach { segs += it.a to it.b } }
    obstacles.forEach { poly -> poly.indices.forEach { i -> segs += poly[i] to poly[(i + 1) % poly.size] } }
    return starts.mapNotNull { (o, d) ->
        val t = segs.mapNotNull { (a, b) -> raySegment(o, d, a, b) }.minOrNull() ?: return@mapNotNull null
        if (t > 20000) null else DimLine(o, o + d * t)
    }
}

// ───────── 自動判定 ─────────

enum class Level { ERROR, WARN }

data class Issue(val placementId: String, val level: Level, val message: String)

fun roomOf(pt: Pt, rooms: List<Room>): Room? = rooms.firstOrNull { pointInPolygon(pt, it.worldVertices()) }

fun checkLayout(project: Project): List<Issue> {
    val rooms = project.rooms
    val items = project.activeLayout().placements.mapNotNull { pl -> project.furnitureById(pl.furnitureId)?.let { pl to it } }
    val polys = items.associate { (pl, f) -> pl.id to furniturePoly(pl, f) }
    val issues = mutableListOf<Issue>()

    val pillars = mutableListOf<List<Pt>>()
    val doors = mutableListOf<Pair<String, List<Pt>>>()
    val closets = mutableListOf<Pair<String, List<Pt>>>()
    for (r in rooms) for (o in r.openings) when (o.type) {
        OpeningType.PILLAR -> wallBox(r, o, o.depth)?.let { pillars += it }
        OpeningType.DOOR_SWING -> doorSwing(r, o)?.let { doors += "${r.name}の開き戸" to it }
        OpeningType.CLOSET -> if (o.depth > 0) wallBox(r, o, o.depth)?.let { closets += "${r.name}の収納扉" to it }
        else -> {}
    }
    val blockers = mutableListOf<List<Pt>>() // ぶつかってはいけない間仕切り
    val sliders = mutableListOf<List<Pt>>() // 引き戸の間仕切り（開けて使うこともあるので警告）
    for (pt in project.partitions) {
        val w = partitionWall(pt)
        if (pt.kind == PartitionKind.SLIDING) sliders += partitionSolids(pt) else blockers += partitionSolids(pt)
        for (o in pt.openings) when (o.type) {
            OpeningType.DOOR_SWING -> doorSwing(w, o)?.let { doors += "間仕切りの開き戸" to it }
            OpeningType.CLOSET -> if (o.depth > 0) closets += "間仕切りの収納扉" to wallBox(w, o, o.depth)
            else -> {}
        }
    }

    for ((i, item) in items.withIndex()) {
        val (pl, f) = item
        val poly = polys.getValue(pl.id)
        // 部屋に収まっているか
        if (rooms.none { insidePolygon(poly, it.worldVertices()) }) {
            issues += Issue(pl.id, Level.ERROR, "${f.name}：部屋からはみ出しています")
        }
        // 家具同士の重なり
        for (j in items.indices) if (j > i) {
            val (pl2, f2) = items[j]
            if (convexOverlap(poly, polys.getValue(pl2.id))) {
                issues += Issue(pl.id, Level.ERROR, "${f.name}と${f2.name}が重なっています")
                issues += Issue(pl2.id, Level.ERROR, "${f2.name}と${f.name}が重なっています")
            }
        }
        if (pillars.any { convexOverlap(poly, it) }) issues += Issue(pl.id, Level.ERROR, "${f.name}：柱と重なっています")
        if (blockers.any { convexOverlap(poly, it) }) issues += Issue(pl.id, Level.ERROR, "${f.name}：間仕切り（壁）と重なっています")
        if (sliders.any { convexOverlap(poly, it) }) issues += Issue(pl.id, Level.WARN, "${f.name}：引き戸の間仕切りにかかっています")
        doors.filter { convexOverlap(poly, it.second) }.forEach { issues += Issue(pl.id, Level.WARN, "${f.name}：${it.first}の開閉範囲に入っています") }
        closets.filter { convexOverlap(poly, it.second) }.forEach { issues += Issue(pl.id, Level.WARN, "${f.name}：${it.first}の前をふさいでいます") }
        // 前面・背面の空き
        for (z in clearanceZones(pl, f)) {
            val blocked = items.any { (pl2, _) -> pl2.id != pl.id && convexOverlap(z, polys.getValue(pl2.id)) } ||
                pillars.any { convexOverlap(z, it) } || blockers.any { convexOverlap(z, it) } ||
                rooms.none { insidePolygon(z, it.worldVertices()) }
            if (blocked) { issues += Issue(pl.id, Level.WARN, "${f.name}：前後に必要な空きが足りません"); break }
        }
    }
    return issues
}

// ───────── 搬入判定 ─────────

data class RouteResult(val point: RoutePoint, val ok: Boolean)

fun carryDims(f: Furniture): List<Double> =
    if (f.partW > 0 && f.partD > 0 && f.partH > 0) listOf(f.partW, f.partD, f.partH).sorted() else listOf(f.w, f.d, f.h).sorted()

/** 家具の短い2辺が開口の幅・高さに収まるか（傾け・曲がり角は考慮しない目安）。 */
fun routeCheck(f: Furniture, route: List<RoutePoint>): List<RouteResult> {
    val (a, b) = carryDims(f)
    return route.map { r ->
        val p = minOf(r.width, r.height); val q = maxOf(r.width, r.height)
        RouteResult(r, a <= p && b <= q)
    }
}

/** 数値を小数 decimals 桁に丸めて表示（末尾の 0 は省略）。値そのものは丸めない。 */
fun fmtDec(v: Double, decimals: Int): String {
    val s = java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString()
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** mm 表記（0.1mm単位）。 */
fun fmtMm(v: Double): String = fmtDec(v, 1)

/** mm を cm 表記に（0.1mm＝0.01cm 単位、末尾の 0 は省略）。 */
fun fmtCm(v: Double): String = fmtDec(v / 10.0, 2) + "cm"

fun dist(a: Pt, b: Pt) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))


// ───────── 部屋の形の編集（図で編集） ─────────

/** 重複点と、一直線上に並んだ中間点を取り除く。 */
fun cleanupPolygon(v: List<Pt>, removeCollinear: Boolean = true): List<Pt> {
    val pts = v.toMutableList()
    var changed = true
    while (changed && pts.size > 3) {
        changed = false
        for (i in pts.indices) {
            val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
            val dup = (b - a).len() < 1.0
            val col = removeCollinear && abs((b - a).norm().cross((c - b).norm())) < 1e-4 && (b - a).dot(c - b) > 0
            if (dup || col) { pts.removeAt(i); changed = true; break }
        }
    }
    return pts
}

/** 頂点 i の角を、手前の壁に沿って a、次の壁に沿って b だけ四角く欠く（柱の出っ張りなど）。 */
fun cutCorner(v: List<Pt>, i: Int, a: Double, b: Double): List<Pt> {
    val n = v.size
    val p = v[i]
    val uPrev = (p - v[(i - 1 + n) % n]).norm()
    val uNext = (v[(i + 1) % n] - p).norm()
    val q1 = p - uPrev * a
    val q2 = p - uPrev * a + uNext * b
    val q3 = p + uNext * b
    val out = v.toMutableList()
    out.removeAt(i)
    out.addAll(i, listOf(q1, q2, q3))
    return cleanupPolygon(out)
}

/**
 * 壁 i（頂点 i → i+1）に凹み（depth>0：部屋が内側にへこむ）または出っ張り（depth<0）を作る。
 * offset は壁の始点から凹みの端まで。
 */
fun addNotch(v: List<Pt>, i: Int, offset: Double, width: Double, depth: Double): List<Pt> {
    val ws = walls(Room(vertices = v))
    val w = ws.getOrNull(i) ?: return v
    val p1 = w.a + w.dir * offset
    val p2 = p1 + w.inward * depth
    val p3 = p2 + w.dir * width
    val p4 = w.a + w.dir * (offset + width)
    val out = v.toMutableList()
    out.addAll(i + 1, listOf(p1, p2, p3, p4))
    return cleanupPolygon(out)
}

/** 壁 i の中点に角を足す。 */
fun splitWall(v: List<Pt>, i: Int): List<Pt> {
    val a = v[i]; val b = v[(i + 1) % v.size]
    val out = v.toMutableList()
    out.add(i + 1, (a + b) * 0.5)
    return out
}

fun deleteVertex(v: List<Pt>, i: Int): List<Pt> = if (v.size <= 3) v else v.toMutableList().apply { removeAt(i) }

/**
 * 壁 i の長さを len にする。終点を壁の向きに動かし、次の壁が直角なら次の壁ごと平行移動する
 * （直角の間取りを崩さずに寸法だけ直すため）。
 */
fun setWallLength(v: List<Pt>, i: Int, len: Double): List<Pt> {
    val n = v.size
    val a = v[i]; val bi = (i + 1) % n; val b = v[bi]
    val u = (b - a).norm()
    val shift = u * (len - (b - a).len())
    val ci = (bi + 1) % n
    val nextDir = (v[ci] - b).norm()
    val out = v.toMutableList()
    out[bi] = b + shift
    if (abs(u.dot(nextDir)) < 0.01 && ci != i) out[ci] = v[ci] + shift
    return out
}

/** 壁 i を法線方向に平行移動する（両端の頂点を一緒に動かす）。 */
fun moveWall(v: List<Pt>, i: Int, delta: Pt): List<Pt> {
    val n = v.size
    val a = v[i]; val b = v[(i + 1) % n]
    val u = (b - a).norm()
    val normal = Pt(-u.y, u.x)
    val d = normal * delta.dot(normal)
    return v.toMutableList().apply { this[i] = a + d; this[(i + 1) % n] = b + d }
}

/**
 * 頂点の数が変わったとき、ドア・窓などを元と同じ位置の壁に付け替える。
 * 付け替え先が見つからない要素は元の番号のまま残す（画面で赤く表示される）。
 */
fun remapOpenings(old: List<Pt>, new: List<Pt>, openings: List<Opening>): List<Opening> {
    if (old.size == new.size) return openings
    val ow = walls(Room(vertices = old)); val nw = walls(Room(vertices = new))
    return openings.map { o ->
        val w = ow.getOrNull(o.wallIndex) ?: return@map o
        val p0 = w.a + w.dir * (o.offset + o.width / 2)
        val j = nw.indexOfFirst { x -> distToSeg(p0, x.a, x.b) < 2.0 && x.dir.dot(w.dir) > 0.999 }
        if (j < 0) o else o.copy(wallIndex = j, offset = (p0 - nw[j].a).dot(nw[j].dir) - o.width / 2)
    }
}

/**
 * なぞり・ドラッグ時の吸着。
 * 1) 近くの既存の点（部屋の角・間仕切りの端）に吸着
 * 2) 基準点 ref から見て水平・垂直に近ければそろえる
 * 3) 10mm 単位に丸める
 */
fun snapPoint(p: Pt, ref: Pt?, anchors: List<Pt>, tolMm: Double): Pt {
    anchors.minByOrNull { (it - p).len() }?.let { if ((it - p).len() < tolMm) return it }
    var x = Math.round(p.x / 10.0) * 10.0
    var y = Math.round(p.y / 10.0) * 10.0
    if (ref != null) {
        if (abs(p.x - ref.x) < tolMm) x = ref.x
        if (abs(p.y - ref.y) < tolMm) y = ref.y
    }
    // 既存の点とも縦横をそろえる
    anchors.forEach { a -> if (abs(p.x - a.x) < tolMm / 2) x = a.x; if (abs(p.y - a.y) < tolMm / 2) y = a.y }
    return Pt(x, y)
}

fun anchorPoints(p: Project): List<Pt> = p.rooms.flatMap { it.worldVertices() } + p.partitions.flatMap { listOf(it.a, it.b) }


// ───────── なぞった形の直角補正 ─────────

/** snappedCorners：直角にそろえた角の数。-1 は補正量が大きすぎて見送ったことを表す。 */
data class Regularized(val vertices: List<Pt>, val snappedCorners: Int)

private fun normTurn(t: Double): Double { var r = t; while (r > 180) r -= 360; while (r <= -180) r += 360; return r }

/**
 * なぞった多角形を整える。
 * - 90°±tol の角は 90° に、最初の壁の向きは水平・垂直±tol なら軸にそろえる
 * - 向きを固定したまま、壁の長さだけを最小限（長い壁ほど多く）直して形を閉じる
 *   （重み 1/L の最小二乗：δi = −Li·(di·λ)、(Σ Li di diᵀ) λ = 閉合誤差）
 * 補正が破綻する（壁が潰れる等）ときは、元の形のまま返す。
 */
fun regularizeTrace(v: List<Pt>, tolDeg: Double = 10.0): Regularized {
    val n = v.size
    if (n < 3) return Regularized(v, 0)
    val len = (0 until n).map { (v[(it + 1) % n] - v[it]).len() }
    val head = (0 until n).map { val d = v[(it + 1) % n] - v[it]; deg(atan2(d.y, d.x)) }
    var snapped = 0
    // 最初の壁の向き
    var h0 = head[0]
    val axis = Math.round(h0 / 90.0) * 90.0
    if (abs(h0 - axis) <= tolDeg) h0 = axis
    val newHead = MutableList(n) { 0.0 }
    newHead[0] = h0
    for (i in 1 until n) {
        var t = normTurn(head[i] - head[i - 1])
        if (abs(abs(t) - 90.0) <= tolDeg) { if (abs(t) != 90.0) snapped++; t = if (t > 0) 90.0 else -90.0 }
        newHead[i] = newHead[i - 1] + t
    }
    // 最後の角（壁 n-1 → 壁 0）は、閉じた結果として決まる

    val dirs = newHead.map { Pt(cos(rad(it)), sin(rad(it))) }
    var ex = 0.0; var ey = 0.0
    var m11 = 0.0; var m12 = 0.0; var m22 = 0.0
    for (i in 0 until n) {
        val d = dirs[i]; val l = len[i]
        ex += l * d.x; ey += l * d.y
        m11 += l * d.x * d.x; m12 += l * d.x * d.y; m22 += l * d.y * d.y
    }
    val det = m11 * m22 - m12 * m12
    if (abs(det) < 1e-6) return Regularized(v, 0)
    val lx = (ex * m22 - ey * m12) / det
    val ly = (m11 * ey - m12 * ex) / det
    val newLen = (0 until n).map { len[it] - len[it] * (dirs[it].x * lx + dirs[it].y * ly) }
    // 15%（短い壁は100mm）を超えて長さが変わる壁があれば、なぞりのずれが大きいので補正を見送る
    if ((0 until n).any { newLen[it] < 10.0 || abs(newLen[it] - len[it]) > maxOf(0.15 * len[it], 100.0) }) return Regularized(v, if (snapped > 0) -1 else 0)
    val out = mutableListOf(v[0])
    for (i in 0 until n - 1) out += out.last() + dirs[i] * newLen[i]
    return Regularized(cleanupPolygon(out), snapped)
}
