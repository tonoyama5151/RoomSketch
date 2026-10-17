package com.tonoyama.roomsketch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private fun normDeg(d: Double): Double { var r = d % 360.0; if (r < 0) r += 360.0; return Math.round(r * 10) / 10.0 }

private fun Project.updatePlacements(f: (List<Placement>) -> List<Placement>): Project {
    val id = activeLayout().id
    return copy(layouts = layouts.map { if (it.id == id) it.copy(placements = f(it.placements)) else it })
}

@Composable
fun LayoutTab(vm: MainViewModel, project: Project, view: ViewT?, setView: (ViewT?) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf(Pt(0.0, 0.0)) }
    var snapshotTaken by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var layoutMenu by remember { mutableStateOf(false) }
    var renameLayout by remember { mutableStateOf(false) }
    var deleteLayout by remember { mutableStateOf(false) }
    var numeric by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val layout = project.activeLayout()
    val issues = remember(project) { checkLayout(project) }
    val sel = layout.placements.firstOrNull { it.id == selected }
    val selF = sel?.let { project.furnitureById(it.furnitureId) }

    val dims = remember(project, selected) {
        if (sel == null || selF == null) emptyList() else {
            val obstacles = layout.placements.filter { it.id != sel.id }.mapNotNull { pl -> project.furnitureById(pl.furnitureId)?.let { furniturePoly(pl, it) } } +
                project.rooms.flatMap { r -> r.openings.filter { it.type == OpeningType.PILLAR }.mapNotNull { wallBox(r, it, it.depth) } }
            dimensionLines(sel, selF, project.rooms, obstacles)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 配置案の切り替え
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                project.layouts.forEach { l ->
                    FilterChip(selected = l.id == layout.id, onClick = { selected = null; vm.edit(push = false) { it.copy(activeLayoutId = l.id) } }, label = { Text(l.name) })
                }
            }
            Box {
                IconButton(onClick = { layoutMenu = true }) { Icon(Icons.Default.MoreVert, "配置案メニュー") }
                DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                    DropdownMenuItem(text = { Text("この案を複製して新しい案に") }, onClick = {
                        layoutMenu = false
                        val name = nextLayoutName(project.layouts.map { it.name })
                        val nl = layout.copy(id = newId(), name = name, placements = layout.placements.map { it.copy(id = newId()) })
                        vm.edit { it.copy(layouts = it.layouts + nl, activeLayoutId = nl.id) }
                        selected = null
                    })
                    DropdownMenuItem(text = { Text("空の新しい案") }, onClick = {
                        layoutMenu = false
                        val nl = Layout(name = nextLayoutName(project.layouts.map { it.name }))
                        vm.edit { it.copy(layouts = it.layouts + nl, activeLayoutId = nl.id) }
                        selected = null
                    })
                    DropdownMenuItem(text = { Text("案の名前を変更") }, onClick = { layoutMenu = false; renameLayout = true })
                    if (project.layouts.size > 1) DropdownMenuItem(text = { Text("この案を削除") }, onClick = { layoutMenu = false; deleteLayout = true })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasSize = it }) {
            PlanCanvas(
                project = project,
                style = PlanStyle(selectedPlacement = selected, issues = issues, dims = dims),
                view = view,
                onView = { setView(it) },
                modifier = Modifier.fillMaxSize(),
                hitTest = { w ->
                    layout.placements.lastOrNull { pl -> project.furnitureById(pl.furnitureId)?.let { pointInPolygon(w, furniturePoly(pl, it)) } == true }?.id
                },
                onDragStart = { id ->
                    selected = id
                    snapshotTaken = false
                    layout.placements.firstOrNull { it.id == id }?.let { raw = Pt(it.x, it.y) }
                },
                onDrag = { d ->
                    val id = selected ?: return@PlanCanvas
                    if (!snapshotTaken) { vm.snapshot(); snapshotTaken = true }
                    raw = raw + d
                    vm.edit(push = false) { p ->
                        p.updatePlacements { list ->
                            list.map { pl ->
                                if (pl.id != id) pl else {
                                    val f = p.furnitureById(pl.furnitureId) ?: return@map pl
                                    val moved = pl.copy(x = raw.x, y = raw.y)
                                    val snap = if (abs(pl.rot % 90.0) < 0.01) snapFurnitureDelta(furniturePoly(moved, f), p.rooms, 80.0) else Pt(0.0, 0.0)
                                    moved.copy(x = raw.x + snap.x, y = raw.y + snap.y)
                                }
                            }
                        }
                    }
                },
                onTap = { _, id -> selected = id },
            )
            Column(Modifier.align(Alignment.TopEnd)) {
                TextButton(onClick = { setView(null) }) { Text("全体表示") }
            }
            if (issues.isNotEmpty()) {
                val errors = issues.count { it.level == Level.ERROR }
                AssistChip(
                    onClick = { showIssues = true },
                    label = { Text("要確認 ${issues.map { it.message }.distinct().size}件") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = if (errors > 0) Color(0xFFFDE7E4) else Color(0xFFFFF3E0)),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
        HorizontalDivider()
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (sel != null && selF != null) {
                    Text("${selF.name}　${sizeText(selF)}　${fmtNum(sel.rot)}°", style = MaterialTheme.typography.titleSmall)
                    issues.filter { it.placementId == sel.id }.map { it.message }.distinct().take(2).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { rotate(vm, sel.id, 90.0) }) { Text("90°回転") }
                        OutlinedButton(onClick = { rotate(vm, sel.id, -15.0) }) { Text("−15°") }
                        OutlinedButton(onClick = { rotate(vm, sel.id, 15.0) }) { Text("+15°") }
                        OutlinedButton(onClick = { numeric = true }) { Text("数値で指定") }
                        OutlinedButton(onClick = {
                            vm.edit { p -> p.updatePlacements { l -> l.filter { it.id != sel.id } } }
                            selected = null
                        }) { Text("配置から外す") }
                    }
                } else {
                    val unplaced = project.furniture.filter { f -> f.status != FurnitureStatus.DISPOSE && layout.placements.none { it.furnitureId == f.id } }
                    when {
                        project.rooms.isEmpty() -> Text("先に「間取り」タブで部屋を作ってください。", style = MaterialTheme.typography.bodySmall)
                        project.furniture.isEmpty() -> Text("「家具」タブで家具を登録すると、ここから置けます。", style = MaterialTheme.typography.bodySmall)
                        unplaced.isEmpty() -> Text("すべての家具を配置しました。家具をタップで選択、ドラッグで移動します。", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            Text("タップで画面の中央に置きます（未配置 ${unplaced.size}点）", style = MaterialTheme.typography.bodySmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                items(unplaced, key = { it.id }) { f ->
                                    AssistChip(
                                        onClick = {
                                            val c = view?.w(Offset(canvasSize.width / 2f, canvasSize.height / 2f)) ?: projectBounds(project)?.let { Pt((it.minX + it.maxX) / 2, (it.minY + it.maxY) / 2) } ?: Pt(0.0, 0.0)
                                            val pl = Placement(furnitureId = f.id, x = c.x, y = c.y)
                                            vm.edit { p -> p.updatePlacements { it + pl } }
                                            selected = pl.id
                                        },
                                        label = { Text(f.name) },
                                        leadingIcon = { Box(Modifier.size(12.dp).background(Color(FURNITURE_COLORS[f.color.mod(FURNITURE_COLORS.size)]), CircleShape)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIssues) AlertDialog(
        onDismissRequest = { showIssues = false },
        title = { Text("要確認の箇所") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                issues.map { it.level to it.message }.distinct().sortedBy { it.first.ordinal }.forEach { (lv, m) ->
                    Text((if (lv == Level.ERROR) "● " else "▲ ") + m, color = if (lv == Level.ERROR) Color(0xFFC62828) else Color(0xFFE65100),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showIssues = false }) { Text("閉じる") } },
    )

    if (renameLayout) TextInputDialog("案の名前", "名前", layout.name, { renameLayout = false }) { n ->
        vm.edit { p -> p.copy(layouts = p.layouts.map { if (it.id == layout.id) it.copy(name = n) else it }) }; renameLayout = false
    }
    if (deleteLayout) ConfirmDialog("「${layout.name}」を削除しますか？", "この案の配置が消えます（家具の登録は残ります）。", { deleteLayout = false }) {
        vm.edit { p ->
            val rest = p.layouts.filter { it.id != layout.id }
            p.copy(layouts = rest, activeLayoutId = rest.first().id)
        }
        deleteLayout = false; selected = null
    }
    if (numeric && sel != null && selF != null) NumericPlaceDialog(project, sel, selF, { numeric = false }) { np ->
        vm.edit { p -> p.updatePlacements { l -> l.map { if (it.id == np.id) np else it } } }
        numeric = false
    }
}

private fun rotate(vm: MainViewModel, id: String, by: Double) {
    vm.edit { p -> p.updatePlacements { l -> l.map { if (it.id == id) it.copy(rot = normDeg(it.rot + by)) else it } } }
}

fun nextLayoutName(names: List<String>): String {
    for (ch in 'A'..'Z') { val n = "${ch}案"; if (n !in names) return n }
    return "新しい案"
}

/** 選択した家具を、その部屋の左端・上端からの距離と角度で指定する。 */
@Composable
fun NumericPlaceDialog(project: Project, pl: Placement, f: Furniture, onDismiss: () -> Unit, onOk: (Placement) -> Unit) {
    val room = roomOf(Pt(pl.x, pl.y), project.rooms)
    val rb = room?.let { bounds(it.worldVertices()) }
    val fb0 = bounds(furniturePoly(pl, f))!!
    var left by remember { mutableStateOf(rb?.let { fb0.minX - it.minX } ?: 0.0) }
    var top by remember { mutableStateOf(rb?.let { fb0.minY - it.minY } ?: 0.0) }
    var rot by remember { mutableStateOf(pl.rot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置と角度") },
        text = {
            Column {
                Text(if (room != null) "「${room.name}」の左端・上端の壁から、家具の端までの距離です。" else "部屋の外にあるため、角度のみ変更できます。",
                    style = MaterialTheme.typography.bodySmall)
                if (rb != null) {
                    NumField("左の壁から", left, { left = it }, Modifier.fillMaxWidth())
                    NumField("上の壁から", top, { top = it }, Modifier.fillMaxWidth())
                }
                NumField("角度", rot, { rot = it }, Modifier.fillMaxWidth(), suffix = "°")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rotated = pl.copy(rot = normDeg(rot))
                val fb = bounds(furniturePoly(rotated, f))!!
                val res = if (rb == null) rotated else rotated.copy(
                    x = rotated.x + (rb.minX + left - fb.minX),
                    y = rotated.y + (rb.minY + top - fb.minY),
                )
                onOk(res)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
