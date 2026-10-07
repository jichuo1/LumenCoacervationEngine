package com.lumen.coacervation.engine.material

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfacePolicy
import com.lumen.coacervation.engine.host.LumenSurfaceFadeDirection
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 悬浮表面的实时"玻璃透镜"底图：把同一窗口里位于表面**下方**的内容层（由宿主 [bindSource]
 * 指定，通常是滚动页容器）在每次 pre-draw 时按低分辨率软件绘制到一小块位图，经预乘模糊与
 * [LensRefractionPolicy.remap] 透镜重采样后，作为 BitmapShader 供表面一笔画出。
 *
 * 只采样内容层自身（表面是它的兄弟，不在其中），因此没有反馈回路；采样区域被软件 Canvas
 * 的位图边界裁到表面外沿之内，内容层里落在外面的子 View 会被 quickReject 掉，开销与表面
 * 面积成正比而与页面复杂度无关。任何一次采样抛异常即永久退回静态磨砂（不再尝试），
 * 表面外观退化为原先的样子而不是崩溃。
 *
 * **UI 线程只录制，所有逐像素工作都在后台线程**（2026-09-23 真机打点）：一次采样要处理 5 个
 * 表面（顶栏胶囊、底栏胶囊、三个圆按钮），模糊 360–900µs、透镜重映射 250–1060µs，合计
 * **7–10.7ms 全压在 UI 线程**，就是手风琴动画帧上那根尖刺。挪走这两步后仍剩底栏 3–4.4ms：
 * 那是内容层**软件光栅化的填充率**——底栏下方叠着 5 层几乎全覆盖采样区的半透明卡片表面，
 * 与动画无关，展开态静止滚动时同样要付。所以主线程只把内容层的绘制指令录进 [Picture]，
 * 回放（画像素）、取像素、模糊、重映射整批交给 [worker]，回主线程只剩一次 `setPixels`。
 * 步骤与旧实现逐一对应，只是晚一两帧落到纹理上。
 *
 * 因此显式动画期**不再冻结采样**：旧方案为躲尖刺在手风琴动画期整段不采、静默 96ms 后补采，
 * 用户看到的是"顶栏/底栏透出的背景等动画播完才瞬间跳变"。
 */
@MainThread
internal class LiveBackdropSampler(
    private val density: Float,
    /** 与渲染器共用：采集期间开启祖先备忘，录制里的静态磨砂映射与本类的表面矩阵共享祖先链。 */
    private val samplingMatrices: ViewSamplingMatrix,
    private val byteBudget: Int = Int.MAX_VALUE,
    private val recordContent: ((Canvas) -> Unit)? = null,
    private val sharedBudget: LumenSampleBudget? = null
) {
    private class Entry(val budget: LumenSampleBudget?) {
        var profile: LumenSurfaceSampling? = null
        var allocatedBytes = 0L
        var scale = 0
        var margin = 0
        var sampleWidth = 0
        var sampleHeight = 0
        var outWidth = 0
        var outHeight = 0
        /** 后台回放的目标位图：只在作业里被写，所以释放时**不** recycle，交给 GC（见 [release]）。 */
        var sample: Bitmap? = null
        var sampleCanvas: Canvas? = null
        var pixels: IntArray? = null
        /** 模糊的第二块缓冲：逐帧复用，避免每次采样都分配两份采样图大小的数组。 */
        var scratch: IntArray? = null
        var out: IntArray? = null
        var texture: Bitmap? = null
        var shader: BitmapShader? = null
        var valid = false
        /** 缓冲每重建/释放一次递增；后台结果按它认领，旧缓冲上算出的结果直接丢弃。 */
        var generation = 0

        // 以下几项主线程在采集时写、后台回放时读；单飞保证读写不交叠。
        /** 内容局部坐标 → 目标局部坐标。 */
        val sourceToTarget = Matrix()
        /** 多成员组的回放变换；单成员组录制时已带完整变换，回放不再变换（见 [LensJob.replay]）。 */
        val replay = Matrix()
        /** 本表面采样区（含外沿）在内容坐标下的包围盒，用来把重叠的表面合并成一次录制。 */
        val contentBounds = RectF()
        var contentBoundsValid = false

        fun release() {
            generation++
            valid = false
            shader = null
            texture = null // A previous hardware display list may still hold this bitmap.
            // 内存压力下的 releaseAll 可能正赶上后台在往 sample 里回放：recycle 会立刻释放
            // 原生像素，正在写的那一趟就是释放后使用。只断引用，在飞作业自己持有它直到结束。
            sample = null
            sampleCanvas = null
            pixels = null
            scratch = null
            out = null
            sampleWidth = 0; sampleHeight = 0; outWidth = 0; outHeight = 0
            budget?.release(allocatedBytes)
            allocatedBytes = 0L
        }
    }

    /**
     * 一个表面的一次后台透镜作业。主线程录好绘制指令后交出；作业在飞期间主线程不碰
     * 这里的录制、位图与缓冲（[inFlight] 单飞保证），后台也不碰任何 View 或纹理。
     */
    private class LensJob(
        val view: View,
        val entry: Entry,
        val generation: Int,
        val picture: Picture,
        /** null：录制时已带完整变换，原样回放；否则先套上这个变换再回放组录制。 */
        val replay: Matrix?,
        val sample: Bitmap,
        val sampleCanvas: Canvas,
        val pixels: IntArray,
        val scratch: IntArray,
        val out: IntArray,
        val sampleWidth: Int,
        val sampleHeight: Int,
        val margin: Int,
        val outWidth: Int,
        val outHeight: Int,
        val blurRadius: Int,
        val profile: LumenSurfaceSampling?
    )

    private var source: View? = null
    private val entries = WeakHashMap<View, Entry>()
    private val roots = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    /** 内容层的全局矩阵，每批只取一次，所有表面共用。 */
    private val contentGlobal = FloatArray(9)
    private val inverseScratch = Matrix()
    /** 组录制的复用池：第 k 组用第 k 个。单飞保证后台回放期间主线程不会重录。 */
    private val pictures = ArrayList<Picture>()
    private val shaderMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var dirty = true
    private var suspended = false
    private var failed = false
    private var closed = false

    /** 上一次真正采样的时刻；节流与收尾补采都按它判定。 */
    private var lastSampleNanos = 0L
    private var trailingPosted = false
    private var trailingHost: View? = null
    private val trailingSample = Runnable {
        trailingPosted = false
        if (!isActive) return@Runnable
        dirty = true
        entries.keys.forEach(View::invalidate)
    }

    /** 同一时刻至多一批作业在后台；在飞期间的新位移只记 dirty，批次回来后续采。 */
    private var inFlight = false

    /**
     * 本次内容层"脏"是我们自己造成的：批次回来时失效了位于内容层**内部**的宿主（如「管理常用」
     * 按钮），整条祖先链连同内容层都被标脏，下一帧 pre-draw 又据此重采样、再失效宿主——
     * 每帧一次的自激循环。2026-09-24 实测：柔光皮肤静止时持续 ~62fps 重绘（5 秒 310 帧），
     * 调用栈全部来自 onBatchDone → 宿主 invalidate。已提交版本同样存在。
     * 置位后下一次 pre-draw 若没有显式内容变化（[dirty]）就跳过；真实位移走 [invalidate]
     * 显式置 dirty，持续动画会连续多帧弄脏内容层，都不受影响。
     */
    private var ignoreSelfInflictedDirty = false
    /** 释放/关闭时递增，让已经在飞的批次回来时自知过期。 */
    private var batchToken = 0
    private var workerStarted = false
    private val worker: ExecutorService by lazy {
        workerStarted = true
        Executors.newSingleThreadExecutor { task -> Thread(task, "Lumen-LiveLens").apply { isDaemon = true } }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    val hasFailed: Boolean get() = failed
    var budgetRejected: Boolean = false
        private set
    var recordings: Long = 0
        private set
    val allocatedBytes: Long get() = entries.values.sumOf { it.allocatedBytes }

    private fun intervalMs(): Long {
        var value = Long.MAX_VALUE
        entries.values.forEach { value = minOf(value, it.profile?.minIntervalMs ?: ModernMaterialPolicy.LIVE_SAMPLE_MIN_INTERVAL_MS) }
        return if (value == Long.MAX_VALUE) ModernMaterialPolicy.LIVE_SAMPLE_MIN_INTERVAL_MS else value
    }

    val isActive: Boolean get() = !closed && !failed && !suspended && source != null

    fun bindSource(view: View) {
        if (closed) return
        source = view
        dirty = true
    }

    /** 表面 draw 时登记；同窗口首个表面顺带装上 pre-draw 采样钩子。 */
    fun register(view: View, profile: LumenSurfaceSampling? = null) {
        if (!isActive) return
        if (!entries.containsKey(view)) {
            entries[view] = Entry(sharedBudget).apply { this.profile = profile }
            dirty = true
        }
        val entry = entries[view]
        if (entry != null && entry.profile != profile) {
            entry.release()
            entry.profile = profile
            dirty = true
        }
        val root = view.rootView ?: return
        if (!roots.containsKey(root)) {
            val listener = ViewTreeObserver.OnPreDrawListener { onPreDraw(); true }
            root.viewTreeObserver.addOnPreDrawListener(listener)
            roots[root] = listener
        }
    }

    /**
     * 表面改走内容节点玻璃（C 期，`FrostedChromeGlassApi31`）：摘掉它的条目，后续批次不再为它录制。
     *
     * 只断引用、不 recycle 纹理：宿主上一份 display list 可能还引用着它，交给 GC。
     * 在飞批次回来时按 generation 认领，已摘掉的条目结果直接丢弃。
     */
    fun unregister(view: View) {
        val entry = entries.remove(view) ?: return
        entry.release()
    }

    /**
     * 内容位移（滚动、手风琴、翻页等一切来源）：下一帧 pre-draw 重采样，节奏由
     * `LIVE_SAMPLE_MIN_INTERVAL_MS` 与后台单飞共同控制。
     */
    fun invalidate() {
        dirty = true
    }

    private fun onPreDraw() {
        // 所有悬浮表面都交给 GPU 后，不再为一个空批次计算内容矩阵或安排收尾采样。
        if (!isActive || entries.isEmpty()) return
        val content = source ?: return
        if (!content.isAttachedToWindow || content.width <= 0 || content.height <= 0) return
        if (!dirty && !content.isDirty) return
        if (ignoreSelfInflictedDirty) {
            ignoreSelfInflictedDirty = false
            if (!dirty) return
        }
        // 上一批还在后台：保持 dirty，批次回来时会拉起下一趟 traversal 续采。
        if (inFlight) return
        // 节流：连续运动时不必每个 vsync 都重采样一遍内容层。落在间隔内就保持 dirty、
        // 投递一次收尾补采——**必须**补，否则手指停下的最后一帧被跳过就会永久停在
        // 滞后的映射上。
        val now = System.nanoTime()
        val elapsedMs = (now - lastSampleNanos) / NANOS_PER_MILLISECOND
        if (lastSampleNanos != 0L && elapsedMs < intervalMs()) {
            scheduleTrailingSample(intervalMs() - elapsedMs)
            return
        }
        lastSampleNanos = now
        dirty = false
        // 采集期间视图树静止：开启祖先备忘，5 个表面与录制里的每张卡片共享同一条祖先链。
        val jobs = samplingMatrices.withAncestorMemo { collectJobs(content) } ?: run {
            fail()
            return
        }
        if (jobs.isEmpty()) return
        inFlight = true
        val token = batchToken
        worker.execute {
            val ok = runCatching { jobs.forEach(::process) }.isSuccess
            mainHandler.post { onBatchDone(token, jobs, ok) }
        }
    }

    /** 一批采集的主线程部分：逐表面准备几何，再按组录制。返回 null 表示失败（调用方永久退回静态磨砂）。 */
    private fun collectJobs(content: View): List<LensJob>? {
        // 5 个表面共用同一个内容层：它到屏幕的矩阵每批只算一次（每次都是十几层的 JNI 链）。
        val contentGlobalValid = samplingMatrices.localToScreen(content, contentGlobal)
        val plans = ArrayList<Entry>(entries.size)
        val planViews = ArrayList<View>(entries.size)
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (!view.isAttachedToWindow) { entry.release(); iterator.remove(); continue }
            if (!view.isShown || view.rootView !== content.rootView) { entry.valid = false; continue }
            val prepared = runCatching { prepare(view, entry, contentGlobalValid) }
            if (prepared.isFailure) return null
            if (prepared.getOrDefault(false)) {
                plans += entry
                planViews += view
            }
        }
        if (plans.isEmpty()) return emptyList()
        return runCatching { capture(content, plans, planViews) }.getOrNull()
    }

    /**
     * 投递一次收尾补采：节流跳过的那一帧必须有人补上，否则滚动停止时纹理停在滞后态。
     * 只投一次（[trailingPosted] 自守），到点把表面设脏拉起一次 traversal 即可。
     */
    private fun scheduleTrailingSample(delayMs: Long) {
        if (trailingPosted) return
        val host = entries.keys.firstOrNull { it.isAttachedToWindow } ?: return
        trailingPosted = true
        trailingHost = host
        host.postOnAnimationDelayed(trailingSample, delayMs.coerceAtLeast(1L))
    }

    /** 主线程：算本表面的采样几何并备好缓冲。返回 false 表示本批跳过它。 */
    private fun prepare(view: View, entry: Entry, contentGlobalValid: Boolean): Boolean {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) { entry.valid = false; return false }
        if (!contentGlobalValid ||
            !samplingMatrices.sourceToTarget(contentGlobal, view, entry.sourceToTarget)
        ) {
            entry.valid = false
            return false
        }
        val profile = entry.profile
        val marginPx = if (profile == null) LensRefractionPolicy.marginPx(density) else
            kotlin.math.ceil(maxOf(if (profile.refractionEnabled) LensRefractionPolicy.MARGIN_DP * profile.refractionStrength else 0f, if (profile.blurEnabled) profile.blurRadiusDp * 1.8f else 0f) * density).toInt().coerceAtLeast(1)
        val scale = if (profile == null) LensRefractionPolicy.sampleScale(width + 2 * marginPx, height + 2 * marginPx) else
            LumenSurfacePolicy.softwareDivisor(width, height, profile.softwareScale, profile.maxSoftwarePixels, marginPx)
        val margin = ((marginPx.toLong() + scale - 1) / scale).toInt().coerceAtLeast(1)
        val outWidth = ((width.toLong() + scale - 1) / scale).toInt().coerceAtLeast(1)
        val outHeight = ((height.toLong() + scale - 1) / scale).toInt().coerceAtLeast(1)
        val sampleWidth = outWidth + 2 * margin
        val sampleHeight = outHeight + 2 * margin
        if (entry.sampleWidth != sampleWidth || entry.sampleHeight != sampleHeight ||
            entry.outWidth != outWidth || entry.outHeight != outHeight) {
            val bytes = (sampleWidth.toLong() * sampleHeight * 12L + outWidth.toLong() * outHeight * 8L)
            if (allocatedBytes - entry.allocatedBytes + bytes > byteBudget) {
                budgetRejected = true
                entry.release()
                return false
            }
            budgetRejected = false
            entry.release()
            if (sharedBudget?.reserve(bytes) == false) { budgetRejected = true; return false }
            entry.allocatedBytes = bytes
            entry.sample = createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            entry.sampleCanvas = Canvas(entry.sample!!)
            entry.pixels = IntArray(sampleWidth * sampleHeight)
            entry.scratch = IntArray(sampleWidth * sampleHeight)
            entry.out = IntArray(outWidth * outHeight)
            entry.texture = createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            entry.shader = BitmapShader(entry.texture!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            entry.sampleWidth = sampleWidth; entry.sampleHeight = sampleHeight
            entry.outWidth = outWidth; entry.outHeight = outHeight
        }
        entry.scale = scale
        entry.margin = margin
        // 采样区（目标局部坐标，含外沿）反变换回内容坐标，得到它在内容层上的包围盒。
        val reach = (margin * scale).toFloat()
        entry.contentBounds.set(-reach, -reach, outWidth * scale + reach, outHeight * scale + reach)
        entry.contentBoundsValid = entry.sourceToTarget.invert(inverseScratch)
        if (entry.contentBoundsValid) inverseScratch.mapRect(entry.contentBounds)
        return entry.sample != null && entry.sampleCanvas != null && entry.pixels != null &&
            entry.scratch != null && entry.out != null
    }

    /**
     * 主线程：把内容层的绘制指令录进 [Picture]。View 只能在 UI 线程遍历，只有这一步必须留下。
     *
     * 只**录制**、不光栅化：内容层的软件光栅化是填充率开销，与叠在采样区里的半透明卡片层数成正比
     * ——底栏下方叠着页面卡/进阶卡/分类卡共 5 层，实测 3–4.4ms，而录制只是遍历一遍视图树。
     * 回放（真正画像素）交给后台，见 [process]。录制画布与位图画布同为软件画布
     * （isHardwareAccelerated=false），View 的绘制分支不变。
     *
     * **采样区重叠的表面合并成一次录制**：顶栏胶囊和它上面的三个圆按钮落在同一条带里，原来是
     * 4 次完整的视图树遍历（2026-09-23 打点：顶栏 ~160µs、每个圆按钮 ~90µs）。
     * - 单成员组：录制时直接带上完整变换，回放不再变换——与合并前逐字节相同。
     * - 多成员组：按组包围盒在内容坐标下录一次，每个表面回放时各自套上 缩放·外沿平移·源到目标，
     *   再平移回组原点。录制区是每个成员采样区的超集（外扩 [CLUSTER_PAD_PX]），不会少录任何东西。
     */
    private fun capture(content: View, plans: List<Entry>, views: List<View>): List<LensJob> {
        val groups = LensCaptureGrouping.group(plans.size) { a, b ->
            plans[a].contentBoundsValid && plans[b].contentBoundsValid &&
                RectF.intersects(plans[a].contentBounds, plans[b].contentBounds)
        }
        val jobs = ArrayList<LensJob>(plans.size)
        for ((index, members) in groups.withIndex()) {
            while (pictures.size <= index) pictures += Picture()
            val picture = pictures[index]
            if (members.size == 1) {
                val entry = plans[members[0]]
                val scale = entry.scale
                val margin = entry.margin
                val recording = picture.beginRecording(entry.sampleWidth, entry.sampleHeight)
                try {
                    // 位图像素 = (目标局部坐标 + 外沿) / scale；目标局部坐标 = M · 内容局部坐标。
                    recording.scale(1f / scale, 1f / scale)
                    recording.translate((margin * scale).toFloat(), (margin * scale).toFloat())
                    recording.concat(entry.sourceToTarget)
                    if (recordContent == null) content.draw(recording) else recordContent.invoke(recording)
                    recordings++
                } finally {
                    picture.endRecording()
                }
                jobs += job(views[members[0]], entry, picture, replay = null)
                continue
            }
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (member in members) {
                val bounds = plans[member].contentBounds
                left = minOf(left, bounds.left); top = minOf(top, bounds.top)
                right = maxOf(right, bounds.right); bottom = maxOf(bottom, bounds.bottom)
            }
            val originX = floor(left).toInt() - CLUSTER_PAD_PX
            val originY = floor(top).toInt() - CLUSTER_PAD_PX
            val recordWidth = (ceil(right).toInt() + CLUSTER_PAD_PX - originX).coerceAtLeast(1)
            val recordHeight = (ceil(bottom).toInt() + CLUSTER_PAD_PX - originY).coerceAtLeast(1)
            val recording = picture.beginRecording(recordWidth, recordHeight)
            try {
                recording.translate(-originX.toFloat(), -originY.toFloat())
                if (recordContent == null) content.draw(recording) else recordContent.invoke(recording)
                recordings++
            } finally {
                picture.endRecording()
            }
            for (member in members) {
                val entry = plans[member]
                val reach = (entry.margin * entry.scale).toFloat()
                // 与单成员录制相同的变换次序（scale → translate → concat），最后平移回组原点。
                entry.replay.setScale(1f / entry.scale, 1f / entry.scale)
                entry.replay.preTranslate(reach, reach)
                entry.replay.preConcat(entry.sourceToTarget)
                entry.replay.preTranslate(originX.toFloat(), originY.toFloat())
                jobs += job(views[member], entry, picture, replay = entry.replay)
            }
        }
        return jobs
    }

    private fun job(view: View, entry: Entry, picture: Picture, replay: Matrix?): LensJob = LensJob(
        view = view,
        entry = entry,
        generation = entry.generation,
        picture = picture,
        replay = replay,
        sample = entry.sample!!,
        sampleCanvas = entry.sampleCanvas!!,
        pixels = entry.pixels!!,
        scratch = entry.scratch!!,
        out = entry.out!!,
        sampleWidth = entry.sampleWidth,
        sampleHeight = entry.sampleHeight,
        margin = entry.margin,
        outWidth = entry.outWidth,
        outHeight = entry.outHeight,
        blurRadius = entry.profile?.let { (if (it.blurEnabled) it.blurRadiusDp * density / entry.scale else 0f).toInt().coerceIn(0, 32) }
            ?: LensRefractionPolicy.blurRadius(entry.scale, density),
        profile = entry.profile
    )

    /** 批次回到主线程：把后台结果写进纹理。过期的批次/缓冲一律丢弃。 */
    private fun onBatchDone(token: Int, jobs: List<LensJob>, ok: Boolean) {
        inFlight = false
        if (token != batchToken || !isActive) return
        if (!ok) {
            fail()
            return
        }
        for (job in jobs) {
            val entry = job.entry
            if (entry.generation != job.generation) continue
            val texture = entry.texture ?: continue
            if (texture.isRecycled) continue
            texture.setPixels(job.out, 0, job.outWidth, 0, 0, job.outWidth, job.outHeight)
            entry.valid = true
            job.view.invalidate()
            if (!dirty && isInside(job.view, source)) ignoreSelfInflictedDirty = true
        }
        // 批次在飞期间又有位移：上面的 invalidate 通常已经拉起下一趟 pre-draw；这里兜底，
        // 保证即使本批结果全被丢弃，停下来的那一帧也一定会被补采。
        if (dirty) {
            val elapsedMs = (System.nanoTime() - lastSampleNanos) / NANOS_PER_MILLISECOND
            scheduleTrailingSample(intervalMs() - elapsedMs)
        }
    }

    private fun isInside(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun fail() {
        failed = true
        releaseAll()
        entries.keys.forEach(View::invalidate)
    }

    /** 已有有效纹理才画；否则返回 false，表面退回静态磨砂。 */
    fun draw(canvas: Canvas, bounds: RectF, radius: Float, view: View, alpha: Int): Boolean {
        if (!isActive) return false
        val entry = entries[view] ?: return false
        if (!entry.valid) return false
        val shader = entry.shader ?: return false
        shaderMatrix.setScale(bounds.width() / entry.outWidth, bounds.height() / entry.outHeight)
        shaderMatrix.postTranslate(bounds.left, bounds.top)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawRoundRect(bounds, radius, radius, paint)
        return true
    }

    /** 内存压力/后台：丢掉全部位图，恢复后首帧重采样。在飞的批次回来时按令牌作废。 */
    fun releaseAll() {
        batchToken++
        entries.values.forEach(Entry::release)
        // 在飞作业各自持有它引用的录制，清池不影响它们。
        pictures.clear()
        paint.shader = null
        dirty = true
    }

    fun suspend() {
        suspended = true
        releaseAll()
    }

    fun resume() {
        if (closed) return
        suspended = false
        dirty = true
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * 组录制区相对成员采样区包围盒的外扩（内容像素）。包围盒由浮点反变换得到，外扩两像素
         * 保证录制时的 quickReject 不会因舍入剔掉某个成员采样区边缘的 View。
         */
        const val CLUSTER_PAD_PX = 2

        /**
         * 后台部分：回放录制、取像素、模糊与透镜重映射。只碰作业自带的对象，不碰实例状态、
         * View 或纹理。步骤与旧的主线程实现逐一对应（清空 → 按同一变换画内容 → 取像素 →
         * 预乘 → 模糊 → 重映射 → 提亮 → 解预乘），只是画内容那一步从直接绘制换成了回放录制。
         */
        @WorkerThread
        fun process(job: LensJob) {
            job.sample.eraseColor(0)
            val replay = job.replay
            if (replay == null) {
                job.sampleCanvas.drawPicture(job.picture)
            } else {
                job.sampleCanvas.withMatrix(replay) { drawPicture(job.picture) }
            }
            job.sample.getPixels(job.pixels, 0, job.sampleWidth, 0, 0, job.sampleWidth, job.sampleHeight)
            LensRefractionPolicy.premultiply(job.pixels)
            val blurred = if (job.blurRadius == 0) job.pixels else ModernBackdropBlur.blurInto(
                job.pixels, job.scratch, job.sampleWidth, job.sampleHeight, job.blurRadius
            )
            LensRefractionPolicy.remap(
                blurred, job.sampleWidth, job.sampleHeight, job.margin,
                job.out, job.outWidth, job.outHeight,
                if (job.profile?.refractionEnabled == false) 0f else job.profile?.refractionStrength ?: 1f
            )
            LensRefractionPolicy.illuminate(job.out)
            LensRefractionPolicy.unpremultiply(job.out)
            val profile = job.profile
            if (profile?.fadeEnabled == true) {
                val reverse = profile.fadeDirection == LumenSurfaceFadeDirection.BOTTOM_TO_TOP
                for (y in 0 until job.outHeight) {
                    val weight = LumenSurfacePolicy.fade((y + 0.5f) / job.outHeight, profile.fadeHold, profile.fadeEnd, reverse)
                    for (x in 0 until job.outWidth) {
                        val i = y * job.outWidth + x
                        val c = job.out[i]
                        job.out[i] = (c and 0x00ffffff) or (((c ushr 24) * weight).toInt() shl 24)
                    }
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        trailingHost?.removeCallbacks(trailingSample)
        trailingHost = null
        trailingPosted = false
        releaseAll()
        if (workerStarted) worker.shutdownNow()
        roots.forEach { (root, listener) ->
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        roots.clear()
        entries.clear()
        source = null
    }
}
