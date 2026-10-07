@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.app.AlertDialog
import com.lumen.coacervation.engine.host.*

/** Controls serialize through the same versioned codec used by playback. No reflection or hidden fields. */
internal class EnhancementControls(
    private val context: Context,private val container: LinearLayout,
    private val read: ()->LumenEffectPreset,private val write: (LumenEffectPreset)->Unit,
    private val step: ()->Unit,private val fixedClock: (Boolean)->Unit,private val isFixedClock: ()->Boolean
) {
    private var rebuilding=false
    fun build(){
        rebuilding=true
        container.removeAllViews()
        title("1.2 几何、材质与交互增强")
        val metadata=read().toJson()
        val root=org.json.JSONObject(metadata)
        val groups=listOf("geometry" to "形状 / 融合 / 阴影","progressiveBlur" to "两级渐进模糊","press" to "局部按压 / 有限波纹",
            "light" to "光源 / 变换高光","recipe" to "材质意图 / 可读性","quality" to "细节质量 / 硬预算","debug" to "诊断 / 外扩范围实验")
        for((key,label)in groups){
            title(label)
            val data=root.getJSONObject(key)
            val names=data.keys().asSequence().toList()
            for(name in names){
                val value=data.get(name)
                when(value){
                    is Boolean->toggle(label(name),value){update(key,name,it)}
                    is Number->{val range=range(key,name);if(range!=null)slider(label(name),value.toFloat(),range.first,range.second){update(key,name,if(value is Int||value is Long)it.toLong()else it)}}
                    is String->choice(label(name),value,values(name)){update(key,name,it)}
                    is org.json.JSONArray->for(i in 0..3)slider(listOf("左上圆角","右上圆角","右下圆角","左下圆角")[i],value.getDouble(i).toFloat(),0f,128f){next->
                        val json=org.json.JSONObject(read().toJson());json.getJSONObject(key).getJSONArray(name).put(i,next);write(LumenEffectPreset.fromJson(json.toString()))
                    }
                }
            }
        }
        title("固定时钟与参数回放")
        toggle("固定时钟（手动推进，不持续运行）",isFixedClock(),fixedClock)
        button("推进 16ms",step)
        button("复制参数 JSON（不含背景图片）"){
            val manager=context.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Lumen effect preset",read().toJson()))
        }
        button("导入参数 JSON"){
            val input=EditText(context).apply {hint="粘贴之前导出的参数 JSON";filters=arrayOf(android.text.InputFilter.LengthFilter(16_384))}
            val dialog=AlertDialog.Builder(context).setTitle("参数回放").setView(input).setNegativeButton("取消",null).setPositiveButton("应用",null).create()
            dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {val preset=LumenEffectPreset.fromJson(input.text.toString());write(preset);build();dialog.dismiss()}
                catch(_:Exception){input.error="参数无效：请检查版本、字段类型和数值范围"}
            }}
            dialog.show()
        }
        button("恢复增强默认值"){write(read().copy(enhancements=LumenSurfaceEnhancements.DEFAULT));build()}
        rebuilding=false
    }
    private fun update(group: String,key: String,value: Any){
        if(rebuilding)return
        try{
            val json=org.json.JSONObject(read().toJson());val data=json.getJSONObject(group);data.put(key,value)
            if(value is Number){
                val pairs=if(group=="progressiveBlur")listOf("weakRadiusDp" to "strongRadiusDp","weakStart" to "weakEnd","strongStart" to "strongEnd")else if(group=="quality")listOf("lowerThreshold" to "upperThreshold")else emptyList()
                for((start,end)in pairs){
                    if(key==start&&data.getDouble(start)>data.getDouble(end))data.put(end,value)
                    if(key==end&&data.getDouble(end)<data.getDouble(start))data.put(start,value)
                }
            }
            write(LumenEffectPreset.fromJson(json.toString()))
        }catch(_:Exception){Toast.makeText(context,"调节值超出有效范围，已保留上次参数",Toast.LENGTH_SHORT).show();build()}
    }
    private fun title(label: String){container.addView(TextView(context).apply {text=label;textSize=16f;setTextColor(Color.BLACK);setPadding(0,16,0,4)})}
    private fun toggle(label: String,value: Boolean,change:(Boolean)->Unit){container.addView(CheckBox(context).apply {text=label;setTextColor(Color.BLACK);isChecked=value;setOnCheckedChangeListener {_,v->change(v)}})}
    private fun choice(label: String,value: String,values: List<String>,change:(String)->Unit){
        val button=Button(context).apply {text="$label: ${enumLabel(value)}"}
        var selected=values.indexOf(value).coerceAtLeast(0)
        button.setOnClickListener {selected=(selected+1)%values.size;button.text="$label: ${enumLabel(values[selected])}";change(values[selected])};container.addView(button)
    }
    private fun slider(label: String,value: Float,min: Float,max: Float,change:(Float)->Unit){
        val text=TextView(context).apply {setTextColor(Color.BLACK);this.text="$label: $value"};container.addView(text)
        container.addView(SeekBar(context).apply {
            this.max=1000;progress=((value-min)/(max-min)*1000).toInt().coerceIn(0,1000)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar: SeekBar,p: Int,user: Boolean){text.text="$label: ${min+(max-min)*p/1000f}"}
                override fun onStartTrackingTouch(bar: SeekBar)=Unit
                override fun onStopTrackingTouch(bar: SeekBar){change(min+(max-min)*bar.progress/1000f)}
            })
        })
    }
    private fun button(label: String,action:()->Unit){container.addView(Button(context).apply {text=label;setOnClickListener {action()}})}
    private fun values(name: String): List<String> = when(name){
        "intent"->LumenMaterialIntent.entries.map {it.name};"mode"->LumenDetailMode.entries.map {it.name}
        "samplingRange"->LumenSamplingRangeMode.entries.map {it.name};else->LumenSurfaceFadeDirection.entries.map {it.name}
    }
    private fun enumLabel(value: String): String=when(value){
        "UNCHANGED"->"沿用宿主";"READING"->"阅读";"CLEAR"->"通透";"OPAQUE_ACCESSIBLE"->"不透明易读";"DECORATIVE"->"装饰"
        "LOW"->"低";"BALANCED"->"均衡";"HIGH"->"高";"SAFE_INTERIOR"->"保守内区";"PADDED_EXPERIMENTAL"->"外扩采样（实验）"
        "TOP_TO_BOTTOM"->"从上到下";"BOTTOM_TO_TOP"->"从下到上";else->value
    }
    private fun label(name: String): String=when(name){
        "enabled"->"启用";"cornersEnabled"->"独立四角";"mirrorCornersInRtl"->"从右向左布局时镜像圆角"
        "fusionEnabled"->"双形状融合";"fusionRadiusDp"->"融合半径 dp";"antiAliasWidthDp"->"抗锯齿宽度 dp"
        "shadowEnabled"->"阴影";"shadowRadiusDp"->"阴影半径 dp";"shadowOpacity"->"阴影透明度"
        "weakRadiusDp"->"弱模糊半径 dp";"strongRadiusDp"->"强模糊半径 dp";"weakStart"->"弱模糊开始位置"
        "weakEnd"->"弱模糊完成位置";"strongStart"->"强模糊开始位置";"strongEnd"->"强模糊完成位置"
        "direction"->"方向";"strength"->"强度";"maxBandHeightDp"->"最大条带高度 dp"
        "displacementDp"->"按压位移 dp";"radiusFraction"->"按压作用范围";"highlightStrength"->"局部高光强度"
        "cancelOutside"->"移出控件取消";"releaseDurationMs"->"释放回弹时长 ms";"rippleEnabled"->"释放波纹"
        "rippleAmplitudeDp"->"波纹振幅 dp";"rippleSpeedDpPerSecond"->"波纹速度 dp/s";"rippleWidthDp"->"波纹宽度 dp"
        "rippleLifetimeMs"->"波纹时长 ms";"maxRipples"->"最多波纹数"
        "angleDegrees"->"光源角度";"altitude"->"光源高度";"intensity"->"光照强度";"specularStrength"->"镜面高光强度"
        "specularPower"->"高光集中程度";"edgeWidthDp"->"边缘光宽度 dp";"transformNormals"->"随控件变换法线"
        "gestureInfluence"->"触点影响光源程度";"smoothingTimeMs"->"光源平滑时长 ms"
        "intent"->"材质用途";"useIntentDefaults"->"使用用途建议的着色（关闭后由宿主控制）";"normalizeBySize"->"按控件尺寸限制效果";"maxEdgeFraction"->"最大边缘宽度比例"
        "maxRefractionFraction"->"最大折射位移比例";"contrastFloor"->"回退着色下限"
        "reduceTransparency"->"降低透明效果";"reduceMotion"->"降低动态效果";"chromaticStrength"->"色散强度";"saturation"->"饱和度"
        "mode"->"细节等级";"adaptive"->"自动调节细节";"lowerThreshold"->"降低到均衡的压力阈值"
        "upperThreshold"->"降低到低细节的压力阈值";"hysteresis"->"恢复滞回范围";"minimumDwellMs"->"最短等级驻留 ms"
        "maxExecutionPixels"->"最大绘制像素";"maxBitmapPixels"->"最大输入位图像素";"bitmapIntervalMs"->"位图采样间隔 ms"
        "countersEnabled"->"记录计数";"timingEnabled"->"记录耗时";"drawSamplingBounds"->"显示五类采样范围"
        "boundsLineWidthDp"->"范围线宽 dp";"boundsOpacity"->"范围线透明度";"samplingRange"->"采样范围模式"
        else->name
    }
    private fun range(group: String,key: String): Pair<Float,Float>?=when(key){
        "fusionRadiusDp"->0f to 64f;"antiAliasWidthDp"->.25f to 2f;"shadowRadiusDp"->0f to 24f;"shadowOpacity"->0f to .8f
        "weakRadiusDp","strongRadiusDp"->0f to 48f;"weakStart","weakEnd","strongStart","strongEnd","strength"->0f to 1f
        "maxBandHeightDp"->24f to 256f;"displacementDp"->-8f to 8f;"radiusFraction"->.05f to .75f;"highlightStrength"->0f to 3f
        "releaseDurationMs"->50f to 1000f;"rippleAmplitudeDp"->0f to 4f;"rippleSpeedDpPerSecond"->20f to 400f
        "rippleWidthDp"->2f to 48f;"rippleLifetimeMs"->100f to 2000f;"maxRipples"->1f to 4f
        "angleDegrees"->0f to 360f;"altitude"->.05f to 1f;"intensity","specularStrength"->0f to 3f;"specularPower"->2f to 64f
        "edgeWidthDp"->.25f to 12f;"gestureInfluence"->0f to 1f;"smoothingTimeMs"->0f to 1000f
        "maxEdgeFraction"->.02f to .4f;"maxRefractionFraction"->0f to .25f;"contrastFloor"->.5f to 1f
        "chromaticStrength","saturation"->0f to 2f;"lowerThreshold"->.05f to .9f;"upperThreshold"->.05f to .95f
        "hysteresis"->0f to .2f;"minimumDwellMs"->250f to 10000f;"maxExecutionPixels"->16384f to 4194304f
        "maxBitmapPixels"->1024f to 96000f;"bitmapIntervalMs"->0f to 1000f;"boundsLineWidthDp"->.5f to 4f;"boundsOpacity"->.1f to 1f
        else->null
    }
}
