@file:Suppress("SetTextI18n")
package com.lumen.coacervation.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import com.lumen.coacervation.engine.assets.*
import com.lumen.coacervation.engine.assets.lottie.LumenLottieFactory
import com.lumen.coacervation.engine.assets.pag.LumenPagFactory
import com.lumen.coacervation.engine.assets.rive.LumenRiveFactory
import com.lumen.coacervation.engine.assets.rive.LumenRiveRenderer
import com.lumen.coacervation.engine.effects.*
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceEnhancements
import com.lumen.coacervation.engine.host.LumenSurfaceLightOptions
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import com.lumen.coacervation.engine.motion.LumenFrameClock
import com.lumen.coacervation.engine.sensor.*

class P2SandboxActivity:Activity(){
    internal lateinit var target:TextView
    internal lateinit var effect:LumenEffectLayer
    internal lateinit var assetContainer:FrameLayout
    internal var assetsSession:LumenAssetSession?=null
    internal var sensor:LumenSensorLightController?=null
    private var effectOptions=LumenEffectOptions();private var sensorOptions=LumenSensorLightOptions();private var assetOptions=LumenAssetOptions()
    private val fixedClock=LumenFixedFrameClock();private var fixed=false
    private var effectControls:P2Controls?=null
    private var selectedFormat=LumenAssetFormat.LOTTIE
    private var riveRenderer=LumenRiveRenderer.CANVAS
    private lateinit var artboard:EditText;private lateinit var animation:EditText;private lateinit var stateMachine:EditText
    private lateinit var status:TextView;private lateinit var material:LumenSurfaceSession
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(saved:Bundle?){super.onCreate(saved)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(236,239,245))}
        target=TextView(this).apply{text="P2 独立效果层";gravity=Gravity.CENTER;setTextColor(Color.BLACK)}
        val backdrop=View(this).apply{setBackgroundColor(Color.rgb(135,174,230))}
        val preview=FrameLayout(this).apply{addView(backdrop,FrameLayout.LayoutParams(-1,-1));addView(target,FrameLayout.LayoutParams(-1,-1))}
        root.addView(preview,LinearLayout.LayoutParams(-1,dp(150)))
        assetContainer=FrameLayout(this);root.addView(assetContainer,LinearLayout.LayoutParams(-1,dp(120)))
        status=TextView(this).apply{setTextColor(Color.BLACK);textSize=12f};root.addView(status)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),dp(20))}
        root.addView(ScrollView(this).apply{addView(controls)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        material=LumenSurfaceSession(this,LumenPalette.neutral(false));val binding=material.bind(target,LumenSurfaceOptions(),backdrop)
        // Optional sensor producer changes only the chosen light input; decorative generators still never capture.
        binding.updateEnhancements(LumenSurfaceEnhancements(light=LumenSurfaceLightOptions(enabled=true)))
        effect=LumenEffectLayer(target,effectOptions)
        sensor=LumenSensorLightController(target,LumenLightVectorListener{x,y,z->binding.setLightDirection(x,y,z)},sensorOptions)
        fun button(text:String,action:()->Unit){controls.addView(Button(this).apply{this.text=text;setOnClickListener{action()}})}
        fun toggle(text:String,checked:Boolean,action:(Boolean)->Unit){controls.addView(CheckBox(this).apply{this.text=text;isChecked=checked;setOnCheckedChangeListener{_,v->action(v)}})}
        fun slider(text:String,min:Float,max:Float,value:Float,action:(Float)->Unit){val label=TextView(this).apply{setTextColor(Color.BLACK);this.text="$text：$value"};controls.addView(label)
            controls.addView(SeekBar(this).apply{this.max=1000;progress=((value-min)/(max-min)*1000).toInt();setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(v:SeekBar,p:Int,user:Boolean){label.text="$text：${min+(max-min)*p/1000f}"};override fun onStartTrackingTouch(v:SeekBar)=Unit
                override fun onStopTrackingTouch(v:SeekBar){action(min+(max-min)*v.progress/1000f)}
            })})}
        button("发射一组粒子"){effect.emitBurst(target.width*.5f,target.height*.5f)};button("触发短时能量"){effect.triggerEnergy()}
        toggle("固定时钟（效果与可控资产）",false){fixed=it;effect.close();effect=LumenEffectLayer(target,effectOptions,if(it)fixedClock else LumenFrameClock{System.nanoTime()});assetsSession?.close();assetsSession=null}
        button("步进16ms"){if(fixed){fixedClock.advanceMillis(16);effect.advanceFrame();assetsSession?.advanceFrame()}}
        button("暂停"){effect.pause();sensor?.stop();assetsSession?.pause()};button("恢复"){effect.resume();sensor?.start();assetsSession?.resume()}
        button("诊断"){status.text=effect.diagnostics().toString()+"\n"+sensor?.diagnostics()+"\n"+assetsSession?.diagnostics()}
        button("复制效果参数JSON"){(getSystemService(CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lumen P2",LumenEffectPreset(effectOptions).toJson()))}
        button("导入效果参数JSON"){val input=EditText(this).apply{filters=arrayOf(android.text.InputFilter.LengthFilter(16384))};val dialog=AlertDialog.Builder(this).setTitle("参数回放").setView(input).setNegativeButton("取消",null).setPositiveButton("应用",null).create()
            dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{effectOptions=LumenEffectPreset.fromJson(input.text.toString()).options;effect.update(effectOptions);effectControls?.build();dialog.dismiss()}catch(_:Exception){input.error="参数版本、类型或范围无效"}}};dialog.show()}
        controls.addView(TextView(this).apply{text="可选传感器光源（默认关闭）";setTextColor(Color.BLACK)})
        fun updateSensor(change:(LumenSensorLightOptions)->LumenSensorLightOptions){sensorOptions=change(sensorOptions);sensor?.update(sensorOptions);sensor?.start()}
        toggle("启用传感器",false){updateSensor{old->old.copy(enabled=it)}}
        toggle("允许加速度计回退",true){updateSensor{old->old.copy(allowAccelerometerFallback=it)}}
        toggle("反转X",false){updateSensor{old->old.copy(invertX=it)}};toggle("反转Y",false){updateSensor{old->old.copy(invertY=it)}}
        slider("采样率Hz",1f,60f,30f){v->updateSensor{it.copy(sampleRateHz=v.toInt())}}
        slider("最小发布间隔ms",16f,1000f,32f){v->updateSensor{it.copy(minimumUpdateIntervalMs=v.toLong())}}
        slider("滤波时常ms",0f,2000f,150f){v->updateSensor{it.copy(smoothingTimeMs=v.toLong())}}
        slider("传感器影响",0f,1f,.5f){v->updateSensor{it.copy(influence=v)}}
        slider("倾斜上限度",0f,60f,45f){v->updateSensor{it.copy(maximumTiltDegrees=v)}}
        slider("角速度上限度/s",10f,360f,180f){v->updateSensor{it.copy(maximumAngularSpeedDegrees=v)}}
        slider("变化阈值度",0f,10f,.3f){v->updateSensor{it.copy(changeThresholdDegrees=v)}}
        slider("平放阈值",0f,.2f,.025f){v->updateSensor{it.copy(flatThreshold=v)}}
        slider("基准光源角度",0f,360f,145f){v->updateSensor{it.copy(baseAngleDegrees=v)}}
        slider("基准光源高度",.05f,1f,.65f){v->updateSensor{it.copy(baseAltitude=v)}}
        button("切换旋转：自动/固定0/90/180/270"){updateSensor{it.copy(fixedRotation=when(it.fixedRotation){null->0;0->1;1->2;2->3;else->null})};status.text="映射：${sensorOptions.fixedRotation?:"自动主显示器"}"}
        controls.addView(TextView(this).apply{text="独立资产播放器";setTextColor(Color.BLACK)})
        fun field(hint:String)=EditText(this).apply{this.hint=hint;setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(128));controls.addView(this)}
        artboard=field("Rive 画板名称（留空使用首个）");animation=field("Rive 动画名称（留空使用首个）");stateMachine=field("Rive 状态机名称（留空使用时间线）")
        button("加载 Lottie 示例"){loadAsset(LumenAssetFormat.LOTTIE)};button("加载 PAG 示例"){loadAsset(LumenAssetFormat.PAG)};button("加载 Rive 示例（原生时钟）"){loadAsset(LumenAssetFormat.RIVE)}
        toggle("Rive GPU后端（下次加载生效）",false){riveRenderer=if(it)LumenRiveRenderer.GPU else LumenRiveRenderer.CANVAS;status.text="Rive后端：$riveRenderer"}
        button("打开自己的动画文件"){AlertDialog.Builder(this).setTitle("选择资产格式").setItems(arrayOf("Lottie JSON","PAG","Rive")){_,index->selectedFormat=LumenAssetFormat.entries[index]
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE)},71)}.show()}
        button("播放资产"){assetsSession?.play()};button("关闭资产"){assetsSession?.close();assetsSession=null}
        slider("资产进度",0f,1f,0f){assetsSession?.seek(it)}
        slider("资产透明度",0f,1f,1f){v->assetOptions=assetOptions.copy(opacity=v);assetsSession?.update(assetOptions)}
        slider("资产速度（Rive固定1）",.1f,4f,1f){v->assetOptions=assetOptions.copy(speed=v);assetsSession?.update(assetOptions)}
        slider("有限播放次数（Rive固定1）",1f,10f,1f){v->assetOptions=assetOptions.copy(repeatCount=v.toInt());assetsSession?.update(assetOptions)}
        slider("提交帧率（不控制Rive原生时钟）",1f,60f,30f){v->assetOptions=assetOptions.copy(framesPerSecond=v.toInt());assetsSession?.update(assetOptions)}
        slider("文件预算MiB",.01f,16f,4f){v->assetOptions=assetOptions.copy(maximumFileBytes=(v*1024*1024).toInt());assetsSession?.update(assetOptions)}
        slider("资产绘制像素",16384f,4194304f,1048576f){v->assetOptions=assetOptions.copy(maximumRenderPixels=v.toInt());assetsSession?.update(assetOptions)}
        slider("资源图像像素",1024f,4194304f,1048576f){v->assetOptions=assetOptions.copy(maximumDecodedImagePixels=v.toInt());assetsSession?.update(assetOptions)}
        slider("最大播放时长秒",.1f,600f,60f){v->assetOptions=assetOptions.copy(maximumDurationMs=(v*1000).toLong());assetsSession?.update(assetOptions)}
        toggle("资产降低动态",false){assetOptions=assetOptions.copy(reduceMotion=it);assetsSession?.update(assetOptions)}
        toggle("启用资产显示",true){assetOptions=assetOptions.copy(enabled=it);assetsSession?.update(assetOptions)}
        toggle("下次加载自动播放",false){assetOptions=assetOptions.copy(autoplay=it);assetsSession?.update(assetOptions)}
        toggle("资产失焦暂停",true){assetOptions=assetOptions.copy(pauseWhenUnfocused=it);assetsSession?.update(assetOptions)}
        toggle("资产裁剪边界",true){assetOptions=assetOptions.copy(clipToBounds=it);assetsSession?.update(assetOptions)}
        toggle("资产静音",true){assetOptions=assetOptions.copy(mute=it);assetsSession?.update(assetOptions)}
        toggle("允许PAG视频",false){assetOptions=assetOptions.copy(videoEnabled=it);assetsSession?.update(assetOptions)}
        button("适配：包含/裁剪/拉伸"){val types=LumenAssetFit.entries;assetOptions=assetOptions.copy(fit=types[(assetOptions.fit.ordinal+1)%types.size]);assetsSession?.update(assetOptions)}
        val inputName=field("Rive 状态机输入名称");val inputValue=field("数值输入值")
        fun validName():String?=inputName.text.toString().trim().takeIf{it.isNotEmpty()}.also{if(it==null)inputName.error="请输入名称"}
        button("提交数值输入"){val name=validName();val value=inputValue.text.toString().toFloatOrNull();if(value==null||!value.isFinite())inputValue.error="请输入有限数值" else if(name!=null)status.text="输入结果：${assetsSession?.setNumber(name,value)==true}"}
        toggle("布尔输入值",false){value->validName()?.let{status.text="输入结果：${assetsSession?.setBoolean(it,value)==true}"}}
        button("触发状态机输入"){validName()?.let{status.text="输入结果：${assetsSession?.fire(it)==true}"}}
        val effectPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};controls.addView(effectPanel)
        effectControls=P2Controls(this,effectPanel,{effectOptions},{effectOptions=it;effect.update(it)}).also{it.build()}
    }
    internal fun loadAsset(format:LumenAssetFormat){
        val name=when(format){LumenAssetFormat.LOTTIE->"p2/circle.json";LumenAssetFormat.PAG->"p2/minimal.pag";LumenAssetFormat.RIVE->"p2/circle_move.riv"}
        loadSource(format,LumenAssetSource{assets.open(name)})
    }
    private fun loadSource(format:LumenAssetFormat,source:LumenAssetSource){
        assetsSession?.close();val clock=if(fixed)fixedClock else LumenFrameClock{System.nanoTime()}
        val factory:LumenAssetFactory=when(format){LumenAssetFormat.LOTTIE->LumenLottieFactory();LumenAssetFormat.PAG->LumenPagFactory();LumenAssetFormat.RIVE->LumenRiveFactory(this,riveRenderer)}
        if(format==LumenAssetFormat.RIVE)assetOptions=assetOptions.copy(speed=1f,repeatCount=1)
        val selection=LumenAssetSelection(artboard.text.toString().trim().ifEmpty{null},animation.text.toString().trim().ifEmpty{null},stateMachine.text.toString().trim().ifEmpty{null})
        assetsSession=LumenAssetSession(assetContainer,assetOptions,clock).also{it.setListener{state,error->status.text="$format / $state / $error"};it.load(source,factory,selection)}
    }
    @Deprecated("Activity result compatibility for the native View demo")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==71&&resultCode==RESULT_OK){val uri=data?.data?:return;loadSource(selectedFormat,LumenAssetSource{contentResolver.openInputStream(uri)?:throw IllegalArgumentException("Cannot open asset")})}}
    override fun onStart(){super.onStart();if(::effect.isInitialized)effect.resume();sensor?.start();assetsSession?.resume()}
    override fun onStop(){if(::effect.isInitialized)effect.pause();sensor?.stop();assetsSession?.pause();super.onStop()}
    override fun onDestroy(){if(::effect.isInitialized)effect.close();sensor?.close();assetsSession?.close();if(::material.isInitialized)material.close();super.onDestroy()}
}
