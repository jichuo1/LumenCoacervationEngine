package com.lumen.coacervation.sample

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same-window paired measurements. Raw samples describe this emulator/device only. */
@RunWith(AndroidJUnit4::class)
class SurfaceBenchmarkTest {
    @get:org.junit.Rule val hardwareOutput=HardwareOutputRule()
    @Test fun compareFixedSceneWithLegacyAndEnhancedPlansAndPreserveRawSamples(){
        val output=JSONObject().put("schema",1).put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("model",android.os.Build.MODEL).put("sourceAgeAssumption","request-time estimate")
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use {scenario->
            val baseline=measure(scenario,LumenSurfaceEnhancements.DEFAULT)
            val enhanced=measure(scenario,LumenSurfaceEnhancements(progressiveBlur=LumenProgressiveBlurOptions(enabled=true),debug=LumenSurfaceDebugOptions(timingEnabled=true)))
            output.put("legacy",baseline).put("progressive",enhanced)
        }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(target.getExternalFilesDir(null),"lumen-surface-benchmark.json")
        file.writeText(output.toString(2))
        assertTrue(file.length()>100)
    }
    private fun measure(scenario:ActivityScenario<SurfaceSandboxActivity>,enhancements:LumenSurfaceEnhancements):JSONObject {
        val samples=ArrayList<Long>();var listener:Window.OnFrameMetricsAvailableListener?=null
        scenario.onActivity {activity->
            activity.binding.updateEnhancements(enhancements)
            listener=Window.OnFrameMetricsAvailableListener {_,metrics,_->samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))}
            activity.window.addOnFrameMetricsAvailableListener(listener!!,Handler(Looper.getMainLooper()))
        }
        repeat(15){scenario.onActivity {it.content.invalidate();it.session.notifyContentChanged()};SystemClock.sleep(20)}
        var before: LumenSurfacePerformance?=null
        scenario.onActivity {samples.clear();before=it.session.performanceDiagnostics()}
        repeat(90){index->scenario.onActivity {it.content.translationY=if(index%2==0)1f else 0f;it.content.invalidate();it.session.notifyContentChanged()};SystemClock.sleep(20)}
        val result=JSONObject()
        scenario.onActivity {activity->
            activity.window.removeOnFrameMetricsAvailableListener(listener!!)
            val raw=JSONArray();samples.forEach {raw.put(it)}
            val sorted=samples.sorted()
            assertTrue("FrameMetrics produced no measured frames",sorted.isNotEmpty())
            fun percentile(p:Double):Long=if(sorted.isEmpty())0 else sorted[((sorted.size-1)*p).toInt()]
            val counters=activity.session.performanceDiagnostics()
            result.put("parameters",JSONObject(LumenEffectPreset(enhancements=enhancements).toJson()))
                .put("rawTotalFrameNanos",raw).put("frameCount",samples.size).put("p50Nanos",percentile(.5)).put("p95Nanos",percentile(.95)).put("p99Nanos",percentile(.99))
                .put("contentRecordings",counters.contentRecordings-before!!.contentRecordings).put("proxyRecordings",counters.proxyRecordings-before!!.proxyRecordings)
                .put("effectChainBuilds",counters.effectChainBuilds-before!!.effectChainBuilds).put("runtimeShaderBuilds",counters.runtimeShaderBuilds-before!!.runtimeShaderBuilds)
                .put("softwareRequests",counters.softwareRequests-before!!.softwareRequests).put("softwareCompletions",counters.softwareCompletions-before!!.softwareCompletions)
                .put("activeSoftwareBytes",counters.activeSoftwareBytes)
            activity.content.translationY=0f
        }
        return result
    }
}
