package com.tonoyama.roomsketch

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

data class ProjectSummary(val id: String, val name: String, val updatedAt: Long, val rooms: Int, val furniture: Int)

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dir = File(app.filesDir, "projects").apply { mkdirs() }

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _current = MutableStateFlow<Project?>(null)
    val current: StateFlow<Project?> = _current.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var saveJob: Job? = null

    init { refreshList() }

    fun consumeMessage() { _message.value = null }
    fun toast(msg: String) { _message.value = msg }

    // ───────── 一覧 ─────────

    fun refreshList() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
                    runCatching { JSON.decodeFromString(Project.serializer(), f.readText()) }.getOrNull()
                        ?.let { ProjectSummary(it.id, it.name, it.updatedAt, it.rooms.size, it.furniture.size) }
                }?.sortedByDescending { it.updatedAt } ?: emptyList()
            }
            _projects.value = list
        }
    }

    fun create(name: String) {
        val p = Project(name = name.ifBlank { "新居" }, updatedAt = System.currentTimeMillis())
        writeNow(p)
        openProject(p)
    }

    fun open(id: String) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { JSON.decodeFromString(Project.serializer(), File(dir, "$id.json").readText()) }.getOrNull()
            }
            if (p == null) toast("読み込めませんでした") else openProject(p)
        }
    }

    private fun openProject(p: Project) {
        undo.clear(); redo.clear(); updateFlags()
        _current.value = p.copy(activeLayoutId = p.activeLayoutId ?: p.layouts.firstOrNull()?.id)
    }

    fun close() {
        flush()
        _current.value = null
        refreshList()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        refreshList()
    }

    fun renameById(id: String, name: String) {
        val f = File(dir, "$id.json")
        val p = runCatching { JSON.decodeFromString(Project.serializer(), f.readText()) }.getOrNull() ?: return
        writeNow(p.copy(name = name))
        refreshList()
    }

    fun duplicate(id: String) {
        val f = File(dir, "$id.json")
        val p = runCatching { JSON.decodeFromString(Project.serializer(), f.readText()) }.getOrNull() ?: return
        writeNow(p.copy(id = newId(), name = p.name + "のコピー", updatedAt = System.currentTimeMillis()))
        refreshList()
    }

    // ───────── 編集（元に戻す対応） ─────────

    /** 変更を加える。push=false はドラッグ中など、直前に snapshot() を取ってある場合。 */
    fun edit(push: Boolean = true, f: (Project) -> Project) {
        val cur = _current.value ?: return
        if (push) pushUndo(cur)
        _current.value = f(cur).copy(updatedAt = System.currentTimeMillis())
        scheduleSave()
    }

    /** ドラッグ開始時に1回だけ呼び、元に戻す地点を作る。 */
    fun snapshot() { _current.value?.let { pushUndo(it) } }

    private fun pushUndo(p: Project) {
        undo.addLast(p)
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
        updateFlags()
    }

    fun undo() {
        val cur = _current.value ?: return
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(cur)
        _current.value = prev
        updateFlags(); scheduleSave()
    }

    fun redo() {
        val cur = _current.value ?: return
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(cur)
        _current.value = next
        updateFlags(); scheduleSave()
    }

    private fun updateFlags() {
        _canUndo.value = undo.isNotEmpty(); _canRedo.value = redo.isNotEmpty()
    }

    // ───────── 保存 ─────────

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            _current.value?.let { p -> withContext(Dispatchers.IO) { write(p) } }
        }
    }

    fun flush() {
        saveJob?.cancel()
        _current.value?.let { writeNow(it) }
    }

    private fun writeNow(p: Project) { write(p) }

    private fun write(p: Project) {
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(JSON.encodeToString(Project.serializer(), p))
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    override fun onCleared() { flush(); super.onCleared() }

    // ───────── 書き出し・読み込み・共有 ─────────

    fun exportTo(uri: Uri) {
        val p = _current.value ?: return
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(JSON.encodeToString(Project.serializer(), p).toByteArray())
            }
        }.onSuccess { toast("書き出しました") }.onFailure { toast("書き出しに失敗しました") }
    }

    /** JSONを読み込む。同じプロジェクト（同じID）があれば新しい内容で上書きする。 */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    JSON.decodeFromString(Project.serializer(), text ?: "")
                }.getOrNull()
            }
            if (p == null) { toast("RoomSketchのファイルではないようです"); return@launch }
            val existed = File(dir, "${p.id}.json").exists()
            withContext(Dispatchers.IO) { write(p) }
            refreshList()
            toast(if (existed) "「${p.name}」を受け取った内容で更新しました" else "「${p.name}」を読み込みました")
        }
    }

    fun shareIntentForJson(): Intent? {
        val p = _current.value ?: return null
        flush()
        val f = shareFile("${safeName(p.name)}.roomsketch.json")
        f.writeText(JSON.encodeToString(Project.serializer(), p))
        return sendIntent(f, "application/json")
    }

    fun shareFile(name: String): File {
        val d = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        return File(d, name)
    }

    fun sendIntent(f: File, mime: String): Intent {
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "共有")
    }

    // ───────── 下絵 ─────────

    /** 画像を読み込み、長辺 2000px 以下の JPEG にして下絵に設定する。 */
    fun importBackground(uri: Uri) {
        viewModelScope.launch {
            val bg = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
                    val k = minOf(1.0, 2000.0 / maxOf(src.width, src.height))
                    val bmp = if (k < 1.0) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val cur = _current.value
                    val b = cur?.let { projectBounds(it) }
                    Background(
                        data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                        wPx = bmp.width, hPx = bmp.height,
                        mmPerPx = 10000.0 / bmp.width, // 仮に幅10mとして置く。あとで縮尺を合わせる
                        x = b?.minX ?: 0.0, y = b?.minY ?: 0.0,
                    )
                }.getOrNull()
            }
            if (bg == null) toast("画像を読み込めませんでした")
            else { edit { it.copy(background = bg) }; toast("下絵を置きました。次に「縮尺を合わせる」で実寸に合わせてください") }
        }
    }

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { "roomsketch" }
}

/** 下絵の Base64 を画像にする。 */
fun decodeBackground(b: Background?): ImageBitmap? = runCatching {
    if (b == null) return@runCatching null
    val bytes = Base64.decode(b.data, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
