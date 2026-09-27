package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.Nbt
import com.bilicraft.handheld.protocol.NbtTag
import com.bilicraft.handheld.protocol.PacketKey
import com.bilicraft.handheld.protocol.PacketPhase
import com.bilicraft.handheld.protocol.PaletteRegistry
import com.bilicraft.handheld.protocol.ResourcePackRequest
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourcePackTest {
    @Test fun officialVanillaAssetsContainItemDefinitionsModelsAndTextures() {
        val base = ResourcePackArchive(File("src/main/assets/minecraft/vanilla-items-1.21.11.zip"))
        for (item in listOf("stone", "diamond_sword", "diamond_helmet", "elytra")) {
            assertNotNull(base.readAsset("minecraft:$item", "items", ".json"))
        }
        assertNotNull(base.readAsset("minecraft:block/stone", "models", ".json"))
        assertNotNull(base.readAsset("minecraft:item/diamond_sword", "textures", ".png"))
    }
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun pack(extra: Map<String, String> = emptyMap()) = zip(mapOf("pack.mcmeta" to """{"pack":{"pack_format":75}}""") + extra)
    private fun archive(bytes: ByteArray): ResourcePackArchive = ResourcePackArchive(temporary.newFile().apply { writeBytes(bytes) })
    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun request(url: String, hash: String) = ResourcePackRequest(UUID.randomUUID(), url, hash, false, emptyList())

    @Test fun packetIdsUseTheCorrectPhaseAndOnlyTheVerifiedPlayVersion() {
        val palette = PaletteRegistry.forProtocol(774)
        assertEquals(PacketKey.CB_RESOURCE_PACK, palette.cbKey(0x4f, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_REMOVE_RESOURCE_PACK, palette.cbKey(0x4e, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_CONFIG_RESOURCE_PACK, palette.cbKey(0x09, PacketPhase.CONFIGURATION))
        assertEquals(PacketKey.CB_CONFIG_REMOVE_RESOURCE_PACK, palette.cbKey(0x08, PacketPhase.CONFIGURATION))
        assertEquals(0x30, palette.sbId(PacketKey.SB_RESOURCE_PACK_RESPONSE))
        assertEquals(0x06, palette.sbId(PacketKey.SB_CONFIG_RESOURCE_PACK_RESPONSE))
        assertNull(PaletteRegistry.forProtocol(773).sbId(PacketKey.SB_RESOURCE_PACK_RESPONSE))
    }

    @Test fun fontFilesAndVersion75OverlaysAreIndexedInPriorityOrder() {
        val contents = mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":75},"overlays":{"entries":[{"directory":"old","formats":{"min_inclusive":18,"max_inclusive":45}},{"directory":"current","min_format":63,"max_format":75}]}}""",
            "assets/demo/font/default.json" to """{"providers":[],"source":"base"}""",
            "old/assets/demo/font/default.json" to """{"providers":[],"source":"wrong"}""",
            "current/assets/demo/font/default.json" to """{"providers":[],"source":"current"}""",
            "current/assets/demo/textures/icon.png" to "image"
        )
        val pack = archive(zip(contents))
        assertEquals("current", pack.fonts.getValue("demo:default").getString("source"))
        assertEquals("image", pack.readAsset("demo:icon.png", "textures")!!.toString(Charsets.UTF_8))
        assertNull(pack.readAsset("demo:missing.png", "textures"))
    }

    @Test fun malformedArchivesAndUnsafeAssetReferencesAreRejected() {
        assertThrows(Exception::class.java) { archive(zip(mapOf("elsewhere" to "data"))) }
        assertThrows(Exception::class.java) { archive(pack(mapOf("assets/demo/font/x.json" to "bad json"))) }
        val pack = archive(pack())
        assertThrows(IllegalArgumentException::class.java) { pack.readAsset("demo:../private", "textures") }
        assertThrows(IllegalArgumentException::class.java) { ResourcePackArchive.readLimited("12345".byteInputStream(),4) }
    }

    @Test fun downloadChecksHashAndReusesOnlyApprovedExactServerCache() = runBlocking {
        val bytes = pack()
        val server = TestServer(bytes)
        try {
            val repository = ResourcePackRepository(temporary.newFolder())
            val offer = request("http://127.0.0.1:${server.port}/pack", sha1(bytes))
            assertFalse(repository.hasApprovedCache("server-a", offer))
            val file = repository.load("server-a", offer, emptySet()) { _, _ -> }
            ResourcePackArchive(file)
            repository.markApproved("server-a", offer)
            assertTrue(repository.hasApprovedCache("server-a", offer))
            assertFalse(repository.hasApprovedCache("server-b", offer))
            assertFalse(repository.hasApprovedCache("server-a", offer.copy(sha1 = "1".repeat(40))))
            repository.load("server-a", offer, emptySet()) { _, _ -> }
            val renewedOffer = offer.copy(url = "http://127.0.0.1:${server.port}/pack?signature=renewed", sha1 = offer.sha1.uppercase())
            assertTrue(repository.hasApprovedCache("server-a", renewedOffer))
            assertEquals(file, repository.load("server-a", renewedOffer, emptySet()) { _, _ -> })
            assertEquals(1, server.calls.get())
            file.writeText("corrupt")
            repository.load("server-a", offer, emptySet()) { _, _ -> }
            assertEquals(2, server.calls.get())
        } finally { server.close() }
    }

    @Test fun failedHashDoesNotLeaveCacheOrPartialFiles() = runBlocking {
        val server = TestServer(byteArrayOf(1,2,3))
        try {
            val directory = temporary.newFolder()
            val repository = ResourcePackRepository(directory)
            val result = runCatching { repository.load("server", request("http://127.0.0.1:${server.port}/", "0".repeat(40)), emptySet()) { _, _ -> } }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-1"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { server.close() }
    }

    @Test fun invalidUrlsAndOversizeResponsesFailBeforeWritingBody() = runBlocking {
        val repository = ResourcePackRepository(temporary.newFolder())
        assertTrue(runCatching { repository.load("server", request("file:///etc/passwd", ""), emptySet()) { _, _ -> } }.isFailure)
        val server = TestServer(byteArrayOf(), 129L*1024*1024)
        try {
            assertTrue(runCatching { repository.load("server", request("http://127.0.0.1:${server.port}/", ""), emptySet()) { _, _ -> } }.exceptionOrNull()?.message.orEmpty().contains("128 MiB"))
        } finally { server.close() }
    }

    @Test fun emptyHashCannotSilentlyReuseAnApprovedDownload() {
        val directory = temporary.newFolder()
        val repository = ResourcePackRepository(directory)
        val offer = request("https://example.com/pack", "")
        File(directory, "${repository.cacheKey("s",offer)}.zip").writeBytes(pack())
        repository.markApproved("s", offer)
        assertFalse(repository.hasApprovedCache("s",offer))
    }

    @Test fun actualServerCustomModelStringsSelectTheRightModel() {
        val definition = JSONObject("""{"type":"minecraft:select","property":"minecraft:custom_model_data","cases":[{"when":["oraxen:cat_staff","oraxen:other"],"model":{"type":"minecraft:model","model":"cutiecatpack/staff"}}],"fallback":{"type":"minecraft:model","model":"item/wooden_sword"}}""")
        val components = Nbt.fromJson(JSONObject("""{"minecraft:custom_model_data":{"strings":["oraxen:cat_staff"]}}""")) as NbtTag.NbtCompound
        assertEquals("cutiecatpack/staff", ItemModelSelector.select(definition, ItemDetails("minecraft:wooden_sword",1,components)).single().getString("model"))
        assertEquals("item/wooden_sword", ItemModelSelector.select(definition, ItemDetails("minecraft:wooden_sword",1)).single().getString("model"))
    }

    @Test fun numericThresholdsAreInclusiveAndGuiItemsAreNotBeingUsed() {
        val definition = JSONObject("""{"type":"minecraft:condition","property":"minecraft:using_item","on_true":{"type":"minecraft:model","model":"using"},"on_false":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data","entries":[{"threshold":2,"model":{"type":"minecraft:model","model":"two"}},{"threshold":1,"model":{"type":"minecraft:model","model":"one"}}],"fallback":{"type":"minecraft:model","model":"zero"}}}""")
        val components = Nbt.fromJson(JSONObject("""{"minecraft:custom_model_data":{"floats":[2.0]}}""")) as NbtTag.NbtCompound
        assertEquals("two",ItemModelSelector.select(definition,ItemDetails("minecraft:paper",1,components)).single().getString("model"))
        assertEquals("zero",ItemModelSelector.select(definition,ItemDetails("minecraft:paper",1)).single().getString("model"))
    }

    @Test fun unknownModelRulesDoNotChooseAnInventedFallback() {
        val definition = JSONObject("""{"type":"minecraft:select","property":"mod:unknown","cases":[],"fallback":{"type":"minecraft:model","model":"wrong"}}""")
        assertThrows(IllegalStateException::class.java) { ItemModelSelector.select(definition,ItemDetails("minecraft:stone",1)) }
    }
    private class TestServer(private val bytes: ByteArray, private val length: Long = bytes.size.toLong()) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val calls = AtomicInteger()
        private val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    calls.incrementAndGet()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (closed: SocketException) {
                if (!socket.isClosed) throw closed
            }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }

}
