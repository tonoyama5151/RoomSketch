package com.tonoyama.roomsketch

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(8)

/** 座標・寸法はすべて mm。y は画面下向きが正（平面図を上から見た向き）。 */
@Serializable
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun dot(o: Pt) = x * o.x + y * o.y
    fun cross(o: Pt) = x * o.y - y * o.x
    fun len() = kotlin.math.hypot(x, y)
    fun norm(): Pt { val l = len(); return if (l == 0.0) this else Pt(x / l, y / l) }
}

@Serializable
enum class OpeningType(val label: String) {
    DOOR_SWING("開き戸"),
    DOOR_SLIDING("引き戸"),
    WINDOW("窓"),
    CLOSET("収納扉"),
    PILLAR("柱"),
    OUTLET("コンセント"),
    AIRCON("エアコン"),
}

/**
 * 壁に付く要素。位置は「何番目の壁（頂点 i → i+1）の、始点から何mm」で持つため、
 * 壁の長さを直しても追従する。
 */
@Serializable
data class Opening(
    val id: String = newId(),
    val type: OpeningType = OpeningType.DOOR_SWING,
    val wallIndex: Int = 0,
    val offset: Double = 0.0,       // 壁の始点から要素の端まで
    val width: Double = 780.0,
    val depth: Double = 0.0,        // 柱の出っ張り
    val sill: Double = 0.0,         // 床からの高さ（窓の腰高・エアコン下端）
    val height: Double = 2000.0,    // 要素の高さ（窓の高さ・ドア高さ）
    val hingeAtEnd: Boolean = false,// 開き戸：吊元が壁の終点側
    val swingInside: Boolean = true,// 開き戸：この部屋側に開く
)

@Serializable
data class Room(
    val id: String = newId(),
    val name: String = "部屋",
    val origin: Pt = Pt(0.0, 0.0),          // 住戸全体での位置
    val vertices: List<Pt> = emptyList(),   // origin からの相対座標
    val ceiling: Double = 2400.0,
    val openings: List<Opening> = emptyList(),
) {
    fun worldVertices(): List<Pt> = vertices.map { it + origin }
}

@Serializable
enum class FurnitureShape(val label: String) { RECT("矩形"), ROUND("円形") }

@Serializable
enum class FurnitureStatus(val label: String) { BRING("持参"), CANDIDATE("購入候補"), DISPOSE("処分予定") }

@Serializable
data class Furniture(
    val id: String = newId(),
    val name: String = "家具",
    val category: String = "その他",
    val w: Double = 1000.0,
    val d: Double = 500.0,
    val h: Double = 700.0,
    val shape: FurnitureShape = FurnitureShape.RECT,
    val color: Int = 0,
    val front: Double = 0.0,   // 前面に必要な空き（奥行方向の手前側）
    val back: Double = 0.0,    // 背面に必要な空き
    val partW: Double = 0.0,   // 分解後寸法（0なら未設定）
    val partD: Double = 0.0,
    val partH: Double = 0.0,
    val status: FurnitureStatus = FurnitureStatus.BRING,
    val memo: String = "",
)

@Serializable
data class Placement(
    val id: String = newId(),
    val furnitureId: String,
    val x: Double,
    val y: Double,
    val rot: Double = 0.0, // 度。0 のとき幅が x 方向、前面が +y（画面下）
)

@Serializable
data class Layout(
    val id: String = newId(),
    val name: String = "A案",
    val placements: List<Placement> = emptyList(),
)

@Serializable
data class RoutePoint(
    val id: String = newId(),
    val name: String = "玄関ドア",
    val width: Double = 800.0,
    val height: Double = 2000.0,
)

@Serializable
data class Project(
    val id: String = newId(),
    val name: String = "新居",
    val rooms: List<Room> = emptyList(),
    val furniture: List<Furniture> = emptyList(),
    val layouts: List<Layout> = listOf(Layout()),
    val activeLayoutId: String? = null,
    val route: List<RoutePoint> = emptyList(),
    val aisleMin: Double = 600.0,  // 通路幅の警告しきい値（設定で変更）
    val updatedAt: Long = 0L,
) {
    fun activeLayout(): Layout = layouts.firstOrNull { it.id == activeLayoutId } ?: layouts.firstOrNull() ?: Layout(id = "default")
    fun furnitureById(id: String) = furniture.firstOrNull { it.id == id }
}

/** 家具プリセット。寸法は書き換えて使う雛形。 */
data class Preset(val category: String, val name: String, val w: Double, val d: Double, val h: Double, val front: Double = 0.0, val back: Double = 0.0)

val PRESETS = listOf(
    Preset("寝具", "シングルベッド", 970.0, 1950.0, 400.0),
    Preset("寝具", "セミダブルベッド", 1200.0, 1950.0, 400.0),
    Preset("寝具", "ダブルベッド", 1400.0, 1950.0, 400.0),
    Preset("家電", "冷蔵庫", 650.0, 700.0, 1800.0, front = 600.0),
    Preset("家電", "洗濯機", 600.0, 650.0, 1050.0, front = 500.0),
    Preset("家電", "テレビ", 1000.0, 250.0, 650.0),
    Preset("ソファ", "2人掛けソファ", 1500.0, 850.0, 800.0),
    Preset("ソファ", "3人掛けソファ", 1900.0, 900.0, 800.0),
    Preset("テーブル", "ダイニングテーブル(4人)", 1350.0, 800.0, 700.0, front = 600.0, back = 600.0),
    Preset("テーブル", "ローテーブル", 1000.0, 500.0, 400.0),
    Preset("収納", "テレビ台", 1500.0, 400.0, 450.0),
    Preset("収納", "本棚", 900.0, 300.0, 1800.0, front = 300.0),
    Preset("収納", "チェスト", 800.0, 450.0, 1000.0, front = 450.0),
    Preset("机", "デスク", 1200.0, 600.0, 720.0, front = 600.0),
)

val FURNITURE_COLORS = listOf(
    0xFFF2B134, 0xFF9FD3C7, 0xFFE88D72, 0xFFA7B8E8, 0xFFC9A7E0, 0xFFB5D99C, 0xFFD9C3A0, 0xFFBDBDBD,
)
