package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled pixels, rather than merely a nonempty screenshot or a GPU status flag. */
@RunWith(AndroidJUnit4::class)
class SurfacePixelContractTest {
    @get:org.junit.Rule val hardwareOutput=HardwareOutputRule()
    private fun scene(scenario: ActivityScenario<SurfaceSandboxActivity>,stripes: Boolean=false,transparent: Boolean=false,marked: Boolean=false) {
        scenario.onActivity {a->
            a.binding.close();a.preview.removeAllViews();a.preview.setBackgroundColor(Color.BLACK)
            a.content=object:View(a){private val paint=Paint();override fun onDraw(c:Canvas){
                c.drawColor(if(transparent)Color.argb(64,120,40,200)else if(stripes)Color.WHITE else Color.BLUE)
                if(stripes){paint.color=Color.BLACK;var x=0;while(x<width){c.drawRect(x.toFloat(),0f,(x+6).toFloat(),height.toFloat(),paint);x+=12}}
                if(marked){paint.color=Color.RED;c.drawRect(0f,0f,20f,height.toFloat(),paint)}
            }}
            a.glass=TextView(a)
            a.preview.addView(a.content,FrameLayout.LayoutParams(-1,-1))
            a.preview.addView(a.glass,FrameLayout.LayoutParams(224,160).apply {leftMargin=20;topMargin=20})
            a.binding=a.session.bind(a.glass,LumenSurfaceOptions(radiusDp=0f,edgeEnabled=false,tintEnabled=!stripes,color=Color.WHITE,tintOpacity=1f,
                sampling=LumenSurfaceSampling(blurEnabled=false,refractionEnabled=false,softwareScale=1f,maxSoftwarePixels=96_000)),a.content,
                LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(0f,0f,0f,0f)),quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000)))
        }
    }
    private fun gpu(scenario:ActivityScenario<SurfaceSandboxActivity>){
        val end=SystemClock.uptimeMillis()+7000;var ready=false
        while(!ready&&SystemClock.uptimeMillis()<end){scenario.onActivity {ready=it.binding.diagnostics()?.backend==LumenSurfaceBackend.GPU};if(!ready)SystemClock.sleep(25)}
        scenario.onActivity {assertTrue("Direct shader did not produce a frame: ${it.binding.diagnostics()} / ${it.session.performanceDiagnostics()}",ready)};SystemClock.sleep(100)
    }
    private fun image(scenario:ActivityScenario<SurfaceSandboxActivity>):Bitmap {
        val latch=CountDownLatch(1);var bitmap:Bitmap?=null;var result=-1
        scenario.onActivity {a->
            val at=IntArray(2);a.glass.getLocationInWindow(at)
            bitmap=Bitmap.createBitmap(a.glass.width,a.glass.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window,Rect(at[0],at[1],at[0]+a.glass.width,at[1]+a.glass.height),bitmap!!,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))
        }
        assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result);return bitmap!!
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun smoothFusionFillsTheBridgeButTheHardUnionDoesNot(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s)
            fun setup(k:Float){s.onActivity {a->
                val density=a.resources.displayMetrics.density
                a.binding.updateEnhancements(LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(0f,0f,0f,0f),fusionEnabled=true,fusionRadiusDp=k/density),quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000)))
                a.binding.setShapesPixels(30f,30f,72f,80f,110f,30f,72f,80f,true)
            };gpu(s)}
            setup(0f);val hard=image(s);assertTrue(Color.red(hard.getPixel(106,70))<40)
            setup(64f);val smooth=image(s);assertTrue(Color.red(smooth.getPixel(106,70))>200)
            assertTrue(Color.red(smooth.getPixel(10,10))<40)
            s.onActivity {it.binding.updateEnhancements(LumenSurfaceEnhancements.DEFAULT)};gpu(s);val single=image(s)
            assertTrue(Color.red(single.getPixel(60,70))>200);assertTrue(Color.red(single.getPixel(140,70))<40)
            hard.recycle();smooth.recycle();single.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun asymmetricLargeCornerMatchesTheCanvasContourAwayFromAntialiasing(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s);s.onActivity {a->
                val d=a.resources.displayMetrics.density
                a.binding.updateEnhancements(LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(80f/d,0f,0f,0f)),quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000)))
                a.binding.setShapesPixels(20f,20f,160f,100f,0f,0f,0f,0f,false)
            };gpu(s);val actual=image(s)
            val expected=Bitmap.createBitmap(224,160,Bitmap.Config.ARGB_8888);val c=Canvas(expected);c.drawColor(Color.BLUE)
            val path=android.graphics.Path().apply {addRoundRect(android.graphics.RectF(20f,20f,180f,120f),floatArrayOf(80f,80f,0f,0f,0f,0f,0f,0f),android.graphics.Path.Direction.CW)}
            c.drawPath(path,Paint().apply {color=Color.WHITE})
            for(y in 3 until 157 step 7)for(x in 3 until 221 step 7){
                val desired=expected.getPixel(x,y)
                // Exclude the contour's AA transition, but include the corner extending beyond half the short edge.
                val inside=Color.red(desired)>128
                val stable=(x-2..x+2).all {xx->(y-2..y+2).all {yy->(Color.red(expected.getPixel(xx,yy))>128)==inside}}
                if(stable)assertEquals("Contour at $x,$y",inside,Color.red(actual.getPixel(x,y))>128)
            }
            actual.recycle();expected.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun progressiveBlurReducesStripeContrastAndCanReverseDirection(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s,true)
            fun setup(direction:LumenSurfaceFadeDirection){s.onActivity {it.binding.updateEnhancements(LumenSurfaceEnhancements(
                progressiveBlur=LumenProgressiveBlurOptions(enabled=true,weakRadiusDp=0f,strongRadiusDp=24f,direction=direction),
                quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000)))};gpu(s)}
            fun contrast(b:Bitmap,y:Int):Int{var low=255;var high=0;for(x in 30 until 194){val v=Color.red(b.getPixel(x,y));low=minOf(low,v);high=maxOf(high,v)};return high-low}
            setup(LumenSurfaceFadeDirection.TOP_TO_BOTTOM);val forward=image(s)
            assertTrue("Blur contrast: top=${contrast(forward,20)}, bottom=${contrast(forward,140)}",contrast(forward,20)>contrast(forward,140)+70)
            setup(LumenSurfaceFadeDirection.BOTTOM_TO_TOP);val reverse=image(s)
            assertTrue(contrast(reverse,140)>contrast(reverse,20)+70);forward.recycle();reverse.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun localPressChangesNearbyPixelsAndLeavesDistantPixelsStable(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s,true);s.onActivity {it.binding.updateEnhancements(LumenSurfaceEnhancements(press=LumenLocalPressOptions(enabled=true,displacementDp=8f,radiusFraction=.1f,highlightStrength=0f),quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000)))}
            gpu(s);val rest=image(s)
            s.onActivity {it.binding.setPressPixels(60f,80f,1f)};SystemClock.sleep(150);val pressed=image(s)
            var near=0L;var far=0L
            for(y in 50..110)for(x in 30..90)near+=kotlin.math.abs(Color.red(rest.getPixel(x,y))-Color.red(pressed.getPixel(x,y)))
            for(y in 50..110)for(x in 160..210)far+=kotlin.math.abs(Color.red(rest.getPixel(x,y))-Color.red(pressed.getPixel(x,y)))
            assertTrue("No local displacement",near>2000);assertTrue("Displacement leaked to distant pixels",far<near/10)
            rest.recycle();pressed.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun sdrTransparentInputAndTintFollowPremultipliedComposition(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s,transparent=true);s.onActivity {a->
                a.binding.update(LumenSurfaceOptions(radiusDp=0f,color=Color.WHITE,tintOpacity=.2f,edgeEnabled=false,
                    sampling=LumenSurfaceSampling(blurEnabled=false,refractionEnabled=false,softwareScale=1f,maxSoftwarePixels=96_000)))
            };gpu(s);val actual=image(s);val color=actual.getPixel(110,80)
            // sRGB ARGB8888 source over black; the same source remains visible below the transparent surface.
            val sourceAlpha=64f/255f;val tint=.2f;val outputAlpha=tint+sourceAlpha*(1f-tint)
            for((observed,straight)in listOf(Color.red(color) to 120,Color.green(color) to 40,Color.blue(color) to 200)){
                val expected=255f*tint+straight*sourceAlpha*(1f-tint)+straight*sourceAlpha*(1f-outputAlpha)
                assertEquals("SDR premultiplied composition",expected,observed.toFloat(),4f)
            }
            actual.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun paddedExperimentCanReadTheOutsideMarkerAndReportsActualFiveBounds(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s,marked=true)
            fun setup(range:LumenSamplingRangeMode){s.onActivity {a->
                a.binding.update(LumenSurfaceOptions(radiusDp=0f,tintEnabled=false,edgeEnabled=false,sampling=LumenSurfaceSampling(blurEnabled=false,refractionStrength=2f,softwareScale=1f,maxSoftwarePixels=96_000)))
                a.binding.updateEnhancements(LumenSurfaceEnhancements(geometry=LumenSurfaceGeometryOptions(cornersEnabled=true,corners=LumenSurfaceCorners(0f,0f,0f,0f)),
                    material=LumenMaterialRecipeOptions(chromaticStrength=0f),quality=LumenSurfaceQualityOptions(maxBitmapPixels=96_000),debug=LumenSurfaceDebugOptions(samplingRange=range)))
            };gpu(s)}
            setup(LumenSamplingRangeMode.SAFE_INTERIOR);val safe=image(s)
            setup(LumenSamplingRangeMode.PADDED_EXPERIMENTAL);val padded=image(s)
            assertTrue(Color.blue(safe.getPixel(1,80))>180);assertTrue(Color.red(padded.getPixel(1,80))>180)
            s.onActivity {a->
                val r=a.binding.samplingRegions()!!
                assertEquals(LumenRegionSpace.SOURCE_LOCAL,r.recordedBounds.space)
                assertEquals(LumenRegionSpace.EXECUTION_PIXELS,r.materializedBounds.space)
                assertTrue(r.requiredSampleBounds.left<r.shapeBounds.left)
                assertTrue(r.materializedBounds.right>224f);assertEquals(224f,r.outputClip.right,0f)
                assertNotNull(r.estimatedSourceAgeNanos)
            }
            safe.recycle();padded.recycle()
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun rotatingTheSurfaceTransformsItsLightingNormal(){
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {s->
            scene(s);s.onActivity {a->
                a.binding.update(LumenSurfaceOptions(radiusDp=0f,tintEnabled=false,edgeEnabled=false,sampling=LumenSurfaceSampling(blurEnabled=false,refractionEnabled=false)))
                a.binding.updateEnhancements(LumenSurfaceEnhancements(light=LumenSurfaceLightOptions(enabled=true,intensity=3f,specularStrength=0f,transformNormals=true)))
                a.binding.setLightDirection(1f,0f,.65f)
            };gpu(s)
            fun edgePixel():Int {
                val latch=CountDownLatch(1);var b:Bitmap?=null;var x=0;var y=0;var result=-1
                s.onActivity {a->
                    val m=android.graphics.Matrix();a.glass.transformMatrixToGlobal(m);val point=floatArrayOf(222f,80f);m.mapPoints(point)
                    val origin=IntArray(2);a.window.decorView.getLocationOnScreen(origin);x=(point[0]-origin[0]).toInt();y=(point[1]-origin[1]).toInt()
                    b=Bitmap.createBitmap(a.window.decorView.width,a.window.decorView.height,Bitmap.Config.ARGB_8888)
                    PixelCopy.request(a.window,b!!,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))
                }
                assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
                return Color.red(b!!.getPixel(x,y)).also {b!!.recycle()}
            }
            val original=edgePixel()
            s.onActivity {it.glass.rotation=180f;it.session.notifyPositionChanged()};SystemClock.sleep(200)
            val rotated=edgePixel();assertTrue("Lighting normal did not rotate: $original / $rotated",original>rotated+10)
        }
    }
}
