package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.parser.CadParseResult
import com.example.data.parser.GltfParser
import com.example.data.parser.IgzParser
import com.example.data.parser.ObjParser
import com.example.data.parser.SkpParser
import com.example.domain.model.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParserUnitTest {

    private val igzParser = IgzParser()
    private val skpParser = SkpParser()
    private val objParser = ObjParser()
    private val gltfParser = GltfParser()

    @Test
    fun `igz parser opens all six bundled official IngeTrazo sample models`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val samples = listOf(
            "samples/banca-pergola-yanque.igz",
            "samples/luminaria-solar-yanque.igz",
            "samples/pileta-fuente-yanque.igz",
            "samples/arco-yanque.igz",
            "samples/capas-prueba.igz",
            "samples/pestanas-prueba.igz"
        )
        for (assetPath in samples) {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val result = igzParser.parse(bytes, "asset:$assetPath", assetPath, ModelFormat.IGZ)
            assertTrue("Expected Success for $assetPath, got $result", result is CadParseResult.Success)
            val doc = (result as CadParseResult.Success).document
            assertEquals(ModelFormat.IGZ, doc.format)
            assertTrue("Expected faces or edges in $assetPath", doc.faces.isNotEmpty() || doc.edges.isNotEmpty())
            assertTrue("Expected layers in $assetPath", doc.layers.isNotEmpty())
            assertTrue("Expected valid bounds in $assetPath", doc.bounds.isValid)
            if (assetPath.endsWith("pileta-fuente-yanque.igz")) {
                assertTrue("Expected >30k faces in pileta, got ${doc.faces.size}", doc.faces.size > 30_000)
                assertTrue("Expected >70k edges in pileta, got ${doc.edges.size}", doc.edges.size > 70_000)
                assertTrue("Expected water material in pileta", doc.materials.containsKey("water_calm"))
                assertTrue("Expected textures in pileta", doc.textures.size >= 2)
            }
            if (assetPath.endsWith("arco-yanque.igz")) {
                assertTrue("Expected >10k faces in arco, got ${doc.faces.size}", doc.faces.size > 10_000)
                assertTrue("Expected >20k edges in arco, got ${doc.edges.size}", doc.edges.size > 20_000)
                assertEquals(6, doc.textures.size)
            }
        }
    }

    @Test
    fun `document parser registry sniffs extensionless IGZ v2 ZIP and SKP VFF files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = com.example.data.parser.DocumentParserRegistry(
            listOf(igzParser, skpParser, objParser, gltfParser)
        )

        val bancaBytes = context.assets.open("samples/banca-pergola-yanque.igz").use { it.readBytes() }
        // Intentionally pass display title without .igz extension (reproduces extensionless SAF / sample names)
        val bancaRes = registry.parse(bancaBytes, "asset:banca", "Banca & Pérgola Yanque")
        assertTrue("Expected extensionless IGZ v2 ZIP to open, got $bancaRes", bancaRes is CadParseResult.Success)
        val bancaDoc = (bancaRes as CadParseResult.Success).document
        assertEquals(ModelFormat.IGZ, bancaDoc.format)
        assertTrue("Expected embedded textures in Banca & Pérgola Yanque", bancaDoc.textures.isNotEmpty())

        val luminariaBytes = context.assets.open("samples/luminaria-solar-yanque.igz").use { it.readBytes() }
        val lumRes = registry.parse(luminariaBytes, "asset:luminaria", "Luminaria Solar Yanque")
        assertTrue("Expected extensionless Luminaria IGZ v2 ZIP to open, got $lumRes", lumRes is CadParseResult.Success)
        val lumDoc = (lumRes as CadParseResult.Success).document
        assertEquals(ModelFormat.IGZ, lumDoc.format)
        assertTrue("Expected colored faces in Luminaria", lumDoc.faces.any { it.color != com.example.domain.model.CadColor.DEFAULT_FRONT })

        val skp2021Bytes = javaClass.classLoader!!.getResourceAsStream("samples/nesting_2021.skp")!!.use {
            it.readBytes()
        }
        val skpRes = registry.parse(skp2021Bytes, "skp_no_ext", "Extensionless SketchUp File")
        assertTrue("Expected extensionless SKP VFF to open, got $skpRes", skpRes is CadParseResult.Success)
    }

    @Test
    fun `igz parser decodes Format 3 binary geom block ZIP`() {
        // Build minimal Format 3 ZIP with 1 square face (4 verts) and 4 edges in geom/root.bin
        val posFloats = floatArrayOf(
            0f, 0f, 0f,
            2f, 0f, 0f,
            2f, 3f, 0f,
            0f, 3f, 0f
        )
        val edgesInts = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0)
        val ringsInts = intArrayOf(0, 1, 2, 3)
        val ringSizes = intArrayOf(4)
        val ringCounts = intArrayOf(1)

        val binOut = ByteArrayOutputStream()
        fun writeInts(arr: IntArray): Int {
            val off = binOut.size()
            val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            arr.forEach { bb.putInt(it) }
            binOut.write(bb.array())
            return off
        }
        fun writeFloats(arr: FloatArray): Int {
            val off = binOut.size()
            val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            arr.forEach { bb.putFloat(it) }
            binOut.write(bb.array())
            return off
        }

        val posOff = writeFloats(posFloats)
        val edgesOff = writeInts(edgesInts)
        val ringsOff = writeInts(ringsInts)
        val sizesOff = writeInts(ringSizes)
        val countsOff = writeInts(ringCounts)
        val geomBytes = binOut.toByteArray()

        val docJson = """
            {
              "igz_format": 3,
              "app_version": "0.10.0",
              "scene": {
                "dimension_style": {"units": "m", "decimals": 2},
                "layers": [{"name": "Layer 0", "visible": true}],
                "geom": {
                  "member": "geom/root.bin",
                  "arrays": {
                    "pos": ["<f4", [4, 3], $posOff],
                    "edges": ["<i4", [4, 2], $edgesOff],
                    "rings": ["<i4", [4], $ringsOff],
                    "ring_sizes": ["<i4", [1], $sizesOff],
                    "ring_counts": ["<i4", [1], $countsOff]
                  }
                }
              }
            }
        """.trimIndent()

        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(ZipEntry("document.json"))
            zos.write(docJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("geom/root.bin"))
            zos.write(geomBytes)
            zos.closeEntry()
        }

        val res = igzParser.parse(zipBaos.toByteArray(), "f3", "Format3 Test", ModelFormat.IGZ)
        assertTrue(res is CadParseResult.Success)
        val doc = (res as CadParseResult.Success).document
        assertEquals("IGZ v3", doc.formatVersion)
        assertEquals(1, doc.faces.size)
        assertEquals(4, doc.edges.size)
        assertEquals(2.0, doc.bounds.size.x, 1e-4)
        assertEquals(3.0, doc.bounds.size.y, 1e-4)
    }

    @Test
    fun `skp parser parses VFF 2021 fixture and cleanly flags legacy 2020 MFC file`() {
        val skp2021Bytes = javaClass.classLoader!!.getResourceAsStream("samples/nesting_2021.skp")!!.use {
            it.readBytes()
        }
        val res2021 = skpParser.parse(skp2021Bytes, "skp2021", "nesting_2021.skp", ModelFormat.SKP)
        assertTrue("Expected SKP 2021 success, got $res2021", res2021 is CadParseResult.Success)
        val doc2021 = (res2021 as CadParseResult.Success).document
        assertEquals(ModelFormat.SKP, doc2021.format)
        assertTrue(doc2021.faces.isNotEmpty() || doc2021.edges.isNotEmpty())
        assertTrue(doc2021.nodes.size >= 2)

        val skp2020Bytes = javaClass.classLoader!!.getResourceAsStream("samples/nesting_2020.skp")!!.use {
            it.readBytes()
        }
        val res2020 = skpParser.parse(skp2020Bytes, "skp2020", "nesting_2020.skp", ModelFormat.SKP)
        assertTrue(res2020 is CadParseResult.Failure)
        val fail = res2020 as CadParseResult.Failure
        assertTrue(fail.message.contains("MFC", ignoreCase = true) || fail.details.orEmpty().contains("ingetrazo_converter.py"))
    }

    @Test
    fun `obj parser converts Y-up coordinates to Z-up and extracts faces and edges`() {
        val objText = """
            o BeamBox
            v 0 0 0
            v 2 0 0
            v 2 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val res = objParser.parse(objText, "obj1", "beam.obj", ModelFormat.OBJ)
        assertTrue(res is CadParseResult.Success)
        val doc = (res as CadParseResult.Success).document
        assertEquals(1, doc.faces.size)
        assertEquals(4, doc.edges.size)
        // OBJ Y=1 maps to IngeTrazo Z=1
        assertEquals(1.0, doc.bounds.max.z, 1e-6)
        assertEquals(2.0, doc.bounds.max.x, 1e-6)
    }

    @Test
    fun `gltf parser decodes binary GLB container`() {
        // Construct minimal GLB with 1 triangle (3 float32 positions + 3 uint16 indices)
        val posBytes = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0f); putFloat(0f); putFloat(0f)
            putFloat(1f); putFloat(0f); putFloat(0f)
            putFloat(0f); putFloat(2f); putFloat(0f)
        }.array()
        val idxBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(2); putShort(0) // padded to 4-byte boundary
        }.array()
        val binRaw = posBytes + idxBytes

        val jsonStr = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"name":"Tri","mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"buffers":[{"byteLength":${binRaw.size}}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}"""
        val jsonPad = (4 - (jsonStr.toByteArray(Charsets.UTF_8).size % 4)) % 4
        val jsonChunk = jsonStr.toByteArray(Charsets.UTF_8) + ByteArray(jsonPad) { 0x20 }
        val totalLen = 12 + 8 + jsonChunk.size + 8 + binRaw.size

        val glb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('g'.code.toByte()); put('l'.code.toByte()); put('T'.code.toByte()); put('F'.code.toByte())
            putInt(2)
            putInt(totalLen)
            putInt(jsonChunk.size); putInt(0x4E4F534A); put(jsonChunk)
            putInt(binRaw.size); putInt(0x004E4942); put(binRaw)
        }.array()

        val res = gltfParser.parse(glb, "glb1", "tri.glb", ModelFormat.GLB)
        assertTrue("Expected GLB parse success, got $res", res is CadParseResult.Success)
        val doc = (res as CadParseResult.Success).document
        assertEquals(ModelFormat.GLB, doc.format)
        assertEquals(1, doc.faces.size)
        assertEquals(3, doc.edges.size)
    }

    @Test
    fun `parsers reject corrupt and empty payloads gracefully`() {
        val corrupt = "not a 3d model".toByteArray()
        assertTrue(igzParser.parse(corrupt, "bad1", "bad.igz", ModelFormat.IGZ) is CadParseResult.Failure)
        assertTrue(skpParser.parse(corrupt, "bad2", "bad.skp", ModelFormat.SKP) is CadParseResult.Failure)
        assertTrue(objParser.parse(corrupt, "bad3", "bad.obj", ModelFormat.OBJ) is CadParseResult.Failure)
        assertTrue(gltfParser.parse(corrupt, "bad4", "bad.gltf", ModelFormat.GLTF) is CadParseResult.Failure)
    }
}
