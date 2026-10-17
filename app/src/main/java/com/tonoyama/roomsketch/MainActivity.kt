package com.tonoyama.roomsketch

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E5E4E), secondary = Color(0xFFB07B2C))) {
                App(vm)
            }
        }
    }

    override fun onStop() {
        vm.flush()
        super.onStop()
    }
}

@Composable
fun App(vm: MainViewModel) {
    val current by vm.current.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val p = current
    if (p == null) HomeScreen(vm, snackbar) else ProjectScreen(vm, p, snackbar)
}

// ───────── ホーム（プロジェクト一覧） ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val list by vm.projects.collectAsStateWithLifecycle()
    var newDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFrom) }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RoomSketch") },
                actions = { TextButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("ファイルを読み込む") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newDialog = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("新しく作る") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (list.isEmpty()) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("物件（新居など）ごとにプロジェクトを作ります。", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(4.dp))
                Text("1. 間取り：部屋の形と寸法、ドア・窓を入力\n2. 家具：手持ちの家具の寸法を登録\n3. 配置：平面図に置いて、重なり・ドアとの干渉を確認\n4. 搬入：玄関やエレベーターを通るか確認",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(4.dp))
                Text("家族から受け取ったファイルは右上の「ファイルを読み込む」から開けます。", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { vm.open(s.id) }) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text("部屋 ${s.rooms}・家具 ${s.furniture}・更新 ${fmt.format(Date(s.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; renameTarget = s })
                                DropdownMenuItem(text = { Text("複製") }, onClick = { menu = false; vm.duplicate(s.id) })
                                DropdownMenuItem(text = { Text("削除") }, onClick = { menu = false; deleteTarget = s })
                            }
                        }
                    }
                }
            }
        }
    }

    if (newDialog) TextInputDialog("新しいプロジェクト", "名前（例：新居）", "新居", { newDialog = false }) { vm.create(it); newDialog = false }
    renameTarget?.let { t -> TextInputDialog("名前を変更", "名前", t.name, { renameTarget = null }) { vm.renameById(t.id, it); renameTarget = null } }
    deleteTarget?.let { t ->
        ConfirmDialog("「${t.name}」を削除しますか？", "元に戻せません。", { deleteTarget = null }) { vm.delete(t.id); deleteTarget = null }
    }
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onOk) { Text("削除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// ───────── プロジェクト画面 ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(vm: MainViewModel, project: Project, snackbar: SnackbarHostState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var floorView by remember { mutableStateOf<ViewT?>(null) }
    var layoutView by remember { mutableStateOf<ViewT?>(null) }
    var editingRoom by remember { mutableStateOf<Room?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportTo) }

    val room = editingRoom
    if (room != null) {
        BackHandler { editingRoom = null }
        RoomEditor(
            initial = room,
            onCancel = { editingRoom = null },
            onSave = { r ->
                vm.edit { p ->
                    if (p.rooms.any { it.id == r.id }) p.copy(rooms = p.rooms.map { if (it.id == r.id) r else it })
                    else p.copy(rooms = p.rooms + r)
                }
                editingRoom = null
                floorView = null
            },
        )
        return
    }

    BackHandler { vm.close() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                title = { Text(project.name, maxLines = 1) },
                actions = {
                    TextButton(onClick = vm::undo, enabled = canUndo) { Text("戻す") }
                    TextButton(onClick = vm::redo, enabled = canRedo) { Text("やり直し") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("家族に送る（編集データ）") }, onClick = {
                                menu = false
                                vm.shareIntentForJson()?.let { ctx.startActivity(it) }
                            })
                            DropdownMenuItem(text = { Text("配置図を画像で送る") }, onClick = {
                                menu = false
                                val bmp = renderPlanBitmap(project, tm, density)
                                val f = vm.shareFile("${vm.safeName(project.name)}_${vm.safeName(project.activeLayout().name)}.png")
                                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                ctx.startActivity(vm.sendIntent(f, "image/png"))
                            })
                            DropdownMenuItem(text = { Text("ファイルに保存") }, onClick = {
                                menu = false
                                exporter.launch("${vm.safeName(project.name)}.roomsketch.json")
                            })
                            DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; rename = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("間取り") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("家具") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Place, null) }, label = { Text("配置") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.CheckCircle, null) }, label = { Text("搬入") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> FloorTab(vm, project, floorView, { floorView = it }, onEdit = { editingRoom = it })
                1 -> FurnitureTab(vm, project)
                2 -> LayoutTab(vm, project, layoutView, { layoutView = it })
                else -> RouteTab(vm, project)
            }
        }
    }

    if (rename) TextInputDialog("名前を変更", "名前", project.name, { rename = false }) { n -> vm.edit { it.copy(name = n) }; rename = false }
}

/** 配置図をPNG用のビットマップに描く。 */
fun renderPlanBitmap(project: Project, tm: TextMeasurer, density: Density): Bitmap {
    val b = projectBounds(project)
    val w = 2000
    val h = if (b == null || b.w <= 0) 1500 else (w * b.h / b.w).toInt().coerceIn(900, 4000) + 160
    val img = ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    val size = Size(w.toFloat(), h.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size) {
        val v = ViewT.fit(b, Size(size.width, size.height - 100f), 80f).let { it.copy(oy = it.oy + 100f) }
        drawPlan(project, v, tm, PlanStyle(issues = checkLayout(project), title = "${project.name}　${project.activeLayout().name}"))
    }
    return img.asAndroidBitmap()
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun Gap(w: Int = 8) = Spacer(Modifier.width(w.dp))

fun shareText(ctx: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "共有"))
}
