package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.ModernMaterialPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 透镜"输入未变即沿用已发布纹理"（2026-10-25）：内容层任何一处重绘都会让 5 个表面整批重采样，
 * 而栏下像素常常根本没变。回放与已发布输入逐像素相同时，输出必然相同，跳过后续步骤不改变任何像素。
 */
class LiveLensMemoContractTest {
    private val sampler = SourceContract.read("material/LiveBackdropSampler.kt")

    /** 与 LiveBackdropSampler.prepare 默认（无 profile）分支相同的采样图尺寸。 */
    private fun sampleBytes(widthPx: Int, heightPx: Int, density: Float): Long {
        val marginPx = LensRefractionPolicy.marginPx(density)
        val scale = LensRefractionPolicy.sampleScale(widthPx + 2 * marginPx, heightPx + 2 * marginPx)
        val margin = (marginPx + scale - 1) / scale
        val outWidth = (widthPx + scale - 1) / scale
        val outHeight = (heightPx + scale - 1) / scale
        return (outWidth + 2L * margin) * (outHeight + 2L * margin) * 4L
    }

    /** 真实工作集：顶栏、底栏胶囊与三个圆按钮同屏，常见密度下比对位图都要装进独立预算。 */
    @Test fun theMemoBudgetCoversTheFiveSurfaceWorkingSet() {
        for (density in listOf(2.625f, 3f, 3.5f, 4f)) {
            val capsule = sampleBytes((360 * density).roundToInt(), (54 * density).roundToInt(), density)
            val button = sampleBytes((56 * density).roundToInt(), (56 * density).roundToInt(), density)
            val workingSet = 2 * capsule + 3 * button
            assertTrue("density=$density workingSet=$workingSet", workingSet <= ModernMaterialPolicy.LIVE_SAMPLE_MEMO_BYTES)
        }
        assertTrue(ModernMaterialPolicy.LIVE_SAMPLE_MEMO_BYTES <= 1024L * 1024)
    }

    @Test fun theComparisonRunsRightAfterReplayAndBeforeAnyPixelWork() {
        val process = sampler.after("fun process(job: LensJob)").before("fun close()")
        val check = process.after("drawPicture(job.picture) }").before("job.sample.getPixels(")
        assertTrue(check.contains("job.sample.sameAs(reference)"))
        assertTrue(check.contains("job.unchanged = true"))
    }

    @Test fun unchangedJobsSkipUploadOnlyWhileThePublishedTextureIsStillValid() {
        val done = sampler.after("private fun onBatchDone(").before("private fun rememberPublishedInput(")
        val skip = done.before("texture.setPixels(job.out")
        assertTrue(skip.contains("if (job.unchanged && entry.valid) { entry.publishedBlurScale = job.blurScale; continue }"))
        assertTrue(done.after("texture.setPixels(job.out").contains("rememberPublishedInput(entry, job)"))
        // 比对基准只来自同参数的已发布结果。
        val job = sampler.after("private fun job(").before("private fun onBatchDone(")
        assertTrue(job.contains("entry.valid && entry.publishedWith(entry.scale, entry.margin, blurRadius, weakRadius, strongRadius)"))
    }

    /** 比对位图走独立预算，不进必需缓冲的准入计算：不得因为它挤掉原本放得下的表面。 */
    @Test fun memoBitmapsNeverTakePartInBufferAdmission() {
        val prepare = sampler.after("private fun prepare(").before("private fun capture(")
        val admission = prepare.before("entry.allocatedBytes = bytes")
        assertFalse(admission.contains("memoBytes"))
        assertTrue(prepare.contains("<= ModernMaterialPolicy.LIVE_SAMPLE_MEMO_BYTES"))
        val release = sampler.after("fun release() {").before("/** Weak identity keys")
        assertTrue(release.contains("reference = null"))
        assertTrue(release.contains("hasPublishedInput = false"))
    }
}
