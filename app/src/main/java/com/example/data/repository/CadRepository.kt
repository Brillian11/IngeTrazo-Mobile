package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.data.local.MeasurementDao
import com.example.data.local.RecentFileDao
import com.example.data.local.RecentFileEntity
import com.example.data.parser.CadParseResult
import com.example.data.parser.DocumentParserRegistry
import com.example.domain.model.CadDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class SampleModelInfo(
    val assetPath: String,
    val title: String,
    val subtitle: String,
    val formatTag: String
)

class CadRepository(
    private val context: Context,
    private val parserRegistry: DocumentParserRegistry,
    private val recentFileDao: RecentFileDao,
    private val measurementDao: MeasurementDao
) {
    private val _activeDocument = MutableStateFlow<CadDocument?>(null)
    val activeDocument: StateFlow<CadDocument?> = _activeDocument.asStateFlow()

    val recentFiles: Flow<List<RecentFileEntity>> = recentFileDao.observeRecentFiles()

    val bundledSamples: List<SampleModelInfo> = listOf(
        SampleModelInfo(
            assetPath = "samples/banca-pergola-yanque.igz",
            title = "Banca & Pérgola Yanque",
            subtitle = "Timber pergola & concrete bench · Multi-material textures",
            formatTag = "IGZ v2"
        ),
        SampleModelInfo(
            assetPath = "samples/luminaria-solar-yanque.igz",
            title = "Luminaria Solar Yanque",
            subtitle = "4.5m solar street lamp · Colored materials & Saved Views",
            formatTag = "IGZ v2"
        ),
        SampleModelInfo(
            assetPath = "samples/pileta-fuente-yanque.igz",
            title = "Pileta Fuente Yanque",
            subtitle = "Octagonal plaza fountain · 22 instances & translucent water",
            formatTag = "IGZ v2"
        ),
        SampleModelInfo(
            assetPath = "samples/arco-yanque.igz",
            title = "Arco Monumental Yanque",
            subtitle = "Stone arch, interior rebar (X-Ray) & sillar textures",
            formatTag = "IGZ v2"
        ),
        SampleModelInfo(
            assetPath = "samples/capas-prueba.igz",
            title = "Capas & Secciones",
            subtitle = "21 groups · 22 architectural layers · Dimensions & sections",
            formatTag = "IGZ v1"
        ),
        SampleModelInfo(
            assetPath = "samples/pestanas-prueba.igz",
            title = "Pestañas & Escenas",
            subtitle = "Simple massing house · 4 saved camera scenes",
            formatTag = "IGZ v1"
        )
    )

    suspend fun openUri(uri: Uri): CadParseResult = withContext(Dispatchers.IO) {
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Non-persistable URI from third-party provider is still readable in-session
            }
            val displayName = resolveDisplayName(uri) ?: "model.igz"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext CadParseResult.Failure("Unable to open file", "Could not read input stream for $uri")
            val docKey = "uri:$uri"
            val result = parserRegistry.parse(bytes, docKey, displayName)
            if (result is CadParseResult.Success) {
                _activeDocument.value = result.document
                recentFileDao.upsert(
                    RecentFileEntity(
                        documentKey = docKey,
                        displayName = displayName,
                        uriString = uri.toString(),
                        isSampleAsset = false,
                        assetPath = null,
                        formatName = result.document.format.extension.uppercase(),
                        fileSizeBytes = bytes.size.toLong(),
                        faceCount = result.document.stats.faceCount,
                        edgeCount = result.document.stats.edgeCount,
                        lastOpenedAt = System.currentTimeMillis()
                    )
                )
            }
            result
        } catch (e: Exception) {
            CadParseResult.Failure("Failed to read selected file", e.localizedMessage)
        }
    }

    suspend fun openSampleAsset(assetPath: String, displayTitle: String): CadParseResult =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.assets.open(assetPath).use { it.readBytes() }
                val fileName = assetPath.substringAfterLast('/')
                val docKey = "asset:$assetPath"
                val result = parserRegistry.parse(
                    bytes = bytes,
                    documentKey = docKey,
                    fileName = fileName,
                    formatHint = com.example.domain.model.ModelFormat.IGZ
                )
                if (result is CadParseResult.Success) {
                    val titledDoc = if (displayTitle.isNotBlank()) {
                        result.document.copy(title = displayTitle)
                    } else {
                        result.document
                    }
                    _activeDocument.value = titledDoc
                    recentFileDao.upsert(
                        RecentFileEntity(
                            documentKey = docKey,
                            displayName = titledDoc.title,
                            uriString = "",
                            isSampleAsset = true,
                            assetPath = assetPath,
                            formatName = titledDoc.format.extension.uppercase(),
                            fileSizeBytes = bytes.size.toLong(),
                            faceCount = titledDoc.stats.faceCount,
                            edgeCount = titledDoc.stats.edgeCount,
                            lastOpenedAt = System.currentTimeMillis()
                        )
                    )
                    return@withContext CadParseResult.Success(titledDoc)
                }
                result
            } catch (e: Exception) {
                CadParseResult.Failure("Failed to load sample asset", e.localizedMessage)
            }
        }

    suspend fun openRecent(entity: RecentFileEntity): CadParseResult {
        return if (entity.isSampleAsset && !entity.assetPath.isNullOrEmpty()) {
            openSampleAsset(entity.assetPath, entity.displayName)
        } else {
            openUri(Uri.parse(entity.uriString))
        }
    }

    suspend fun removeRecent(documentKey: String) {
        recentFileDao.deleteByKey(documentKey)
    }

    fun observeLengths(docKey: String): Flow<List<LengthMeasurementEntity>> =
        measurementDao.observeLengths(docKey)

    fun observeAngles(docKey: String): Flow<List<AngleMeasurementEntity>> =
        measurementDao.observeAngles(docKey)

    fun observeCountedAngles(docKey: String): Flow<List<CountedAngleEntity>> =
        measurementDao.observeCountedAngles(docKey)

    suspend fun addLength(entity: LengthMeasurementEntity): Long =
        measurementDao.insertLength(entity)

    suspend fun updateLengthLabel(entity: LengthMeasurementEntity, newLabel: String) =
        measurementDao.updateLength(entity.copy(label = newLabel))

    suspend fun deleteLength(id: Long) =
        measurementDao.deleteLengthById(id)

    suspend fun addAngle(entity: AngleMeasurementEntity): Long =
        measurementDao.insertAngle(entity)

    suspend fun updateAngleLabel(entity: AngleMeasurementEntity, newLabel: String) =
        measurementDao.updateAngle(entity.copy(label = newLabel))

    suspend fun deleteAngle(id: Long) =
        measurementDao.deleteAngleById(id)

    suspend fun addCountedAngle(entity: CountedAngleEntity): Long =
        measurementDao.insertCountedAngle(entity)

    suspend fun addCountedAngles(entities: List<CountedAngleEntity>) =
        measurementDao.insertCountedAngles(entities)

    suspend fun updateCountedAngle(entity: CountedAngleEntity) =
        measurementDao.updateCountedAngle(entity)

    suspend fun deleteCountedAngle(id: Long) =
        measurementDao.deleteCountedAngleById(id)

    suspend fun clearCountedAngles(docKey: String) =
        measurementDao.clearCountedAnglesForDoc(docKey)

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
