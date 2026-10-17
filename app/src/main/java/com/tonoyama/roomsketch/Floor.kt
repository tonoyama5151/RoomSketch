package com.tonoyama.roomsketch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// ───────── 間取りタブ：部屋の一覧と、住戸全体での並べ方 ─────────

@Composable
fun FloorTab(vm: MainViewModel, project: Project, view: ViewT?, setView: (ViewT?) -> Unit, onEdit: (Room) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf(Pt(0.0, 0.0)) }
    var snapshotTaken by remember { mutableStateOf(false) }
    var carried by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<Room?>(null) }
    val sel = project.rooms.firstOrNull { it.id == selected }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlanCanvas(
                project = project,
                style = PlanStyle(selectedRoom = selected, dimFurniture = false),
                view = view,
                onView = { setView(it) },
                modifier = Modifier.fillMaxSize(),
                hitTest = { w -> project.rooms.lastOrNull { pointInPolygon(w, it.worldVertices()) }?.id },
                onDragStart = { id ->
                    selected = id
                    snapshotTaken = false
                    val r = project.rooms.first { it.id == id }
                    raw = r.origin
                    // 部屋の中に置いた家具は部屋と一緒に動かす
                    val poly = r.worldVertices()
                    carried = project.layouts.flatMap { it.placements }.filter { pointInPolygon(Pt(it.x, it.y), poly) }.map { it.id }.toSet()
                },
                onDrag = { d ->
                    val id = selected ?: return@PlanCanvas
                    if (!snapshotTaken) { vm.snapshot(); snapshotTaken = true }
                    raw = raw + d
                    vm.edit(push = false) { p ->
                        val r = p.rooms.firstOrNull { it.id == id } ?: return@edit p
                        val moved = r.copy(origin = raw)
                        val snap = snapRoomDelta(moved, p.rooms.filter { it.id != id }, 150.0)
                        val newOrigin = raw + snap
                        val delta = newOrigin - r.origin
                        p.copy(
                            rooms = p.rooms.map { if (it.id == id) it.copy(origin = newOrigin) else it },
                            layouts = p.layouts.map { l ->
                                l.copy(placements = l.placements.map { pl -> if (pl.id in carried) pl.copy(x = pl.x + delta.x, y = pl.y + delta.y) else pl })
                            },
                        )
                    }
                },
                onTap = { _, id -> selected = id },
            )
            TextButton(onClick = { setView(null) }, modifier = Modifier.align(Alignment.TopEnd)) { Text("全体表示") }
        }
        HorizontalDivider()
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (sel != null) {
                    Text(sel.name, style = MaterialTheme.typography.titleMedium)
                    Text("${areaText(sel.worldVertices())}・天井高 ${fmtCm(sel.ceiling)}・ドア/窓等 ${sel.openings.size}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onEdit(sel) }) { Text("形・ドア・窓を編集") }
                        OutlinedButton(onClick = {
                            vm.edit { p -> p.copy(rooms = p.rooms + sel.copy(id = newId(), name = sel.name + "のコピー", origin = nextRoomOrigin(p), openings = sel.openings.map { it.copy(id = newId()) })) }
                            setView(null)
                        }) { Text("複製") }
                        IconButton(onClick = { confirmDelete = sel }) { Icon(Icons.Default.Delete, "削除") }
                    }
                } else {
                    Text(
                        if (project.rooms.isEmpty()) "まず部屋を追加してください。部屋ごとに形と寸法を入力し、ここで並べて住戸全体にします。"
                        else "部屋をタップで選択、ドラッグで移動（隣の部屋の壁に吸着します）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { onEdit(Room(name = "部屋${project.rooms.size + 1}", origin = nextRoomOrigin(project), vertices = rectRoom(3600.0, 2700.0))) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("部屋を追加")
                    }
                }
            }
        }
    }

    confirmDelete?.let { r ->
        ConfirmDialog("「${r.name}」を削除しますか？", "配置した家具は残ります。", { confirmDelete = null }) {
            vm.edit { p -> p.copy(rooms = p.rooms.filter { it.id != r.id }) }
            confirmDelete = null; selected = null
        }
    }
}

/** 新しい部屋は既存の部屋の右隣に置く。 */
fun nextRoomOrigin(p: Project): Pt {
    val b = projectBounds(p) ?: return Pt(0.0, 0.0)
    return Pt(b.maxX + 300.0, b.minY)
}

// ───────── 部屋エディタ ─────────

private enum class ShapeMode(val label: String) { RECT("矩形"), EDGES("辺を順に入力") }

private data class EdgeRow(val length: Double, val turn: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomEditor(initial: Room, onCancel: () -> Unit, onSave: (Room) -> Unit) {
    val isRect = initial.vertices.size == 4 && initial.vertices.let { v ->
        abs(v[0].y - v[1].y) < 1 && abs(v[1].x - v[2].x) < 1 && abs(v[2].y - v[3].y) < 1 && abs(v[3].x - v[0].x) < 1
    }
    var name by remember { mutableStateOf(initial.name) }
    var ceiling by remember { mutableStateOf(initial.ceiling) }
    var mode by remember { mutableStateOf(if (isRect || initial.vertices.size < 3) ShapeMode.RECT else ShapeMode.EDGES) }
    val b0 = bounds(initial.vertices)
    var rw by remember { mutableStateOf(b0?.w ?: 3600.0) }
    var rd by remember { mutableStateOf(b0?.h ?: 2700.0) }
    val init = edgesFromVertices(initial.vertices)
    val edges = remember { mutableStateListOf<EdgeRow>().apply { addAll(init.first.map { EdgeRow(it.length, it.turn) }) } }
    var heading by remember { mutableStateOf(init.second) }
    var correct by remember { mutableStateOf(true) }
    val openings = remember { mutableStateListOf<Opening>().apply { addAll(initial.openings) } }
    var editOpening by remember { mutableStateOf<Opening?>(null) }
    var lDialog by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<ViewT?>(null) }

    val build = if (mode == ShapeMode.EDGES) buildFromEdges(edges.map { EdgeSpec(it.length, it.turn) }, heading, correct) else null
    val vertices = if (mode == ShapeMode.RECT) rectRoom(rw, rd) else build?.vertices ?: emptyList()
    val draft = initial.copy(name = name, ceiling = ceiling, vertices = vertices, openings = openings.toList())
    val preview = Project(rooms = listOf(draft.copy(origin = Pt(0.0, 0.0))))
    val wallCount = vertices.size
    val valid = wallCount >= 3 && abs(signedArea(vertices)) > 1

    fun switchToEdges() {
        val (e, h) = edgesFromVertices(vertices)
        edges.clear(); edges.addAll(e.map { EdgeRow(it.length, it.turn) }); heading = h
        mode = ShapeMode.EDGES
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "閉じる") } },
                title = { Text("部屋の編集") },
                actions = {
                    TextButton(enabled = valid, onClick = {
                        // 壁が減ってはみ出した要素は外す
                        onSave(draft.copy(openings = openings.filter { it.wallIndex < wallCount }))
                    }) { Text("保存") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                PlanCanvas(preview, PlanStyle(showFurniture = false, wallLabels = true), previewView, { previewView = it }, Modifier.fillMaxSize())
                TextButton(onClick = { previewView = null }, modifier = Modifier.align(Alignment.TopEnd)) { Text("全体表示") }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("部屋の名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField("天井高", ceiling, { ceiling = it }, Modifier.fillMaxWidth())

                SectionTitle("形と寸法（床面で測った内のり寸法）")
                Choices(ShapeMode.entries, mode, { it.label }, { m -> if (m == ShapeMode.EDGES && mode == ShapeMode.RECT) { switchToEdges() } else { mode = m } })
                if (mode == ShapeMode.RECT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("横幅", rw, { rw = it; previewView = null }, Modifier.weight(1f))
                        NumField("奥行", rd, { rd = it; previewView = null }, Modifier.weight(1f))
                    }
                    TextButton(onClick = { lDialog = true }) { Text("L字の部屋にする") }
                } else {
                    Text("左上の角（●）から時計回りに、壁の長さと次に曲がる向きを入力します。", style = MaterialTheme.typography.bodySmall)
                    edges.forEachIndexed { i, e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("${i + 1}", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                            NumField("壁${i + 1}の長さ", e.length, { v -> edges[i] = e.copy(length = v) }, Modifier.weight(1.3f))
                            if (i < edges.size - 1) {
                                Gap(6)
                                NumField("曲がる角度", e.turn, { v -> edges[i] = e.copy(turn = v) }, Modifier.weight(1f), suffix = "°", allowNegative = true)
                            } else Gap(6)
                            IconButton(onClick = { if (edges.size > 3) edges.removeAt(i) }) { Icon(Icons.Default.Delete, "削除") }
                        }
                        if (i < edges.size - 1) Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { edges[i] = e.copy(turn = 90.0) }) { Text("右に90°") }
                            TextButton(onClick = { edges[i] = e.copy(turn = -90.0) }) { Text("左に90°") }
                        }
                    }
                    TextButton(onClick = { edges.add(EdgeRow(1000.0, 90.0)) }) { Text("壁を追加") }
                    build?.let { r ->
                        val bad = r.closureError > 10
                        Text(
                            "閉合誤差 ${fmtMm(r.closureError)}mm" + if (bad) "（測り直し推奨。補正ONで各壁に按分）" else "",
                            color = if (bad) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = correct, onCheckedChange = { correct = it })
                        Gap(); Text("誤差を按分して形を閉じる", style = MaterialTheme.typography.bodySmall)
                    }
                }

                SectionTitle("ドア・窓・設備")
                Text("位置は「壁の番号」と「壁の始点（矢印の根元側の角）からの距離」で指定します。", style = MaterialTheme.typography.bodySmall)
                openings.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${o.type.label}　壁${o.wallIndex + 1}", style = MaterialTheme.typography.bodyMedium,
                                color = if (o.wallIndex >= wallCount) Color(0xFFD32F2F) else Color.Unspecified)
                            Text("始点から${fmtCm(o.offset)}・幅${fmtCm(o.width)}" + openingExtra(o), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editOpening = o }) { Icon(Icons.Default.Edit, "編集") }
                        IconButton(onClick = { openings.remove(o) }) { Icon(Icons.Default.Delete, "削除") }
                    }
                }
                OutlinedButton(enabled = valid, onClick = { editOpening = Opening(wallIndex = 0) }) { Text("ドア・窓などを追加") }
                Gap(); Box(Modifier.height(48.dp))
            }
        }
    }

    editOpening?.let { o ->
        OpeningDialog(o, walls(draft.copy(origin = Pt(0.0, 0.0))).map { it.length }, onDismiss = { editOpening = null }) { done ->
            val idx = openings.indexOfFirst { it.id == done.id }
            if (idx >= 0) openings[idx] = done else openings.add(done)
            editOpening = null
        }
    }

    if (lDialog) LShapeDialog(rw, rd, { lDialog = false }) { verts ->
        val (e, h) = edgesFromVertices(verts)
        edges.clear(); edges.addAll(e.map { EdgeRow(it.length, it.turn) }); heading = h
        mode = ShapeMode.EDGES; lDialog = false; previewView = null
    }
}

private fun openingExtra(o: Opening): String = when (o.type) {
    OpeningType.DOOR_SWING -> "・吊元${if (o.hingeAtEnd) "終点側" else "始点側"}・${if (o.swingInside) "内開き" else "外開き"}"
    OpeningType.WINDOW -> "・床から${fmtCm(o.sill)}・高さ${fmtCm(o.height)}"
    OpeningType.PILLAR -> "・出${fmtCm(o.depth)}"
    OpeningType.CLOSET -> if (o.depth > 0) "・扉前の空き${fmtCm(o.depth)}" else ""
    OpeningType.AIRCON, OpeningType.OUTLET -> "・床から${fmtCm(o.sill)}"
    else -> ""
}

/** 種類を切り替えたときの初期値（現場で書き換える前提）。 */
private fun defaultsFor(t: OpeningType, base: Opening): Opening = when (t) {
    OpeningType.DOOR_SWING -> base.copy(type = t, width = 780.0, height = 2000.0, sill = 0.0, depth = 0.0)
    OpeningType.DOOR_SLIDING -> base.copy(type = t, width = 800.0, height = 2000.0, sill = 0.0, depth = 0.0)
    OpeningType.WINDOW -> base.copy(type = t, width = 1650.0, height = 1100.0, sill = 900.0, depth = 0.0)
    OpeningType.CLOSET -> base.copy(type = t, width = 1600.0, height = 2000.0, sill = 0.0, depth = 600.0)
    OpeningType.PILLAR -> base.copy(type = t, width = 400.0, depth = 300.0, sill = 0.0)
    OpeningType.OUTLET -> base.copy(type = t, width = 100.0, sill = 250.0, depth = 0.0)
    OpeningType.AIRCON -> base.copy(type = t, width = 800.0, height = 300.0, sill = 1950.0, depth = 0.0)
}

@Composable
fun OpeningDialog(initial: Opening, wallLengths: List<Double>, onDismiss: () -> Unit, onOk: (Opening) -> Unit) {
    var o by remember { mutableStateOf(initial) }
    val wl = wallLengths.getOrNull(o.wallIndex) ?: 0.0
    val over = o.offset + o.width > wl + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ドア・窓・設備") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("種類", style = MaterialTheme.typography.labelMedium)
                Choices(OpeningType.entries, o.type, { it.label }, { o = defaultsFor(it, o) })
                Text("壁の番号", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Choices(wallLengths.indices.toList(), o.wallIndex, { "${it + 1}（${fmtCm(wallLengths[it])}）" }, { o = o.copy(wallIndex = it) })
                NumField("始点から端まで", o.offset, { o = o.copy(offset = it) }, Modifier.fillMaxWidth())
                NumField("幅", o.width, { o = o.copy(width = it) }, Modifier.fillMaxWidth())
                if (over) Text("壁の長さ（${fmtCm(wl)}）を超えています", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                when (o.type) {
                    OpeningType.DOOR_SWING -> {
                        Text("吊元（蝶番）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        Choices(listOf(false, true), o.hingeAtEnd, { if (it) "終点側" else "始点側" }, { o = o.copy(hingeAtEnd = it) })
                        Text("開く向き", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        Choices(listOf(true, false), o.swingInside, { if (it) "この部屋側" else "反対側" }, { o = o.copy(swingInside = it) })
                    }
                    OpeningType.WINDOW -> {
                        NumField("床から窓の下端まで", o.sill, { o = o.copy(sill = it) }, Modifier.fillMaxWidth())
                        NumField("窓の高さ", o.height, { o = o.copy(height = it) }, Modifier.fillMaxWidth())
                    }
                    OpeningType.PILLAR -> NumField("壁からの出っ張り", o.depth, { o = o.copy(depth = it) }, Modifier.fillMaxWidth())
                    OpeningType.CLOSET -> NumField("扉の前に空けておく奥行（0で判定しない）", o.depth, { o = o.copy(depth = it) }, Modifier.fillMaxWidth())
                    OpeningType.AIRCON -> NumField("床から下端まで", o.sill, { o = o.copy(sill = it) }, Modifier.fillMaxWidth())
                    OpeningType.OUTLET -> NumField("床からの高さ", o.sill, { o = o.copy(sill = it) }, Modifier.fillMaxWidth())
                    else -> {}
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(o) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun LShapeDialog(w0: Double, d0: Double, onDismiss: () -> Unit, onOk: (List<Pt>) -> Unit) {
    var a by remember { mutableStateOf(w0) }
    var b by remember { mutableStateOf(d0) }
    var c by remember { mutableStateOf(w0 / 2) }
    var d by remember { mutableStateOf(d0 / 2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("L字の部屋") },
        text = {
            Column {
                Text("全体の横幅・奥行と、右下の欠けている部分の大きさを入力します。あとから辺ごとに直せます。", style = MaterialTheme.typography.bodySmall)
                NumField("全体の横幅", a, { a = it }, Modifier.fillMaxWidth())
                NumField("全体の奥行", b, { b = it }, Modifier.fillMaxWidth())
                NumField("欠けの横幅", c, { c = it }, Modifier.fillMaxWidth())
                NumField("欠けの奥行", d, { d = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = c < a && d < b, onClick = {
                onOk(listOf(Pt(0.0, 0.0), Pt(a, 0.0), Pt(a, b - d), Pt(a - c, b - d), Pt(a - c, b), Pt(0.0, b)))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
