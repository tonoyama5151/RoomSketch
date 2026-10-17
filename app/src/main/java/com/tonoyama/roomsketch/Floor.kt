package com.tonoyama.roomsketch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
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
import kotlin.math.atan2
import kotlin.math.tan

private enum class FloorTool(val label: String) {
    ROOM("部屋を並べる"),
    TRACE("なぞって部屋を作る"),
    PARTITION("間仕切り"),
    BACKGROUND("下絵（間取り図）"),
}

/** a から見て水平・垂直に近ければ（8°以内）そろえる。 */
fun orthoSnap(a: Pt, p: Pt): Pt {
    val d = p - a
    val t8 = tan(Math.toRadians(8.0))
    return when {
        abs(d.y) < abs(d.x) * t8 -> Pt(p.x, a.y)
        abs(d.x) < abs(d.y) * t8 -> Pt(a.x, p.y)
        else -> p
    }
}

private fun snapFrom(a: Pt?, raw: Pt, anchors: List<Pt>, tol: Double): Pt {
    anchors.minByOrNull { (it - raw).len() }?.let { if ((it - raw).len() < tol) return it }
    val o = if (a != null) orthoSnap(a, raw) else raw
    val s = snapPoint(o, a, emptyList(), tol / 2)
    return if (a != null) orthoSnap(a, s).let { if (abs(it.x - a.x) < 0.5 || abs(it.y - a.y) < 0.5) it else s } else s
}

// ───────── 間取りタブ ─────────

@Composable
fun FloorTab(vm: MainViewModel, project: Project, view: ViewT?, setView: (ViewT?) -> Unit, onEdit: (Room) -> Unit) {
    var tool by remember { mutableStateOf(FloorTool.ROOM) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selPart by remember { mutableStateOf<String?>(null) }
    var dragKind by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf(Pt(0.0, 0.0)) }
    var raw2 by remember { mutableStateOf(Pt(0.0, 0.0)) }
    var snapshotTaken by remember { mutableStateOf(false) }
    var carried by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<Room?>(null) }
    var wallDialog by remember { mutableStateOf(false) }
    var newPart by remember { mutableStateOf<Pair<Pt, Pt>?>(null) }
    val trace = remember { mutableStateListOf<Pt>() }
    val marks = remember { mutableStateListOf<Pt>() }
    var calibrating by remember { mutableStateOf(false) }
    var calibDialog by remember { mutableStateOf(false) }
    var partOpening by remember { mutableStateOf<Opening?>(null) }
    val bgImage = remember(project.background?.data) { decodeBackground(project.background) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importBackground) }

    val sel = project.rooms.firstOrNull { it.id == selected }
    val part = project.partitions.firstOrNull { it.id == selPart }
    val anchors = remember(project.rooms, project.partitions) { anchorPoints(project) }

    fun takeSnapshot() { if (!snapshotTaken) { vm.snapshot(); snapshotTaken = true } }
    fun editPart(id: String, push: Boolean = true, f: (Partition) -> Partition) = vm.edit(push) { p -> p.copy(partitions = p.partitions.map { if (it.id == id) f(it) else it }) }

    val createStart: ((Pt) -> Unit)? = if (tool == FloorTool.PARTITION) {
        { p: Pt ->
            val sc = view?.scale ?: 0.1f
            val a = snapFrom(null, p, anchors, 20.0 / sc)
            newPart = a to a
        }
    } else null

    val style = PlanStyle(
        selectedRoom = if (tool == FloorTool.ROOM) selected else null,
        selectedPartition = selPart,
        dimFurniture = false,
        bg = bgImage,
        draft = when {
            tool == FloorTool.TRACE -> trace.toList()
            newPart != null -> listOf(newPart!!.first, newPart!!.second)
            else -> emptyList()
        },
        handles = if (tool == FloorTool.PARTITION && part != null) listOf(part.a, part.b) else emptyList(),
        marks = if (tool == FloorTool.BACKGROUND) marks.toList() else emptyList(),
        partitionLabels = tool == FloorTool.PARTITION,
        showFurniture = tool == FloorTool.ROOM,
    )

    Column(Modifier.fillMaxSize()) {
        Choices(FloorTool.entries, tool, { it.label }, { tool = it; trace.clear(); marks.clear(); calibrating = false; selPart = null },
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlanCanvas(
                project = project,
                style = style,
                view = view,
                onView = { setView(it) },
                modifier = Modifier.fillMaxSize(),
                hitTest = { w, sc ->
                    val tol = 26.0 / sc
                    when (tool) {
                        FloorTool.ROOM -> project.rooms.lastOrNull { pointInPolygon(w, it.worldVertices()) }?.id
                        FloorTool.TRACE -> if (trace.size >= 3 && (trace.first() - w).len() < tol) "first" else null
                        FloorTool.PARTITION -> {
                            val pp = part
                            when {
                                pp != null && (pp.a - w).len() < tol -> "pa"
                                pp != null && (pp.b - w).len() < tol -> "pb"
                                else -> project.partitions.lastOrNull { distToSeg(w, it.a, it.b) < maxOf(tol * 0.7, it.thickness) }?.let { "p:" + it.id }
                            }
                        }
                        FloorTool.BACKGROUND -> project.background?.takeIf { !calibrating && it.visible }?.let { b ->
                            if (w.x >= b.x && w.y >= b.y && w.x <= b.x + b.wPx * b.mmPerPx && w.y <= b.y + b.hPx * b.mmPerPx) "bg" else null
                        }
                    }
                },
                onDragStart = { id ->
                    snapshotTaken = false
                    dragKind = id
                    when {
                        tool == FloorTool.ROOM -> {
                            selected = id
                            val r = project.rooms.first { it.id == id }
                            raw = r.origin
                            val poly = r.worldVertices()
                            carried = project.layouts.flatMap { it.placements }.filter { pointInPolygon(Pt(it.x, it.y), poly) }.map { it.id }.toSet()
                        }
                        id == "pa" -> part?.let { raw = it.a }
                        id == "pb" -> part?.let { raw = it.b }
                        id.startsWith("p:") -> {
                            selPart = id.removePrefix("p:")
                            project.partitions.firstOrNull { it.id == selPart }?.let { raw = it.a; raw2 = it.b }
                        }
                        id == "bg" -> project.background?.let { raw = Pt(it.x, it.y) }
                    }
                    Unit
                },
                onDrag = { d ->
                    val sc = view?.scale ?: 0.1f
                    val tol = 20.0 / sc
                    when {
                        tool == FloorTool.ROOM -> {
                            val id = selected ?: return@PlanCanvas
                            takeSnapshot()
                            raw = raw + d
                            vm.edit(push = false) { p ->
                                val r = p.rooms.firstOrNull { it.id == id } ?: return@edit p
                                val snap = snapRoomDelta(r.copy(origin = raw), p.rooms.filter { it.id != id }, 150.0, p.wallThickness)
                                val newOrigin = raw + snap
                                val delta = newOrigin - r.origin
                                p.copy(
                                    rooms = p.rooms.map { if (it.id == id) it.copy(origin = newOrigin) else it },
                                    layouts = p.layouts.map { l ->
                                        l.copy(placements = l.placements.map { pl -> if (pl.id in carried) pl.copy(x = pl.x + delta.x, y = pl.y + delta.y) else pl })
                                    },
                                )
                            }
                        }
                        dragKind == "pa" || dragKind == "pb" -> {
                            val pp = part ?: return@PlanCanvas
                            takeSnapshot()
                            raw = raw + d
                            val other = if (dragKind == "pa") pp.b else pp.a
                            val np = snapFrom(other, raw, anchors.filter { (it - other).len() > 1 && (it - pp.a).len() > 1 && (it - pp.b).len() > 1 }, tol)
                            editPart(pp.id, push = false) { if (dragKind == "pa") it.copy(a = np) else it.copy(b = np) }
                        }
                        dragKind.startsWith("p:") -> {
                            val id = selPart ?: return@PlanCanvas
                            takeSnapshot()
                            raw = raw + d; raw2 = raw2 + d
                            val ra = Pt(Math.round(raw.x / 10.0) * 10.0, Math.round(raw.y / 10.0) * 10.0)
                            val off = ra - raw
                            editPart(id, push = false) { it.copy(a = ra, b = raw2 + off) }
                        }
                        dragKind == "bg" -> {
                            takeSnapshot()
                            raw = raw + d
                            vm.edit(push = false) { p -> p.copy(background = p.background?.copy(x = raw.x, y = raw.y)) }
                        }
                    }
                    Unit
                },
                onTap = { w, id ->
                    val sc = view?.scale ?: 0.1f
                    when (tool) {
                        FloorTool.ROOM -> selected = id
                        FloorTool.PARTITION -> selPart = id?.takeIf { it.startsWith("p:") }?.removePrefix("p:") ?: if (id == "pa" || id == "pb") selPart else null
                        FloorTool.TRACE -> {
                            if (id == "first") {
                                finishTrace(trace.toList(), project, vm)?.let { onEdit(it) }
                                trace.clear()
                            } else trace.add(snapFrom(trace.lastOrNull(), w, anchors, 20.0 / sc))
                        }
                        FloorTool.BACKGROUND -> if (calibrating) {
                            if (marks.size >= 2) marks.clear()
                            marks.add(w)
                            if (marks.size == 2) calibDialog = true
                        }
                    }
                    Unit
                },
                onCreateStart = createStart,
                onCreateMove = { p ->
                    val sc = view?.scale ?: 0.1f
                    newPart?.let { (a, _) -> newPart = a to snapFrom(a, p, anchors.filter { (it - a).len() > 1 }, 20.0 / sc) }
                },
                onCreateEnd = {
                    newPart?.let { (a, b) ->
                        if ((b - a).len() > 100) {
                            val np = Partition(a = a, b = b, thickness = project.wallThickness)
                            vm.edit { it.copy(partitions = it.partitions + np) }
                            selPart = np.id
                        }
                    }
                    newPart = null
                },
            )
            TextButton(onClick = { setView(null) }, modifier = Modifier.align(Alignment.TopEnd)) { Text("全体表示") }
        }
        HorizontalDivider()
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (tool) {
                    FloorTool.ROOM -> RoomPanel(vm, project, sel, onEdit, onDelete = { confirmDelete = it }, onWall = { wallDialog = true }, onAdd = {
                        onEdit(Room(name = "部屋${project.rooms.size + 1}", origin = nextRoomOrigin(project), vertices = rectRoom(3600.0, 2700.0)))
                    }, onDuplicated = { setView(null) })
                    FloorTool.TRACE -> {
                        Text(
                            if (trace.isEmpty()) "部屋の角を順にタップします。縦横に近い線は自動でそろい、既存の角や間仕切りの端に吸着します。下絵があればその上でなぞれます。"
                            else "角 ${trace.size}点。最初の点をタップするか「完成」で閉じます。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Button(enabled = trace.size >= 3, onClick = { finishTrace(trace.toList(), project, vm)?.let { onEdit(it) }; trace.clear() }) { Text("完成") }
                            OutlinedButton(enabled = trace.isNotEmpty(), onClick = { trace.removeAt(trace.size - 1) }) { Text("1つ戻す") }
                            TextButton(enabled = trace.isNotEmpty(), onClick = { trace.clear() }) { Text("やめる") }
                        }
                    }
                    FloorTool.PARTITION -> {
                        if (part == null) {
                            Text("何もない所を1本指でなぞると間仕切りを引けます（2本指で移動・拡大）。部屋の角・壁の端に吸着します。タップで選択。",
                                style = MaterialTheme.typography.bodySmall)
                        } else PartitionPanel(part, onChange = { np -> editPart(part.id) { np } }, onDelete = {
                            vm.edit { p -> p.copy(partitions = p.partitions.filter { it.id != part.id }) }; selPart = null
                        }, onAddOpening = { partOpening = Opening(wallIndex = 0, offset = 0.0) }, onEditOpening = { partOpening = it })
                    }
                    FloorTool.BACKGROUND -> BackgroundPanel(project.background, calibrating,
                        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCalibrate = { calibrating = !calibrating; marks.clear() },
                        onChange = { nb -> vm.edit(push = false) { it.copy(background = nb) } },
                        onDelete = { vm.edit { it.copy(background = null) } })
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
    if (wallDialog) {
        var v by remember { mutableStateOf(project.wallThickness) }
        AlertDialog(
            onDismissRequest = { wallDialog = false },
            title = { Text("部屋と部屋の間の壁の厚さ") },
            text = {
                Column {
                    Text("部屋は内のり寸法で入力するため、隣の部屋とはこの厚さだけ離して吸着します。間仕切りの初期値にも使います。", style = MaterialTheme.typography.bodySmall)
                    NumField("壁の厚さ", v, { v = it }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.edit { it.copy(wallThickness = v.coerceIn(0.0, 500.0)) }; wallDialog = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { wallDialog = false }) { Text("キャンセル") } },
        )
    }
    if (calibDialog && marks.size == 2) {
        val bg = project.background
        var mm by remember { mutableStateOf(0.0) }
        AlertDialog(
            onDismissRequest = { calibDialog = false; marks.clear() },
            title = { Text("2点間の実際の長さ") },
            text = {
                Column {
                    Text("今の縮尺では ${fmtCm((marks[1] - marks[0]).len())} です。実測した長さ（または図面の寸法）を入力してください。", style = MaterialTheme.typography.bodySmall)
                    NumField("実際の長さ", mm, { mm = it }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = mm > 0 && bg != null, onClick = {
                    val b = bg!!
                    val cur = (marks[1] - marks[0]).len()
                    if (cur > 0) {
                        val k = mm / cur
                        val p1 = marks[0]
                        // 1点目を固定したまま拡大縮小
                        val nb = b.copy(mmPerPx = b.mmPerPx * k, x = p1.x - (p1.x - b.x) * k, y = p1.y - (p1.y - b.y) * k)
                        vm.edit { it.copy(background = nb) }
                        setView(null)
                    }
                    calibDialog = false; calibrating = false; marks.clear()
                }) { Text("合わせる") }
            },
            dismissButton = { TextButton(onClick = { calibDialog = false; marks.clear() }) { Text("キャンセル") } },
        )
    }
    val po = partOpening
    if (po != null && part != null) {
        OpeningDialog(po, listOf((part.b - part.a).len()), onDismiss = { partOpening = null }, sideLabels = sideLabels(partitionWall(part))) { done ->
            editPart(part.id) { p ->
                val list = if (p.openings.any { it.id == done.id }) p.openings.map { if (it.id == done.id) done else it } else p.openings + done
                p.copy(openings = list)
            }
            partOpening = null
        }
    }
}

/** なぞった点から部屋を作る（原点は最初の点）。90°±10°の角は直角にそろえ、壁の長さで閉じる。 */
private fun finishTrace(pts: List<Pt>, project: Project, vm: MainViewModel): Room? {
    val v0 = cleanupPolygon(pts, removeCollinear = true)
    if (v0.size < 3 || abs(signedArea(v0)) < 10_000) return null
    val reg = regularizeTrace(v0)
    val v = reg.vertices
    when {
        reg.snappedCorners > 0 -> vm.toast("${reg.snappedCorners}か所の角を90°にそろえ、壁の長さを補正しました")
        reg.snappedCorners < 0 -> vm.toast("なぞりのずれが大きいため、直角補正をせずに作成しました（図で編集で直せます）")
    }
    val o = v.first()
    return Room(name = "部屋${project.rooms.size + 1}", origin = o, vertices = v.map { it - o })
}

/** 壁の法線の向きを「上側/下側」「左側/右側」で言い表す（開き戸の開く向きの選択用）。 */
fun sideLabels(w: Wall): Pair<String, String> {
    val n = w.inward
    return if (abs(n.y) >= abs(n.x)) (if (n.y < 0) "上側" else "下側") to (if (n.y < 0) "下側" else "上側")
    else (if (n.x < 0) "左側" else "右側") to (if (n.x < 0) "右側" else "左側")
}

@Composable
private fun RoomPanel(vm: MainViewModel, project: Project, sel: Room?, onEdit: (Room) -> Unit, onDelete: (Room) -> Unit, onWall: () -> Unit, onAdd: () -> Unit, onDuplicated: () -> Unit) {
    if (sel != null) {
        Text("${sel.name}（${sel.kind.label}）", style = MaterialTheme.typography.titleMedium)
        Text("${areaText(sel.worldVertices())}・角 ${sel.vertices.size}・ドア/窓等 ${sel.openings.size}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEdit(sel) }) { Text("形・ドア・窓を編集") }
            OutlinedButton(onClick = {
                vm.edit { p -> p.copy(rooms = p.rooms + sel.copy(id = newId(), name = sel.name + "のコピー", origin = nextRoomOrigin(p), openings = sel.openings.map { it.copy(id = newId()) })) }
                onDuplicated()
            }) { Text("複製") }
            IconButton(onClick = { onDelete(sel) }) { Icon(Icons.Default.Delete, "削除") }
        }
    } else {
        Text(
            if (project.rooms.isEmpty()) "部屋（廊下・水回り・収納も）を1つずつ作り、ここで並べて住戸全体にします。下絵をなぞって作ることもできます。"
            else "部屋をタップで選択、ドラッグで移動（隣の部屋とは壁の厚さだけ離して吸着）。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAdd) { Text("部屋を追加") }
            TextButton(onClick = onWall) { Text("壁の厚さ ${fmtCm(project.wallThickness)}") }
        }
    }
}

@Composable
private fun PartitionPanel(p: Partition, onChange: (Partition) -> Unit, onDelete: () -> Unit, onAddOpening: () -> Unit, onEditOpening: (Opening) -> Unit) {
    val len = (p.b - p.a).len()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Choices(PartitionKind.entries, p.kind, { it.label }, { onChange(p.copy(kind = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            NumField("長さ", len, { l -> if (l > 0 && len > 0) onChange(p.copy(b = p.a + (p.b - p.a).norm() * l)) }, Modifier.weight(1f))
            NumField("厚さ", p.thickness, { onChange(p.copy(thickness = it)) }, Modifier.weight(1f))
            if (p.kind == PartitionKind.LOW_WALL) NumField("高さ", p.height, { onChange(p.copy(height = it)) }, Modifier.weight(1f))
        }
        p.openings.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${o.type.label}　端から${fmtCm(o.offset)}・幅${fmtCm(o.width)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onEditOpening(o) }) { Icon(Icons.Default.Edit, "編集") }
                IconButton(onClick = { onChange(p.copy(openings = p.openings.filter { it.id != o.id })) }) { Icon(Icons.Default.Delete, "削除") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddOpening) { Text("ドアを付ける") }
            TextButton(onClick = onDelete) { Text("間仕切りを削除", color = MaterialTheme.colorScheme.error) }
        }
        Text("端の●をドラッグで伸縮、線をドラッグで移動。長さは●が付いた始点から測ります。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BackgroundPanel(bg: Background?, calibrating: Boolean, onPick: () -> Unit, onCalibrate: () -> Unit, onChange: (Background) -> Unit, onDelete: () -> Unit) {
    if (bg == null) {
        Text("販売図面やスクリーンショットを下絵に置き、縮尺を合わせてから「なぞって部屋を作る」で角をタップすると、図面どおりの形を作れます。",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = onPick, modifier = Modifier.padding(top = 6.dp)) { Text("画像を選ぶ") }
        return
    }
    Text(
        if (calibrating) "長さが分かる2点（例：実測した部屋の幅の両端）をタップしてください。"
        else "画像をドラッグで位置合わせ。縮尺 1px＝${"%.1f".format(bg.mmPerPx)}mm",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onCalibrate) { Text(if (calibrating) "縮尺合わせをやめる" else "縮尺を合わせる") }
        OutlinedButton(onClick = onPick) { Text("画像を変更") }
        Switch(checked = bg.visible, onCheckedChange = { onChange(bg.copy(visible = it)) })
        Text("表示", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.error) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("濃さ", style = MaterialTheme.typography.bodySmall)
        Slider(value = bg.opacity, onValueChange = { onChange(bg.copy(opacity = it)) }, valueRange = 0.1f..1f, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** 新しい部屋は既存の部屋の右隣に置く。 */
fun nextRoomOrigin(p: Project): Pt {
    val b = projectBounds(p) ?: return Pt(0.0, 0.0)
    return Pt(b.maxX + 300.0, b.minY)
}

// ───────── 部屋エディタ ─────────

private enum class ShapeTool(val label: String) { DRAW("図で編集"), RECT("矩形・L字"), EDGES("辺を順に入力") }

private data class EdgeRow(val length: Double, val turn: Double)

private data class Shape(val verts: List<Pt>, val openings: List<Opening>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomEditor(initial: Room, wallThickness: Double, onCancel: () -> Unit, onSave: (Room) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var ceiling by remember { mutableStateOf(initial.ceiling) }
    var shape by remember { mutableStateOf(Shape(initial.vertices.ifEmpty { rectRoom(3600.0, 2700.0) }, initial.openings)) }
    val history = remember { mutableStateListOf<Shape>() }
    var tool by remember { mutableStateOf(ShapeTool.DRAW) }
    var selV by remember { mutableStateOf<Int?>(null) }
    var selW by remember { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf<Shape?>(null) }
    var dragTotal by remember { mutableStateOf(Pt(0.0, 0.0)) }
    var dragId by remember { mutableStateOf("") }
    var editOpening by remember { mutableStateOf<Opening?>(null) }
    var notchDialog by remember { mutableStateOf(false) }
    var cutDialog by remember { mutableStateOf(false) }
    var lDialog by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<ViewT?>(null) }
    var rw by remember { mutableStateOf(bounds(shape.verts)?.w ?: 3600.0) }
    var rd by remember { mutableStateOf(bounds(shape.verts)?.h ?: 2700.0) }
    val edges = remember { mutableStateListOf<EdgeRow>() }
    var heading by remember { mutableStateOf(0.0) }
    var correct by remember { mutableStateOf(true) }
    var closure by remember { mutableStateOf(0.0) }

    val verts = shape.verts
    val wallCount = verts.size
    val valid = wallCount >= 3 && abs(signedArea(verts)) > 1
    val draft = initial.copy(name = name, kind = kind, ceiling = ceiling, vertices = verts, openings = shape.openings)
    val local = draft.copy(origin = Pt(0.0, 0.0))
    val preview = Project(rooms = listOf(local), wallThickness = wallThickness)
    val ws = walls(local)

    /** 形を変える操作。頂点数が変わったらドア・窓を付け替え、元に戻せるよう履歴に積む。 */
    fun applyShape(newVerts: List<Pt>, keepSelection: Boolean = false) {
        if (newVerts.size < 3) return
        history.add(shape)
        if (history.size > 50) history.removeAt(0)
        shape = Shape(newVerts, remapOpenings(shape.verts, newVerts, shape.openings))
        if (!keepSelection) { selV = null; selW = null }
    }

    fun loadEdges() {
        val (e, h) = edgesFromVertices(verts)
        edges.clear(); edges.addAll(e.map { EdgeRow(it.length, it.turn) }); heading = h
    }

    fun applyEdges() {
        val r = buildFromEdges(edges.map { EdgeSpec(it.length, it.turn) }, heading, correct)
        closure = r.closureError
        if (r.vertices.size >= 3) shape = Shape(r.vertices, remapOpenings(shape.verts, r.vertices, shape.openings))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "閉じる") } },
                title = { Text("部屋の編集") },
                actions = {
                    TextButton(enabled = history.isNotEmpty(), onClick = { shape = history.removeAt(history.size - 1); selV = null; selW = null }) { Text("戻す") }
                    TextButton(enabled = valid, onClick = { onSave(draft.copy(openings = shape.openings.filter { it.wallIndex < wallCount })) }) { Text("保存") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                PlanCanvas(
                    preview,
                    PlanStyle(
                        showFurniture = false, wallLabels = true,
                        handles = if (tool == ShapeTool.DRAW) verts else emptyList(),
                        selectedHandle = selV?.let { verts.getOrNull(it) },
                        selectedSegment = selW?.let { i -> ws.getOrNull(i)?.let { it.a to it.b } },
                    ),
                    previewView, { previewView = it }, Modifier.fillMaxSize(),
                    hitTest = { w, sc ->
                        if (tool != ShapeTool.DRAW) null else {
                            val vi = verts.indices.minByOrNull { (verts[it] - w).len() }
                            if (vi != null && (verts[vi] - w).len() < 26.0 / sc) "v:$vi"
                            else ws.indices.firstOrNull { distToSeg(w, ws[it].a, ws[it].b) < 18.0 / sc }?.let { "w:$it" }
                        }
                    },
                    onDragStart = { id -> dragId = id; dragStart = shape; dragTotal = Pt(0.0, 0.0) },
                    onDrag = { d ->
                        val st = dragStart ?: return@PlanCanvas
                        val sc = previewView?.scale ?: 0.1f
                        dragTotal = dragTotal + d
                        if (dragId.startsWith("v:")) {
                            val i = dragId.removePrefix("v:").toInt()
                            selV = i; selW = null
                            val others = st.verts.filterIndexed { k, _ -> k != i }
                            val np = snapPoint(st.verts[i] + dragTotal, null, others, 16.0 / sc)
                            shape = Shape(st.verts.toMutableList().apply { this[i] = np }, st.openings)
                        } else if (dragId.startsWith("w:")) {
                            val i = dragId.removePrefix("w:").toInt()
                            selW = i; selV = null
                            val u = (st.verts[(i + 1) % st.verts.size] - st.verts[i]).norm()
                            val nrm = Pt(-u.y, u.x)
                            val dist = Math.round(dragTotal.dot(nrm) / 10.0) * 10.0
                            shape = Shape(moveWall(st.verts, i, nrm * dist), st.openings)
                        }
                    },
                    onDragEnd = { moved ->
                        val st = dragStart
                        if (moved && st != null) { history.add(st); if (history.size > 50) history.removeAt(0) }
                        dragStart = null
                    },
                    onTap = { _, id ->
                        when {
                            id == null -> { selV = null; selW = null }
                            id.startsWith("v:") -> { selV = id.removePrefix("v:").toInt(); selW = null }
                            id.startsWith("w:") -> { selW = id.removePrefix("w:").toInt(); selV = null }
                        }
                    },
                )
                TextButton(onClick = { previewView = null }, modifier = Modifier.align(Alignment.TopEnd)) { Text("全体表示") }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Choices(ShapeTool.entries, tool, { it.label }, { t ->
                    if (t == ShapeTool.EDGES) loadEdges()
                    if (t == ShapeTool.RECT) { bounds(verts)?.let { rw = it.w; rd = it.h } }
                    tool = t; selV = null; selW = null
                })
                when (tool) {
                    ShapeTool.DRAW -> DrawPanel(
                        verts, ws, selV, selW,
                        onLength = { i, l -> applyShape(setWallLength(verts, i, l), keepSelection = true) },
                        onNotch = { notchDialog = true },
                        onSplit = { i -> applyShape(splitWall(verts, i)) },
                        onAddOpening = { i -> editOpening = Opening(wallIndex = i) },
                        onCut = { cutDialog = true },
                        onDeleteVertex = { i -> applyShape(deleteVertex(verts, i)) },
                    )
                    ShapeTool.RECT -> {
                        Text("床面で測った内のり寸法を入力して「この形にする」を押します。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("横幅", rw, { rw = it }, Modifier.weight(1f))
                            NumField("奥行", rd, { rd = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = rw > 0 && rd > 0, onClick = { applyShape(rectRoom(rw, rd)); previewView = null; tool = ShapeTool.DRAW }) { Text("この形にする") }
                            OutlinedButton(onClick = { lDialog = true }) { Text("L字にする") }
                        }
                    }
                    ShapeTool.EDGES -> {
                        Text("●の角から順に、壁の長さと次に曲がる角度（右回り＋）を入力します。何角形でも入力できます。", style = MaterialTheme.typography.bodySmall)
                        edges.forEachIndexed { i, e ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                NumField("壁${i + 1}", e.length, { v -> edges[i] = e.copy(length = v); applyEdges() }, Modifier.weight(1.2f))
                                if (i < edges.size - 1) {
                                    Box(Modifier.width(6.dp))
                                    NumField("曲がる", e.turn, { v -> edges[i] = e.copy(turn = v); applyEdges() }, Modifier.weight(1f), suffix = "°", allowNegative = true)
                                }
                                IconButton(onClick = { if (edges.size > 3) { edges.removeAt(i); applyEdges() } }) { Icon(Icons.Default.Delete, "削除") }
                            }
                            if (i < edges.size - 1) Row(Modifier.padding(start = 8.dp)) {
                                TextButton(onClick = { edges[i] = e.copy(turn = 90.0); applyEdges() }) { Text("右に90°") }
                                TextButton(onClick = { edges[i] = e.copy(turn = -90.0); applyEdges() }) { Text("左に90°") }
                                TextButton(onClick = { edges.add(i + 1, EdgeRow(500.0, 90.0)); applyEdges() }) { Text("後に壁を挿入") }
                            }
                        }
                        TextButton(onClick = { edges.add(EdgeRow(1000.0, 90.0)); applyEdges() }) { Text("壁を追加") }
                        val bad = closure > 10
                        Text("閉合誤差 ${fmtMm(closure)}mm" + if (bad) "（測り直し推奨。補正ONで各壁に按分）" else "",
                            color = if (bad) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = correct, onCheckedChange = { correct = it; applyEdges() })
                            Gap(); Text("誤差を按分して形を閉じる", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                SectionTitle("部屋の情報")
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Choices(RoomKind.entries, kind, { it.label }, { kind = it }, Modifier.padding(top = 6.dp))
                NumField("天井高", ceiling, { ceiling = it }, Modifier.fillMaxWidth())
                Text(areaText(verts) + "・角 ${verts.size}", style = MaterialTheme.typography.bodySmall)

                SectionTitle("ドア・窓・設備")
                Text("「壁の番号」と「壁の始点（矢印の根元側の角）からの距離」で位置を指定します。壁をタップして追加もできます。", style = MaterialTheme.typography.bodySmall)
                shape.openings.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${o.type.label}　壁${o.wallIndex + 1}", style = MaterialTheme.typography.bodyMedium,
                                color = if (o.wallIndex >= wallCount) Color(0xFFD32F2F) else Color.Unspecified)
                            Text("始点から${fmtCm(o.offset)}・幅${fmtCm(o.width)}" + openingExtra(o), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editOpening = o }) { Icon(Icons.Default.Edit, "編集") }
                        IconButton(onClick = { shape = shape.copy(openings = shape.openings.filter { it.id != o.id }) }) { Icon(Icons.Default.Delete, "削除") }
                    }
                }
                OutlinedButton(enabled = valid, onClick = { editOpening = Opening(wallIndex = selW ?: 0) }) { Text("ドア・窓などを追加") }
                Box(Modifier.height(48.dp))
            }
        }
    }

    val eo = editOpening
    if (eo != null && ws.isNotEmpty()) {
        OpeningDialog(eo, ws.map { it.length }, onDismiss = { editOpening = null }, sideLabels = ("この部屋側" to "反対側")) { done ->
            val list = if (shape.openings.any { it.id == done.id }) shape.openings.map { if (it.id == done.id) done else it } else shape.openings + done
            shape = shape.copy(openings = list)
            editOpening = null
        }
    }
    if (notchDialog) selW?.let { i ->
        NotchDialog(ws[i].length, { notchDialog = false }) { off, w, depth ->
            applyShape(addNotch(verts, i, off, w, depth)); notchDialog = false
        }
    }
    if (cutDialog) selV?.let { i ->
        CornerCutDialog({ cutDialog = false }) { a, b -> applyShape(cutCorner(verts, i, a, b)); cutDialog = false }
    }
    if (lDialog) LShapeDialog(rw, rd, { lDialog = false }) { v ->
        applyShape(v); lDialog = false; previewView = null; tool = ShapeTool.DRAW
    }
}

@Composable
private fun DrawPanel(
    verts: List<Pt>, ws: List<Wall>, selV: Int?, selW: Int?,
    onLength: (Int, Double) -> Unit, onNotch: () -> Unit, onSplit: (Int) -> Unit, onAddOpening: (Int) -> Unit,
    onCut: () -> Unit, onDeleteVertex: (Int) -> Unit,
) {
    when {
        selW != null && selW < ws.size -> {
            Text("壁${selW + 1}を選択中", style = MaterialTheme.typography.titleSmall)
            NumField("長さ（終点側を動かす）", ws[selW].length, { l -> if (l > 0) onLength(selW, l) }, Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onNotch) { Text("凹み・出っ張り") }
                OutlinedButton(onClick = { onSplit(selW) }) { Text("中点に角を追加") }
                OutlinedButton(onClick = { onAddOpening(selW) }) { Text("ドア・窓を付ける") }
            }
            Text("壁をドラッグすると平行に動きます。", style = MaterialTheme.typography.bodySmall)
        }
        selV != null && selV < verts.size -> {
            Text("角${selV + 1}を選択中", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onCut) { Text("角を四角く欠く（柱など）") }
                OutlinedButton(enabled = verts.size > 3, onClick = { onDeleteVertex(selV) }) { Text("この角を削除") }
            }
            Text("角をドラッグすると動きます（他の角と縦横がそろうと吸着）。", style = MaterialTheme.typography.bodySmall)
        }
        else -> Text(
            "角（●）をドラッグで移動、壁をドラッグで平行移動、タップで選択します。壁を選ぶと長さの指定・凹み/出っ張りの追加・角の追加ができ、角を選ぶと柱の欠き込みや削除ができます。角の数に上限はありません。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NotchDialog(wallLen: Double, onDismiss: () -> Unit, onOk: (Double, Double, Double) -> Unit) {
    var off by remember { mutableStateOf(0.0) }
    var w by remember { mutableStateOf(minOf(600.0, wallLen)) }
    var d by remember { mutableStateOf(300.0) }
    var inward by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("凹み・出っ張り") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choices(listOf(true, false), inward, { if (it) "凹み（部屋が狭くなる）" else "出っ張り（部屋が広がる）" }, { inward = it })
                Text("壁の長さ ${fmtCm(wallLen)}。始点からの距離を0にすると角から始まります。", style = MaterialTheme.typography.bodySmall)
                NumField("始点からの距離", off, { off = it }, Modifier.fillMaxWidth())
                NumField("幅（壁に沿った長さ）", w, { w = it }, Modifier.fillMaxWidth())
                NumField("奥行", d, { d = it }, Modifier.fillMaxWidth())
                if (off + w > wallLen + 1) Text("壁の長さを超えています", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = w > 0 && d > 0 && off + w <= wallLen + 1, onClick = { onOk(off, w, if (inward) d else -d) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun CornerCutDialog(onDismiss: () -> Unit, onOk: (Double, Double) -> Unit) {
    var a by remember { mutableStateOf(400.0) }
    var b by remember { mutableStateOf(400.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("角を欠く") },
        text = {
            Column {
                Text("柱やパイプスペースで角が欠けている大きさです。", style = MaterialTheme.typography.bodySmall)
                NumField("手前の壁に沿って", a, { a = it }, Modifier.fillMaxWidth())
                NumField("次の壁に沿って", b, { b = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = a > 0 && b > 0, onClick = { onOk(a, b) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
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
fun OpeningDialog(initial: Opening, wallLengths: List<Double>, onDismiss: () -> Unit, sideLabels: Pair<String, String> = "この部屋側" to "反対側", onOk: (Opening) -> Unit) {
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
                if (wallLengths.size > 1) {
                    Text("壁の番号", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Choices(wallLengths.indices.toList(), o.wallIndex, { "${it + 1}（${fmtCm(wallLengths[it])}）" }, { o = o.copy(wallIndex = it) })
                }
                NumField("始点から端まで", o.offset, { o = o.copy(offset = it) }, Modifier.fillMaxWidth())
                NumField("幅", o.width, { o = o.copy(width = it) }, Modifier.fillMaxWidth())
                if (over) Text("壁の長さ（${fmtCm(wl)}）を超えています", color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                when (o.type) {
                    OpeningType.DOOR_SWING -> {
                        Text("吊元（蝶番）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        Choices(listOf(false, true), o.hingeAtEnd, { if (it) "終点側" else "始点側" }, { o = o.copy(hingeAtEnd = it) })
                        Text("開く向き", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        Choices(listOf(true, false), o.swingInside, { if (it) sideLabels.first else sideLabels.second }, { o = o.copy(swingInside = it) })
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
                Text("全体の横幅・奥行と、右下の欠けている部分の大きさを入力します。あとから図で自由に直せます。", style = MaterialTheme.typography.bodySmall)
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
