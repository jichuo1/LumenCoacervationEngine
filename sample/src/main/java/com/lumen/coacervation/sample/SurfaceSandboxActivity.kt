@file:Suppress("SetTextI18n")

package com.lumen.coacervation.sample

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Interactive, non-persistent local-surface controls; the ordinary Activity root stays untouched. */
class SurfaceSandboxActivity : Activity() {
    internal lateinit var session: LumenSurfaceSession
    internal lateinit var binding: LumenSurfaceBinding
    internal lateinit var content: View
    internal lateinit var glass: TextView
    internal lateinit var preview: FrameLayout
    private var surface = LumenSurfaceOptions()
    private var limits = LumenSurfaceSessionOptions()
    private var dark = false
    private lateinit var status: TextView
    private var animation: ValueAnimator? = null
    private val dialogs = ArrayList<Dialog>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(238,240,244)) }
        preview = FrameLayout(this)
        content = PatternView()
        glass = TextView(this).apply { text = "局部表面：原窗口与内容层保持原样"; gravity = Gravity.CENTER; setTextColor(Color.BLACK); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        preview.addView(content,FrameLayout.LayoutParams(-1,-1))
        preview.addView(glass,FrameLayout.LayoutParams(-1,dp(84),Gravity.CENTER).apply { leftMargin=dp(16);rightMargin=dp(16) })
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        status = TextView(this).apply { textSize=12f;setPadding(dp(12),dp(4),dp(12),dp(4));setTextColor(Color.BLACK) }
        root.addView(status)
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),dp(12)) }
        val scroll=ScrollView(this).apply { addView(controls) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,dp(300)))
        setContentView(root)
        session=LumenSurfaceSession(this,LumenPalette.neutral(false),limits)
        session.setListener { _,backend,failure,_ -> status.text="$backend / $failure · 点击“诊断”查看计数" }
        binding=session.bind(glass,surface,content)
        buildControls(controls)
    }

    private fun applySurface(value:LumenSurfaceOptions) { surface=value;binding.update(value) }
    private fun applySampling(change:(LumenSurfaceSampling)->LumenSurfaceSampling) = applySurface(surface.copy(sampling=change(surface.sampling)))
    private fun applyLimits(value:LumenSurfaceSessionOptions) { limits=value;session.updateOptions(value) }

    private fun buildControls(parent:LinearLayout) {
        fun toggle(label:String,checked:Boolean,change:(Boolean)->Unit) { parent.addView(CheckBox(this).apply { text=label;isChecked=checked;setOnCheckedChangeListener { _,v->change(v) } }) }
        fun slider(label:String,start:Float,end:Float,current:Float,change:(Float)->Unit) {
            val caption=TextView(this).apply { text="$label：$current";setTextColor(Color.BLACK) };parent.addView(caption)
            parent.addView(SeekBar(this).apply {
                max=100;progress=((current-start)/(end-start)*100).roundToInt().coerceIn(0,100)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean) { caption.text="$label：${start+(end-start)*p/100}" }
                    override fun onStartTrackingTouch(bar:SeekBar)=Unit
                    // Effect-resource changes are committed once at release, not on every drag frame.
                    override fun onStopTrackingTouch(bar:SeekBar) { change(start+(end-start)*bar.progress/100) }
                })
            })
        }
        fun action(label:String,change:()->Unit) { parent.addView(Button(this).apply { text=label;setOnClickListener { change() } }) }
        action("切换材质：柔光 / 高级 / 静态") {
            val values=LumenSurfaceMaterial.entries
            applySurface(surface.copy(material=values[(surface.material.ordinal+1)%values.size]));status.text="材质：${surface.material}"
        }
        action("切换后端：自动 / GPU / 软件 / 静态") {
            val values=LumenSurfaceBackend.entries
            applySampling { it.copy(backend=values[(it.backend.ordinal+1)%values.size]) };status.text="请求后端：${surface.sampling.backend}"
        }
        toggle("启用局部表面",surface.enabled) { applySurface(surface.copy(enabled=it)) }
        toggle("启用会话",limits.enabled) { applyLimits(limits.copy(enabled=it)) }
        toggle("采样",surface.sampling.enabled) { enabled->applySampling { it.copy(enabled=enabled) } }
        toggle("模糊",surface.sampling.blurEnabled) { enabled->applySampling { it.copy(blurEnabled=enabled) } }
        toggle("折射",surface.sampling.refractionEnabled) { enabled->applySampling { it.copy(refractionEnabled=enabled) } }
        toggle("GPU 失败后软件回退",surface.sampling.softwareFallback) { enabled->applySampling { it.copy(softwareFallback=enabled) } }
        toggle("渐隐",surface.sampling.fadeEnabled) { enabled->applySampling { it.copy(fadeEnabled=enabled) } }
        toggle("反向渐隐",surface.sampling.fadeDirection==LumenSurfaceFadeDirection.BOTTOM_TO_TOP) { reverse->applySampling { it.copy(fadeDirection=if(reverse)LumenSurfaceFadeDirection.BOTTOM_TO_TOP else LumenSurfaceFadeDirection.TOP_TO_BOTTOM) } }
        toggle("着色",surface.tintEnabled) { applySurface(surface.copy(tintEnabled=it)) }
        toggle("描边",surface.edgeEnabled) { applySurface(surface.copy(edgeEnabled=it)) }
        toggle("裁剪背景",surface.clipBackground) { applySurface(surface.copy(clipBackground=it)) }
        toggle("深色配色（不重建 Activity）",dark) { dark=it;session.updatePalette(LumenPalette.neutral(it));glass.setTextColor(if(it)Color.WHITE else Color.BLACK) }
        toggle("隐藏时暂停",limits.autoPauseWhenHidden) { applyLimits(limits.copy(autoPauseWhenHidden=it)) }
        toggle("窗口失焦时暂停",limits.pauseWhenWindowUnfocused) { applyLimits(limits.copy(pauseWhenWindowUnfocused=it)) }
        toggle("诊断回调",limits.diagnosticsEnabled) { applyLimits(limits.copy(diagnosticsEnabled=it)) }
        toggle("监听内存压力",limits.registerMemoryCallbacks) { applyLimits(limits.copy(registerMemoryCallbacks=it)) }
        toggle("解绑恢复原背景",limits.restoreBackgroundOnDetach) { applyLimits(limits.copy(restoreBackgroundOnDetach=it)) }
        toggle("内容动画（测试静止门控）",animation?.isRunning==true) { if(it) startAnimation() else {animation?.cancel();animation=null} }
        slider("圆角 dp",0f,128f,surface.radiusDp) { applySurface(surface.copy(radiusDp=it)) }
        slider("背景透明度",0f,1f,surface.opacity) { applySurface(surface.copy(opacity=it)) }
        slider("实时着色",0f,1f,surface.tintOpacity) { applySurface(surface.copy(tintOpacity=it)) }
        slider("回退着色",0f,1f,surface.fallbackTintOpacity) { applySurface(surface.copy(fallbackTintOpacity=it)) }
        slider("描边宽度 dp",0f,8f,surface.edgeWidthDp) { applySurface(surface.copy(edgeWidthDp=it)) }
        slider("描边强度",0f,3f,surface.edgeIntensity) { applySurface(surface.copy(edgeIntensity=it)) }
        slider("模糊半径 dp",0f,48f,surface.sampling.blurRadiusDp) { v->applySampling { it.copy(blurRadiusDp=v) } }
        slider("折射强度",0f,2f,surface.sampling.refractionStrength) { v->applySampling { it.copy(refractionStrength=v) } }
        slider("采样间隔 ms",0f,1000f,surface.sampling.minIntervalMs.toFloat()) { v->applySampling { it.copy(minIntervalMs=v.toLong()) } }
        slider("软件采样倍率",.05f,1f,surface.sampling.softwareScale) { v->applySampling { it.copy(softwareScale=v) } }
        slider("每表面软件像素上限",1024f,96000f,surface.sampling.maxSoftwarePixels.toFloat()) { v->applySampling { it.copy(maxSoftwarePixels=v.toInt()) } }
        slider("渐隐保持区",0f,1f,surface.sampling.fadeHold) { v->applySampling { it.copy(fadeHold=v,fadeEnd=max(v,it.fadeEnd)) } }
        slider("渐隐终点",0f,1f,surface.sampling.fadeEnd) { v->applySampling { it.copy(fadeEnd=v,fadeHold=min(v,it.fadeHold)) } }
        slider("GPU 采样倍率",.1f,1f,limits.gpuScale) { applyLimits(limits.copy(gpuScale=it)) }
        slider("软件总预算 MiB",.25f,16f,limits.maxSoftwareBytes/1048576f) { applyLimits(limits.copy(maxSoftwareBytes=(it*1048576).toInt())) }
        slider("GPU 内容预算像素",16384f,8388608f,limits.maxGpuContentPixels.toFloat()) { applyLimits(limits.copy(maxGpuContentPixels=it.toInt())) }
        slider("GPU 表面预算像素",16384f,8388608f,limits.maxGpuSurfacePixels.toFloat()) { applyLimits(limits.copy(maxGpuSurfacePixels=it.toInt())) }
        slider("最多表面数",1f,32f,limits.maxSurfaces.toFloat()) { applyLimits(limits.copy(maxSurfaces=it.toInt())) }
        slider("GPU 最大边长",128f,8192f,limits.maxGpuDimension.toFloat()) { applyLimits(limits.copy(maxGpuDimension=it.toInt())) }
        action("暂停") { session.pause() };action("恢复") {session.resume()}
        action("释放图形资源") {session.releaseGraphics()}
        action("诊断") {status.text=session.diagnostics().toString()+"\n"+binding.diagnostics()}
        action("状态栏渐隐预设") {
            applySurface(LumenSurfacePresets.fadingBand())
            parent.removeAllViews();buildControls(parent)
        }
        action("跨窗口示例") {showWindowExample()}
    }

    private fun startAnimation() {
        animation?.cancel()
        animation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=1600;repeatCount=ValueAnimator.INFINITE
            addUpdateListener { (content as PatternView).phase=animatedValue as Float;content.invalidate() }
            start()
        }
    }

    private fun showWindowExample() {
        val dialog=Dialog(this)
        val root=FrameLayout(this)
        val source=PatternView()
        val panel=TextView(this).apply {text="此面板采样当前 Dialog 的内容";gravity=Gravity.CENTER;setTextColor(Color.BLACK)}
        root.addView(source,FrameLayout.LayoutParams(-1,-1))
        root.addView(panel,FrameLayout.LayoutParams(-1,dp(100),Gravity.CENTER).apply {leftMargin=dp(16);rightMargin=dp(16)})
        dialog.setContentView(root);dialog.window?.setLayout(dp(320),dp(400))
        val local=LumenSurfaceSession(this,LumenPalette.neutral(false),limits)
        local.bind(panel,surface,source)
        dialog.setOnDismissListener {local.close();dialogs.remove(dialog)}
        dialogs.add(dialog);dialog.show();dialog.window?.setLayout(dp(320),dp(400))
    }

    override fun onStart() {super.onStart();if(::session.isInitialized)session.resume()}
    override fun onStop() {animation?.cancel();session.pause();super.onStop()}
    override fun onDestroy() {dialogs.toList().forEach(Dialog::dismiss);session.close();super.onDestroy()}

    private inner class PatternView : View(this) {
        var phase=0f
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas) {
            canvas.drawColor(Color.rgb(225,231,240))
            val row=dp(40).toFloat()
            var y=-phase*row
            var index=0
            while(y<height) {
                paint.color=if(index%2==0)Color.rgb(120,160,230)else Color.rgb(245,155,165)
                canvas.drawRect(0f,y,width.toFloat(),y+row,paint)
                y+=row;index++
            }
        }
    }
}
