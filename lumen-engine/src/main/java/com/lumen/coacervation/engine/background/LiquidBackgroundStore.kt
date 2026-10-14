package com.lumen.coacervation.engine.background

import com.lumen.coacervation.engine.LumenEngine
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.graphics.ColorUtils
import androidx.annotation.WorkerThread
import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import com.lumen.coacervation.engine.model.LumenPalette
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

public sealed interface LiquidBackgroundImportResult {
    data class Success(val config: LiquidBackgroundConfig) : LiquidBackgroundImportResult
    data class Failure(val reason: LiquidBackgroundImportFailure) : LiquidBackgroundImportResult
}

public enum class LiquidBackgroundImportFailure {
    READ_FAILED,
    FILE_TOO_LARGE,
    UNSUPPORTED_IMAGE,
    DIMENSIONS_TOO_LARGE,
    ENCODE_FAILED,
    STORAGE_FAILED
}

public data class LiquidBackgroundReadResult(
    val config: LiquidBackgroundConfig,
    val issue: LiquidBackgroundConfigIssue,
    val assetPresent: Boolean
)

/**
 * 自定义背景的唯一存储边界。
 *
 * 外部 URI 只在导入期间读取；成功后配置仅引用应用私有目录内的 generation 文件。新资产完整
 * 写入并回读验证后才提交配置。只有新配置或旧配置回滚的持久化已确认，才清理其它代次；
 * 提交中断或存储持续失败时保留可能被磁盘配置引用的资产，供重试或恢复使用。
 */
public object LiquidBackgroundStore {
    // 文件名可配置（LumenStorageNames），默认 lumen_ 前缀；来源工程为 liquid_background_preferences / ui_skin_background。
    private val PREF_FILE: String get() = LumenEngine.config.storage.backgroundPreferences
    private val ASSET_DIRECTORY: String get() = LumenEngine.config.storage.backgroundAssetDirectory
    private const val ASSET_PREFIX = "background_"
    private const val ASSET_SUFFIX = ".img"
    private const val TEMP_PREFIX = "liquid_import_"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val JPEG_QUALITY = 92

    private val transactions = LiquidBackgroundTransactions()

    fun read(context: Context): LiquidBackgroundReadResult {
        val decoded = transactions.read(preferenceAccess(context))
        val config = decoded.config
        val assetPresent = config.mode == LiquidBackgroundMode.CUSTOM &&
            assetFile(context, requireNotNull(config.assetId)).let { it.isFile && it.length() > 0L }
        return LiquidBackgroundReadResult(config, decoded.issue, assetPresent)
    }

    fun importFromUri(context: Context, uri: Uri): LiquidBackgroundImportResult =
        transactions.withWriter {
            val appContext = context.applicationContext ?: context
            val directory = assetDirectory(appContext)
            if (!directory.exists() && !directory.mkdirs()) {
                return@withWriter LiquidBackgroundImportResult.Failure(
                    LiquidBackgroundImportFailure.STORAGE_FAILED
                )
            }
            val raw = File(directory, "$TEMP_PREFIX${UUID.randomUUID()}.raw")
            val encoded = File(directory, "$TEMP_PREFIX${UUID.randomUUID()}.encoded")
            var normalized: Bitmap? = null
            try {
                when (copyExternalInput(appContext, uri, raw)) {
                    CopyResult.TOO_LARGE -> return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.FILE_TOO_LARGE
                    )
                    CopyResult.FAILED -> return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.READ_FAILED
                    )
                    CopyResult.SUCCESS -> Unit
                }
                val decodeResult = decodeAndNormalize(raw)
                if (decodeResult.failure != null) {
                    return@withWriter LiquidBackgroundImportResult.Failure(decodeResult.failure)
                }
                normalized = requireNotNull(decodeResult.bitmap)
                if (!encodeNormalized(normalized, encoded)) {
                    return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                }
                if (encoded.length() !in 1..LiquidBackgroundSizingPolicy.MAX_ASSET_BYTES) {
                    return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                }
                val verifiedBounds = decodeBounds(encoded)
                    ?: return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                val assetId = UUID.randomUUID().toString()
                val destination = assetFile(appContext, assetId)
                if (!encoded.renameTo(destination)) {
                    return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.STORAGE_FAILED
                    )
                }
                val sha256 = hash(destination)
                    ?: run {
                        destination.delete()
                        return@withWriter LiquidBackgroundImportResult.Failure(
                            LiquidBackgroundImportFailure.STORAGE_FAILED
                        )
                    }
                val config = LiquidBackgroundConfig(
                    mode = LiquidBackgroundMode.CUSTOM,
                    assetId = assetId,
                    assetSha256 = sha256,
                    normalizedWidth = verifiedBounds.first,
                    normalizedHeight = verifiedBounds.second,
                    displayName = queryDisplayName(appContext, uri)
                )
                if (!writeConfig(appContext, config)) {
                    return@withWriter LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.STORAGE_FAILED
                    )
                }
                LiquidBackgroundImportResult.Success(config)
            } catch (_: SecurityException) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.READ_FAILED)
            } catch (_: OutOfMemoryError) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE)
            } catch (_: Throwable) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
            } finally {
                normalized?.recycle()
                raw.delete()
                encoded.delete()
            }
        }

    fun restoreAutomatic(context: Context): Boolean = transactions.withWriter {
        val appContext = context.applicationContext ?: context
        writeConfig(appContext, LiquidBackgroundConfig.AUTOMATIC)
    }

    /**
     * 解码自定义图片的控件预览。尺寸按控件像素，单张 ARGB_8888 不超过 2 MiB，且不放大小图框。
     * 自动模式、无效尺寸、丢失或无法读取的资产返回 null。返回位图由宿主接管：未显示的迟到结果
     * 可以 recycle，已经显示的位图应随 ImageView/Drawable 引用释放，不能提前回收。
     * 本方法不读取 View、主题或宿主设置；布局时机、任务取消和结果交付由宿主负责。
     */
    @WorkerThread
    fun decodePreview(
        context: Context,
        config: LiquidBackgroundConfig,
        viewWidth: Int,
        viewHeight: Int,
        palette: LumenPalette
    ): Bitmap? {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "Background preview must be decoded off the main thread" }
        if (config.mode != LiquidBackgroundMode.CUSTOM || viewWidth <= 0 || viewHeight <= 0) return null
        val target = LiquidBackdropSizingPolicy.resolvePreview(viewWidth, viewHeight)
        return runCatching {
            decodeBackdrop(context, config, target.width, target.height, palette.background,
                ColorUtils.calculateLuminance(palette.surface) < 0.5)
        }.getOrNull()
    }

    /** 后台线程调用；返回的 Bitmap 已是最终 backdrop 尺寸并由调用方接管。 */
    internal fun decodeBackdrop(
        context: Context,
        config: LiquidBackgroundConfig,
        targetWidth: Int,
        targetHeight: Int,
        backgroundColor: Int,
        dark: Boolean
    ): Bitmap? {
        if (config.mode != LiquidBackgroundMode.CUSTOM || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val file = assetFile(context, requireNotNull(config.assetId))
        if (!file.isFile || hash(file) != config.assetSha256) return null
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = LiquidBackgroundSizingPolicy.decodeSampleSize(
                bounds.first,
                bounds.second,
                targetWidth,
                targetHeight
            )
        }
        val source = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return try {
            val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            canvas.drawColor(backgroundColor)
            val transform = LiquidBackgroundSizingPolicy.centerCrop(
                source.width,
                source.height,
                targetWidth,
                targetHeight
            )
            val matrix = Matrix().apply {
                setScale(transform.scale, transform.scale)
                postTranslate(transform.translateX, transform.translateY)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(source, matrix, paint)
            paint.color = ColorUtils.setAlphaComponent(
                if (dark) Color.BLACK else backgroundColor,
                if (dark) 0x28 else 0x20
            )
            canvas.drawRect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), paint)
            target.prepareToDraw()
            target
        } finally {
            source.recycle()
        }
    }

    private data class NormalizeResult(
        val bitmap: Bitmap?,
        val failure: LiquidBackgroundImportFailure?
    )

    private enum class CopyResult { SUCCESS, TOO_LARGE, FAILED }

    private fun copyExternalInput(context: Context, uri: Uri, destination: File): CopyResult {
        val declaredLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredLength != null && declaredLength > LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES) {
            return CopyResult.TOO_LARGE
        }
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return CopyResult.FAILED
        return runCatching {
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES) {
                            return@runCatching CopyResult.TOO_LARGE
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (destination.length() <= 0L) CopyResult.FAILED else CopyResult.SUCCESS
        }.getOrDefault(CopyResult.FAILED)
    }

    private fun decodeAndNormalize(file: File): NormalizeResult {
        val bounds = decodeBounds(file)
            ?: return NormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
        if (!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(bounds.first, bounds.second)) {
            return NormalizeResult(null, LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE)
        }
        val target = LiquidBackgroundSizingPolicy.resolveNormalizedSize(bounds.first, bounds.second)
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            LiquidImageDecoderApi28.decode(file)
        } else {
            decodeApi27(file, target)
        } ?: return NormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
        // API 27 的 BitmapFactory 不自动应用 EXIF；旋转 90/270 度后必须按纠正后的宽高重新
        // 计算规范化尺寸，不能再拉伸回未旋转的 bounds 比例。
        val orientedTarget = LiquidBackgroundSizingPolicy.resolveNormalizedSize(
            decoded.width,
            decoded.height
        )
        val scaled = if (decoded.width == orientedTarget.width &&
            decoded.height == orientedTarget.height
        ) {
            decoded
        } else {
            Bitmap.createScaledBitmap(
                decoded,
                orientedTarget.width,
                orientedTarget.height,
                true
            ).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        return NormalizeResult(scaled, null)
    }

    @Suppress("DEPRECATION")
    private fun decodeApi27(file: File, target: LiquidNormalizedImageSize): Bitmap? {
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = LiquidBackgroundSizingPolicy.decodeSampleSize(
                bounds.first,
                bounds.second,
                target.width,
                target.height
            )
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return decoded
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }.getOrElse {
            decoded.recycle()
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun encodeNormalized(bitmap: Bitmap, destination: File): Boolean = runCatching {
        FileOutputStream(destination).use { output ->
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else {
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, JPEG_QUALITY, output)) return@runCatching false
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun decodeBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    private fun hash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?.map { character ->
            if (character.isISOControl() || character == '/' || character == '\\') '_' else character
        }
        ?.joinToString("")
        ?.take(LiquidBackgroundConfigCodec.MAX_DISPLAY_NAME)

    private fun writeConfig(context: Context, config: LiquidBackgroundConfig): Boolean = transactions.withWriter {
        val preferences = runCatching { preferenceAccess(context) }.getOrElse { return@withWriter false }
        val result = transactions.replace(preferences, config)
        result.confirmedConfig?.let { cleanupUnreferencedAssets(context, it.assetId) }
        result.committed
    }

    @SuppressLint("UseKtx")
    private fun preferenceAccess(context: Context): LiquidBackgroundPreferenceAccess {
        val preferences = preferences(context)
        return object : LiquidBackgroundPreferenceAccess {
            override fun snapshot(): Map<String, *> = preferences.all.toMap()

            override fun stage(config: LiquidBackgroundConfig) {
                val editor = preferences.edit().clear()
                LiquidBackgroundConfigCodec.encode(config).forEach { (key, value) ->
                    when (value) {
                        is Int -> editor.putInt(key, value)
                        is String -> editor.putString(key, value)
                        else -> error("Unsupported liquid background preference type")
                    }
                }
                // apply 的内存发布与 read/repair 共用短锁；磁盘写入异步，不把主线程阻塞
                // 在 URI 读取、图片处理或 commit 的磁盘等待上。
                editor.apply()
            }

            // 空编辑器不再改写目标值；同步 commit 确认 apply 发布的 memory generation，
            // 并等待此前的写入。false（包括 await 被中断）不能证明磁盘仍是旧值。
            override fun persist(): Boolean = preferences.edit().commit()
        }
    }

    private fun cleanupUnreferencedAssets(context: Context, keepAssetId: String?) {
        assetDirectory(context).listFiles()?.forEach { file ->
            val keepName = keepAssetId?.let { "$ASSET_PREFIX$it$ASSET_SUFFIX" }
            if (file.name != keepName &&
                (file.name.startsWith(ASSET_PREFIX) || file.name.startsWith(TEMP_PREFIX))
            ) {
                file.delete()
            }
        }
    }

    private fun preferences(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun assetDirectory(context: Context) =
        File((context.applicationContext ?: context).filesDir, ASSET_DIRECTORY)

    private fun assetFile(context: Context, assetId: String) =
        File(assetDirectory(context), "$ASSET_PREFIX$assetId$ASSET_SUFFIX")
}

/** SharedPreferences 之外的事务边界，可用内存先发布、磁盘后确认的替身验证故障与交错。 */
internal interface LiquidBackgroundPreferenceAccess {
    fun snapshot(): Map<String, *>
    fun stage(config: LiquidBackgroundConfig)
    fun persist(): Boolean
}

internal data class LiquidBackgroundWriteResult(
    val committed: Boolean,
    // 仅持久化确认且回读一致时提供；null 意味着必须保留全部可能被引用的资产。
    val confirmedConfig: LiquidBackgroundConfig?
)

internal class LiquidBackgroundTransactions {
    private val writerLock = Any()
    private val stateLock = Any()

    // URI/位图处理、配置替换和清理串行，避免 restore/retry 删除另一次导入的临时资产。
    // read 不获取此锁。
    fun <T> withWriter(action: () -> T): T = synchronized(writerLock, action)

    fun read(preferences: LiquidBackgroundPreferenceAccess): LiquidBackgroundConfigDecodeResult =
        synchronized(stateLock) {
            val decoded = runCatching {
                LiquidBackgroundConfigCodec.decode(preferences.snapshot())
            }.getOrElse {
                // 无法取得快照时不能据此覆盖仍可能有效的配置。
                return@synchronized LiquidBackgroundConfigDecodeResult(
                    LiquidBackgroundConfig.AUTOMATIC,
                    LiquidBackgroundConfigIssue.TYPE_MISMATCH,
                    needsRepair = true
                )
            }
            if (decoded.needsRepair) {
                runCatching { preferences.stage(LiquidBackgroundConfig.AUTOMATIC) }
            }
            decoded
        }

    fun replace(
        preferences: LiquidBackgroundPreferenceAccess,
        config: LiquidBackgroundConfig
    ): LiquidBackgroundWriteResult = withWriter {
        val previous: LiquidBackgroundConfig
        val staged: Boolean
        synchronized(stateLock) {
            previous = runCatching {
                LiquidBackgroundConfigCodec.decode(preferences.snapshot()).config
            }.getOrElse {
                return@withWriter LiquidBackgroundWriteResult(false, null)
            }
            staged = runCatching { preferences.stage(config) }.isSuccess
        }
        if (staged && confirm(preferences, config)) {
            return@withWriter LiquidBackgroundWriteResult(true, config)
        }

        // commit false 不回滚内存，也可能有尚未完成的磁盘写入。先恢复旧的 canonical
        // 配置，再锁外确认其持久化；旧值本来损坏时，它的合法回退就是 AUTOMATIC。
        val restored = synchronized(stateLock) {
            runCatching { preferences.stage(previous) }.isSuccess
        }
        val confirmed = if (restored && confirm(preferences, previous)) previous else null
        LiquidBackgroundWriteResult(false, confirmed)
    }

    private fun confirm(
        preferences: LiquidBackgroundPreferenceAccess,
        config: LiquidBackgroundConfig
    ): Boolean {
        if (!runCatching { preferences.persist() }.getOrDefault(false)) return false
        return synchronized(stateLock) {
            runCatching {
                val readBack = LiquidBackgroundConfigCodec.decode(preferences.snapshot())
                !readBack.needsRepair && readBack.config == config
            }.getOrDefault(false)
        }
    }
}
