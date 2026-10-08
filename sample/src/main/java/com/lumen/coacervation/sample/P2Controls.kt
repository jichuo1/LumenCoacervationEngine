@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.content.Context
import android.graphics.Color
import android.widget.*
import com.lumen.coacervation.engine.effects.*
import org.json.JSONObject

internal class P2Controls(private val context:Context,private val parent:LinearLayout,private val read:()->LumenEffectOptions,private val write:(LumenEffectOptions)->Unit){
    fun build(){
        parent.removeAllViews();val root=JSONObject(LumenEffectPreset(read()).toJson())
        for(group in listOf("layer","procedural","film","paper","energy","particles")){
            parent.addView(TextView(context).apply{text=when(group){"layer"->"调度 / 预算";"procedural"->"材质生成器";"film"->"薄膜";"paper"->"纸张";"energy"->"能量";else->"有限粒子"};setTextColor(Color.BLACK);textSize=17f})
            val data=root.getJSONObject(group)
            for(key in data.keys().asSequence().toList()){
                val value=data.get(key);val caption=label(key)
                fun update(next:Any){try{val json=JSONObject(LumenEffectPreset(read()).toJson());json.getJSONObject(group).put(key,next);write(LumenEffectPreset.fromJson(json.toString()).options)}catch(_:Exception){Toast.makeText(context,"参数超出范围，保留原设置",Toast.LENGTH_SHORT).show();build()}}
                when(value){
                    is Boolean->parent.addView(CheckBox(context).apply{text=caption;isChecked=value;setOnCheckedChangeListener{_,v->update(v)}})
                    is String->parent.addView(Button(context).apply{text="$caption：${enumLabel(value)}";setOnClickListener{
                        val choices=if(key=="kind")LumenProceduralKind.entries.map{it.name}else LumenParticleShape.entries.map{it.name};val old=data.getString(key);val next=choices[(choices.indexOf(old)+1)%choices.size];data.put(key,next);text="$caption：${enumLabel(next)}";update(next)}})
                    is Number->{
                        if(key=="color"){parent.addView(Button(context).apply{text="$caption：切换预设";setOnClickListener{val colors=listOf(0xffefefff.toInt(),0xff89afff.toInt(),0xffffbaca.toInt(),0xffb5efcc.toInt());val next=colors[(colors.indexOf(read().let{if(group=="particles")it.particles.color else it.procedural.color})+1)%colors.size];update(next)}});continue}
                        val range=range(group,key);val text=TextView(context).apply{setTextColor(Color.BLACK);this.text="$caption：$value"};parent.addView(text)
                        parent.addView(SeekBar(context).apply{max=1000;progress=((value.toFloat()-range.first)/(range.second-range.first)*1000).toInt().coerceIn(0,1000)
                            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                                override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean){text.text="$caption：${range.first+(range.second-range.first)*p/1000f}"}
                                override fun onStartTrackingTouch(bar:SeekBar)=Unit
                                override fun onStopTrackingTouch(bar:SeekBar){val next=range.first+(range.second-range.first)*bar.progress/1000f;update(if(value is Int||value is Long)next.toLong()else next)}
                            })})
                    }
                }
            }
        }
    }
    private fun enumLabel(s:String)=when(s){"FILM"->"薄膜";"PAPER"->"纸张";"ENERGY"->"短时能量";"ORB"->"光点";"STREAK"->"短线";else->s}
    private fun range(group:String,key:String):Pair<Float,Float> = when(key){
        "seed"->-10000f to 10000f;"framesPerSecond"->1f to 60f;"maximumRenderPixels"->16384f to 1048576f
        "opacity","iridescence","contrast","relief"->0f to 1f;"radiusDp"->if(group=="particles")1f to 12f else 0f to 128f
        "thicknessNm"->100f to 2000f;"roughness"->.05f to 1f;"angleDegrees","directionDegrees","spreadDegrees"->0f to 360f
        "intensity"->0f to 2f;"grainCount"->16f to 512f;"grainSizeDp"->.5f to 12f;"durationMs","lifetimeMs"->100f to 4000f
        "speed"->.1f to 3f;"bands"->1f to 8f;"wavelengthDp"->8f to 96f;"maximumParticles"->4f to 128f;"burstCount"->1f to 64f
        "speedDpPerSecond"->0f to 800f;"gravityDpPerSecondSquared"->-300f to 300f;"trailLengthDp"->0f to 24f;"maximumParticlePixels"->1024f to 262144f
        else->error("No range for $key")
    }
    private fun label(s:String)=when(s){
        "enabled"->"启用";"seed"->"随机种子";"framesPerSecond"->"提交帧率";"maximumRenderPixels"->"最大绘制像素";"reduceMotion"->"降低动态效果"
        "pauseWhenUnfocused"->"失焦暂停";"clearOnPause"->"暂停清空瞬态";"clipToBounds"->"裁剪边界";"kind"->"材质种类";"opacity"->"透明度";"color"->"颜色"
        "radiusDp"->"圆角 / 粒子半径 dp";"gpuEnabled"->"GPU生成器";"thicknessNm"->"风格化薄膜厚度 nm";"iridescence"->"虹彩强度";"roughness"->"粗糙度"
        "angleDegrees"->"光照角度";"intensity"->"强度";"grainEnabled"->"颗粒开关";"grainCount"->"颗粒数";"grainSizeDp"->"颗粒大小 dp";"contrast"->"颗粒对比";"relief"->"浮雕感"
        "durationMs"->"能量时长 ms";"speed"->"能量速度";"bands"->"波带数";"wavelengthDp"->"波长 dp";"shape"->"粒子形状";"maximumParticles"->"粒子池上限"
        "burstCount"->"单次数量";"lifetimeMs"->"粒子寿命 ms";"speedDpPerSecond"->"粒子速度 dp/s";"gravityDpPerSecondSquared"->"重力 dp/s²"
        "spreadDegrees"->"散射角度";"directionDegrees"->"发射方向";"trailLengthDp"->"短线长度 dp";"maximumParticlePixels"->"粒子面积预算";else->s
    }
}
