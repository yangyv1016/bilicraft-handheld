package com.bilicraft.handheld.resourcepack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.LruCache
import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.NbtTag
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException

data class ItemIcon(val bitmap: Bitmap?, val reason: String? = null)

/** Static GUI previews. Unsupported special renderers use an explicit placeholder. */
class ResourcePackItemIcons(private val archives: List<ResourcePackArchive>, private val headSkins: PlayerHeadSkins? = null) {
    private val icons = object : LruCache<ItemDetails, ItemIcon>(8 * 1024 * 1024) {
        override fun sizeOf(key: ItemDetails, value: ItemIcon) = (value.bitmap?.allocationByteCount ?: 0) + 512
    }
    private val textures = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun render(item: ItemDetails): ItemIcon {
        icons.get(item)?.let { return it }
        val result = try {
            val modelId = (item.components.entries["minecraft:item_model"] as? NbtTag.NbtString)?.value ?: item.id
            val definition = json(modelId, "items") ?: error("资源包未提供该物品的图标")
            val models = ItemModelSelector.select(definition.getJSONObject("model"), item)
            val output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            for (model in models) drawModel(canvas, model, item)
            ItemIcon(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ItemIcon(null, error.message ?: "该物品图标暂不可用")
        }
        if (result.bitmap != null) icons.put(item, result)
        return result
    }

    private fun json(id: String, directory: String): JSONObject? = archives.asReversed()
        .firstNotNullOfOrNull { it.readAsset(id, directory, ".json") }?.let { JSONObject(it.toString(Charsets.UTF_8)) }

    private fun texture(id: String): Bitmap {
        textures.get(id)?.let { return it }
        val bytes = archives.asReversed().firstNotNullOfOrNull { it.readAsset(id, "textures", ".png") }
            ?: error("资源包缺少贴图：$id")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304) { "物品贴图尺寸超出限制" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("物品贴图无法解码")
        val metadata = archives.asReversed().firstNotNullOfOrNull { it.readAsset(id, "textures", ".png.mcmeta") }
            ?.let { JSONObject(it.toString(Charsets.UTF_8)).optJSONObject("animation") }
        val result = if (metadata == null) bitmap else {
            val width = metadata.optInt("width", bitmap.width)
            val height = metadata.optInt("height", width)
            val first = metadata.optJSONArray("frames")?.opt(0)
            val frame = when (first) { is Number -> first.toInt(); is JSONObject -> first.getInt("index"); else -> 0 }
            require(width > 0 && height > 0 && bitmap.width % width == 0 && bitmap.height % height == 0 &&
                frame >= 0 && frame < (bitmap.width / width) * (bitmap.height / height)) { "动画贴图帧无效" }
            Bitmap.createBitmap(bitmap, (frame % (bitmap.width / width)) * width, (frame / (bitmap.width / width)) * height, width, height)
        }
        textures.put(id, result)
        return result
    }

    private suspend fun drawModel(canvas: Canvas, definition: JSONObject, item: ItemDetails) {
        val playerHead = definition.getString("type").removePrefix("minecraft:") == "special"
        val skin = if (playerHead) {
            val profile = PlayerHeadProfile.from(item.components.entries["minecraft:profile"])
            if (profile.texture != null) texture(profile.texture.removeSuffix(".png"))
            else if (profile.dynamic || profile.skinUrl() != null) {
                requireNotNull(headSkins) { "尚未初始化头颅皮肤下载" }.load(profile) { texture(it.defaultTexture()) }
            } else texture(profile.defaultTexture())
        } else null
        val chain = mutableListOf<JSONObject>()
        val visited = mutableSetOf<String>()
        var id = ResourcePackFonts.normalizeId(definition.getString(if (playerHead) "base" else "model"))
        var generated = false
        while (true) {
            require(visited.add(id) && visited.size <= 32) { "物品模型 parent 循环引用" }
            if (id in setOf("minecraft:item/generated", "minecraft:item/handheld", "minecraft:item/handheld_rod", "minecraft:builtin/generated")) {
                generated = true
                break
            }
            val model = json(id, "models") ?: error("资源包缺少模型：$id")
            chain.add(0, model)
            val parent = model.optString("parent")
            if (parent.isEmpty()) break
            id = ResourcePackFonts.normalizeId(parent)
        }
        val textureNames = mutableMapOf<String, String>()
        var elements: JSONArray? = null
        var gui: JSONObject? = null
        for (model in chain) {
            model.optJSONObject("textures")?.let { values -> values.keys().forEach { textureNames[it] = values.getString(it) } }
            model.optJSONArray("elements")?.let { elements = it }
            model.optJSONObject("display")?.optJSONObject("gui")?.let { gui = it }
        }
        if (skin != null) {
            require(skin.width == 64 && skin.height in listOf(32, 64)) { "头颅皮肤尺寸无效" }
            elements = JSONArray()
            // Skull geometry occupies the lower half of a block; the base supplies GUI transforms.
            for (hat in listOf(false, true)) {
                val inflate = if (hat) 0.25 else 0.0
                val sides = JSONObject()
                val offset = if (hat) 32 else 0
                for ((side, rectangle) in mapOf(
                    "south" to listOf(8, 8, 16, 16), "north" to listOf(24, 8, 32, 16),
                    "west" to listOf(0, 8, 8, 16), "east" to listOf(16, 8, 24, 16),
                    "up" to listOf(8, 0, 16, 8), "down" to listOf(16, 0, 24, 8)
                )) {
                    val uv = rectangle.mapIndexed { index, value ->
                        if (index % 2 == 0) (value + offset) / 4.0 else value * 16.0 / skin.height
                    }
                    sides.put(side, JSONObject().put("texture", "#player_skin").put("uv", JSONArray(uv)))
                }
                elements.put(JSONObject().put("from", JSONArray(listOf(4 - inflate, -inflate, 4 - inflate)))
                    .put("to", JSONArray(listOf(12 + inflate, 8 + inflate, 12 + inflate))).put("faces", sides))
            }
        }
        fun resolveTexture(reference: String): Bitmap {
            if (reference == "#player_skin" && skin != null) return skin
            var name = reference
            val seen = mutableSetOf<String>()
            while (name.startsWith('#')) {
                require(seen.add(name)) { "贴图循环引用" }
                name = textureNames[name.drop(1)] ?: error("模型缺少贴图引用：$name")
            }
            return texture(ResourcePackFonts.normalizeId(name))
        }
        fun paint(tintIndex: Int): Paint {
            val tint = definition.optJSONArray("tints")?.optJSONObject(tintIndex)
            val custom = (item.components.entries["minecraft:custom_model_data"] as? NbtTag.NbtCompound)?.entries
            val rgb = if (tint == null) 0xffffff else when (tint.getString("type").removePrefix("minecraft:")) {
                "constant" -> tint.getInt("value")
                "grass" -> {
                    val temperature = tint.getDouble("temperature").coerceIn(0.0, 1.0)
                    val downfall = tint.getDouble("downfall").coerceIn(0.0, 1.0) * temperature
                    texture("minecraft:colormap/grass").getPixel(((1 - temperature) * 255).toInt(), ((1 - downfall) * 255).toInt())
                }
                "map_color" -> (item.components.entries["minecraft:map_color"] as? NbtTag.NbtInt)?.value ?: tint.getInt("default")
                "custom_model_data" -> ((custom?.get("colors") as? NbtTag.NbtList)?.items?.getOrNull(tint.optInt("index", 0)) as? NbtTag.NbtInt)?.value ?: tint.getInt("default")
                "dye" -> (item.components.entries["minecraft:dyed_color"] as? NbtTag.NbtInt)?.value ?: tint.getInt("default")
                "potion" -> (((item.components.entries["minecraft:potion_contents"] as? NbtTag.NbtCompound)?.entries?.get("custom_color")) as? NbtTag.NbtInt)?.value ?: tint.getInt("default")
                else -> error("暂不支持的物品颜色规则")
            }
            return Paint().apply { isFilterBitmap = false; colorFilter = PorterDuffColorFilter(0xff000000.toInt() or rgb, PorterDuff.Mode.MULTIPLY) }
        }
        if (generated && elements == null) {
            val layers = textureNames.keys.filter { it.matches(Regex("layer[0-9]+")) }.sortedBy { it.drop(5).toInt() }
            require(layers.isNotEmpty()) { "物品模型没有贴图图层" }
            for (layer in layers) {
                val bitmap = resolveTexture("#$layer")
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 128f, 128f), paint(layer.drop(5).toInt()))
            }
            return
        }
        val cubes = elements ?: error("暂不支持此模型的专用渲染器")
        require(cubes.length() <= 2048) { "物品模型过于复杂" }
        val guiRotation = vector(gui?.optJSONArray("rotation"), Vec(0.0, 0.0, 0.0))
        val guiScale = vector(gui?.optJSONArray("scale"), Vec(1.0, 1.0, 1.0))
        val guiTranslation = vector(gui?.optJSONArray("translation"), Vec(0.0, 0.0, 0.0))
        val faces = mutableListOf<Face>()
        for (index in 0 until cubes.length()) {
            val element = cubes.getJSONObject(index)
            val from = vector(element.getJSONArray("from"), Vec(0.0, 0.0, 0.0))
            val to = vector(element.getJSONArray("to"), Vec(0.0, 0.0, 0.0))
            val rotation = element.optJSONObject("rotation")
            val origin = vector(rotation?.optJSONArray("origin"), Vec(8.0, 8.0, 8.0))
            val sides = element.getJSONObject("faces")
            for (side in sides.keys()) {
                val face = sides.getJSONObject(side)
                val vertices = when (side) {
                    "north" -> listOf(Vec(to.x,to.y,from.z),Vec(from.x,to.y,from.z),Vec(from.x,from.y,from.z),Vec(to.x,from.y,from.z))
                    "south" -> listOf(Vec(from.x,to.y,to.z),Vec(to.x,to.y,to.z),Vec(to.x,from.y,to.z),Vec(from.x,from.y,to.z))
                    "west" -> listOf(Vec(from.x,to.y,from.z),Vec(from.x,to.y,to.z),Vec(from.x,from.y,to.z),Vec(from.x,from.y,from.z))
                    "east" -> listOf(Vec(to.x,to.y,to.z),Vec(to.x,to.y,from.z),Vec(to.x,from.y,from.z),Vec(to.x,from.y,to.z))
                    "up" -> listOf(Vec(from.x,to.y,from.z),Vec(to.x,to.y,from.z),Vec(to.x,to.y,to.z),Vec(from.x,to.y,to.z))
                    "down" -> listOf(Vec(from.x,from.y,to.z),Vec(to.x,from.y,to.z),Vec(to.x,from.y,from.z),Vec(from.x,from.y,from.z))
                    else -> error("模型面方向无效")
                }.map { vertex ->
                    var point = vertex
                    if (rotation != null) {
                        val angle = rotation.getDouble("angle")
                        val axis = rotation.getString("axis")
                        point = (point - origin).rotate(axis, angle)
                        if (rotation.optBoolean("rescale")) {
                            val factor = 1.0 / cos(Math.toRadians(angle))
                            point *= when (axis) { "x" -> Vec(1.0,factor,factor); "y" -> Vec(factor,1.0,factor); else -> Vec(factor,factor,1.0) }
                        }
                        point += origin
                    }
                    // ItemTransform applies T * Rx * Ry * Rz * S to each model vertex.
                    point = ((point - Vec(8.0,8.0,8.0)) * guiScale)
                        .rotate("z",guiRotation.z).rotate("y",guiRotation.y).rotate("x",guiRotation.x)
                    point + guiTranslation
                }
                val uv = face.optJSONArray("uv") ?: when (side) {
                    "up" -> JSONArray(listOf(from.x, from.z, to.x, to.z))
                    "down" -> JSONArray(listOf(from.x, 16-to.z, to.x, 16-from.z))
                    "north" -> JSONArray(listOf(16-to.x, 16-to.y, 16-from.x, 16-from.y))
                    "south" -> JSONArray(listOf(from.x, 16-to.y, to.x, 16-from.y))
                    "west" -> JSONArray(listOf(from.z, 16-to.y, to.z, 16-from.y))
                    else -> JSONArray(listOf(16-to.z, 16-to.y, 16-from.z, 16-from.y))
                }
                faces += Face(vertices, resolveTexture(face.getString("texture")), uv, face.optInt("rotation",0), paint(face.optInt("tintindex",-1)))
            }
        }
        for (face in faces.sortedBy { it.vertices.sumOf { vertex -> vertex.z } }) {
            val destination = face.vertices.flatMap { listOf((64+it.x*8).toFloat(), (64-it.y*8).toFloat()) }.toFloatArray()
            if (skin != null && (destination[2] - destination[0]) * (destination[5] - destination[1]) -
                (destination[3] - destination[1]) * (destination[4] - destination[0]) <= 0f) continue
            val u1 = face.uv.getDouble(0).toFloat()/16 * face.bitmap.width
            val v1 = face.uv.getDouble(1).toFloat()/16 * face.bitmap.height
            val u2 = face.uv.getDouble(2).toFloat()/16 * face.bitmap.width
            val v2 = face.uv.getDouble(3).toFloat()/16 * face.bitmap.height
            val corners = listOf(u1 to v1,u2 to v1,u2 to v2,u1 to v2)
            val source = (0..3).flatMap { corners[(it + face.rotation/90) % 4].let { pair -> listOf(pair.first,pair.second) } }.toFloatArray()
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(source,0,destination,0,4)) continue
            val path = Path().apply { moveTo(destination[0],destination[1]); for (i in 1..3) lineTo(destination[i*2],destination[i*2+1]); close() }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(face.bitmap,matrix,face.paint)
            canvas.restore()
        }
    }

    private data class Face(val vertices: List<Vec>, val bitmap: Bitmap, val uv: JSONArray, val rotation: Int, val paint: Paint)
    private fun vector(array: JSONArray?, default: Vec): Vec = if (array == null) default else Vec(array.getDouble(0),array.getDouble(1),array.getDouble(2))
    private data class Vec(val x: Double,val y: Double,val z: Double) {
        operator fun plus(other: Vec) = Vec(x+other.x,y+other.y,z+other.z)
        operator fun minus(other: Vec) = Vec(x-other.x,y-other.y,z-other.z)
        operator fun times(other: Vec) = Vec(x*other.x,y*other.y,z*other.z)
        fun rotate(axis: String, degrees: Double): Vec {
            val cosine = cos(Math.toRadians(degrees)); val sine = sin(Math.toRadians(degrees))
            return when (axis) { "x" -> Vec(x,y*cosine-z*sine,y*sine+z*cosine); "y" -> Vec(x*cosine+z*sine,y,-x*sine+z*cosine); "z" -> Vec(x*cosine-y*sine,x*sine+y*cosine,z); else -> error("模型旋转轴无效") }
        }
    }
}
