package com.tonoyama.roomsketch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

fun sizeText(f: Furniture) = "${fmtCm(f.w)}×${fmtCm(f.d)}×高${fmtCm(f.h)}"

// ───────── 家具タブ ─────────

@Composable
fun FurnitureTab(vm: MainViewModel, project: Project) {
    var editing by remember { mutableStateOf<Furniture?>(null) }
    val layout = project.activeLayout()
    val placedRoom = layout.placements.associate { pl -> pl.furnitureId to roomOf(Pt(pl.x, pl.y), project.rooms)?.name }
    val unplaced = project.furniture.count { f -> f.status != FurnitureStatus.DISPOSE && layout.placements.none { it.furnitureId == f.id } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("家具 ${project.furniture.size}点・「${layout.name}」で未配置 ${unplaced}点", style = MaterialTheme.typography.bodySmall)
            }
            items(project.furniture, key = { it.id }) { f ->
                Card(Modifier.fillMaxWidth().clickable { editing = f }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).background(Color(FURNITURE_COLORS[f.color.mod(FURNITURE_COLORS.size)]), CircleShape))
                        Gap(12)
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleSmall)
                            Text(sizeText(f), style = MaterialTheme.typography.bodySmall)
                            val where = if (placedRoom.containsKey(f.id)) (placedRoom[f.id] ?: "部屋の外") else "未配置"
                            Text("${f.status.label}・${f.category}・$where", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Furniture(name = "") },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("家具を登録") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { f ->
        val exists = project.furniture.any { it.id == f.id }
        FurnitureDialog(
            initial = f,
            isNew = !exists,
            onDismiss = { editing = null },
            onSave = { nf ->
                vm.edit { p -> if (exists) p.copy(furniture = p.furniture.map { if (it.id == nf.id) nf else it }) else p.copy(furniture = p.furniture + nf) }
                editing = null
            },
            onDelete = {
                vm.edit { p ->
                    p.copy(
                        furniture = p.furniture.filter { it.id != f.id },
                        layouts = p.layouts.map { l -> l.copy(placements = l.placements.filter { it.furnitureId != f.id }) },
                    )
                }
                editing = null
            },
            onDuplicate = { nf ->
                vm.edit { p -> p.copy(furniture = p.furniture + nf.copy(id = newId(), name = nf.name + "(2)")) }
                editing = null
            },
        )
    }
}

@Composable
fun FurnitureDialog(initial: Furniture, isNew: Boolean, onDismiss: () -> Unit, onSave: (Furniture) -> Unit, onDelete: () -> Unit, onDuplicate: (Furniture) -> Unit) {
    var f by remember { mutableStateOf(initial) }
    var presetMenu by remember { mutableStateOf(false) }
    var showParts by remember { mutableStateOf(initial.partW > 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "家具を登録" else "家具を編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedButton(onClick = { presetMenu = true }) { Text("プリセットから入力") }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        PRESETS.forEach { p ->
                            DropdownMenuItem(text = { Text("${p.name}（${fmtCm(p.w)}×${fmtCm(p.d)}）") }, onClick = {
                                presetMenu = false
                                f = f.copy(name = if (f.name.isBlank()) p.name else f.name, category = p.category, w = p.w, d = p.d, h = p.h, front = p.front, back = p.back)
                            })
                        }
                    }
                }
                OutlinedTextField(f.name, { f = f.copy(name = it) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.category, { f = f.copy(category = it) }, label = { Text("カテゴリ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("幅", f.w, { f = f.copy(w = it) }, Modifier.weight(1f))
                    NumField("奥行", f.d, { f = f.copy(d = it) }, Modifier.weight(1f))
                }
                NumField("高さ", f.h, { f = f.copy(h = it) }, Modifier.fillMaxWidth())
                Text("形", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Choices(FurnitureShape.entries, f.shape, { it.label }, { f = f.copy(shape = it) })
                Text("色", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FURNITURE_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).background(Color(c), CircleShape)
                                .border(if (f.color == i) 3.dp else 1.dp, if (f.color == i) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable { f = f.copy(color = i) }
                        )
                    }
                }
                SectionTitle("使うために必要な空き")
                Text("扉・引き出しを開ける、椅子を引くなど。前面は平面図で線を引いた側です。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("前面", f.front, { f = f.copy(front = it) }, Modifier.weight(1f))
                    NumField("背面", f.back, { f = f.copy(back = it) }, Modifier.weight(1f))
                }
                TextButton(onClick = { showParts = !showParts }) { Text(if (showParts) "分解後の寸法を閉じる" else "分解して運ぶ場合の寸法") }
                if (showParts) {
                    Text("搬入判定に使います。3つとも入力したときだけ有効です。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumField("幅", f.partW, { f = f.copy(partW = it) }, Modifier.weight(1f))
                        NumField("奥行", f.partD, { f = f.copy(partD = it) }, Modifier.weight(1f))
                        NumField("高さ", f.partH, { f = f.copy(partH = it) }, Modifier.weight(1f))
                    }
                }
                Text("状態", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Choices(FurnitureStatus.entries, f.status, { it.label }, { f = f.copy(status = it) })
                OutlinedTextField(f.memo, { f = f.copy(memo = it) }, label = { Text("メモ") }, modifier = Modifier.fillMaxWidth())
                if (!isNew) Row {
                    TextButton(onClick = { onDuplicate(f) }) { Text("複製") }
                    TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = f.name.isNotBlank() && f.w > 0 && f.d > 0, onClick = { onSave(f.copy(name = f.name.trim())) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// ───────── 搬入タブ ─────────

private val ROUTE_PRESETS = listOf(
    RoutePoint(name = "エレベーターの扉", width = 800.0, height = 2000.0),
    RoutePoint(name = "玄関ドア", width = 800.0, height = 2000.0),
    RoutePoint(name = "廊下", width = 780.0, height = 2400.0),
    RoutePoint(name = "室内ドア", width = 700.0, height = 2000.0),
)

@Composable
fun RouteTab(vm: MainViewModel, project: Project) {
    var editing by remember { mutableStateOf<RoutePoint?>(null) }
    val ctx = LocalContext.current
    val targets = project.furniture.filter { it.status != FurnitureStatus.DISPOSE }
    val results = targets.map { it to routeCheck(it, project.route) }.sortedBy { (_, r) -> r.all { it.ok } }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            SectionTitle("搬入経路の開口（実測の幅・高さ）")
            Text("家具の短い2辺が開口に収まるかで判定します。傾けての搬入や曲がり角は考慮しない目安です。", style = MaterialTheme.typography.bodySmall)
        }
        items(project.route, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { editing = r }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall)
                        Text("幅${fmtCm(r.width)}・高さ${fmtCm(r.height)}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.edit { p -> p.copy(route = p.route.filter { it.id != r.id }) } }) { Icon(Icons.Default.Delete, "削除") }
                }
            }
        }
        item {
            Text("追加", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ROUTE_PRESETS.forEach { pr -> OutlinedButton(onClick = { editing = pr.copy(id = newId()) }) { Text(pr.name) } }
            }
        }
        item { SectionTitle("判定結果") }
        if (project.route.isEmpty()) item { Text("経路を追加すると判定します。", style = MaterialTheme.typography.bodySmall) }
        else items(results, key = { it.first.id }) { (f, rs) ->
            val ng = rs.filter { !it.ok }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (ng.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFDE7E4)),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (ng.isEmpty()) "通過可　" else "要確認　") + f.name, style = MaterialTheme.typography.titleSmall,
                        color = if (ng.isEmpty()) MaterialTheme.colorScheme.primary else Color(0xFFC62828))
                    val dims = carryDims(f)
                    val parts = f.partW > 0 && f.partD > 0 && f.partH > 0
                    Text("運ぶときの寸法 ${dims.joinToString("×") { fmtCm(it) }}" + if (parts) "（分解後）" else "", style = MaterialTheme.typography.bodySmall)
                    if (ng.isNotEmpty()) Text("通らない可能性：" + ng.joinToString("、") { it.point.name }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionTitle("当日用")
            OutlinedButton(onClick = { shareText(ctx, movingList(project)) }) { Text("部屋ごとの家具リストを共有") }
        }
    }

    editing?.let { r ->
        RouteDialog(r, { editing = null }) { nr ->
            vm.edit { p -> if (p.route.any { it.id == nr.id }) p.copy(route = p.route.map { if (it.id == nr.id) nr else it }) else p.copy(route = p.route + nr) }
            editing = null
        }
    }
}

/** 引っ越し当日に「どの家具をどの部屋へ」を伝えるテキスト。 */
fun movingList(p: Project): String {
    val l = p.activeLayout()
    val byRoom = l.placements.mapNotNull { pl -> p.furnitureById(pl.furnitureId)?.let { (roomOf(Pt(pl.x, pl.y), p.rooms)?.name ?: "部屋の外") to it } }
        .groupBy({ it.first }, { it.second })
    val sb = StringBuilder("【${p.name}】${l.name} 搬入リスト\n")
    p.rooms.map { it.name }.plus("部屋の外").distinct().forEach { rn ->
        val fs = byRoom[rn] ?: return@forEach
        sb.append("\n■ $rn\n")
        fs.forEach { sb.append("・${it.name}（${sizeText(it)}）\n") }
    }
    val unplaced = p.furniture.filter { f -> f.status != FurnitureStatus.DISPOSE && l.placements.none { it.furnitureId == f.id } }
    if (unplaced.isNotEmpty()) {
        sb.append("\n■ 未配置\n")
        unplaced.forEach { sb.append("・${it.name}\n") }
    }
    return sb.toString()
}

@Composable
fun RouteDialog(initial: RoutePoint, onDismiss: () -> Unit, onOk: (RoutePoint) -> Unit) {
    var r by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搬入経路") },
        text = {
            Column {
                OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField("通れる幅（ドアを開けた状態の有効幅）", r.width, { r = r.copy(width = it) }, Modifier.fillMaxWidth())
                NumField("通れる高さ", r.height, { r = r.copy(height = it) }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOk(r) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
