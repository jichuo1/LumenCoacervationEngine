package com.lumen.coacervation.engine.assets

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object AssetPlaybackPolicy {
    fun capabilitiesAllowed(value:LumenAssetMetadata,options:LumenAssetOptions):Boolean =
        (value.speedControl||options.speed==1f)&&(value.repeatControl||options.repeatCount==1)
    fun metadataAllowed(value:LumenAssetMetadata,options:LumenAssetOptions):Boolean =
        value.width in 1..8192&&value.height in 1..8192&&value.width.toLong()*value.height<=options.maximumRenderPixels&&
            (value.durationMs in 1..options.maximumDurationMs||value.durationMs==0L&&!value.seekable)&&value.decodedImagePixels in 0..options.maximumDecodedImagePixels.toLong()
    fun progress(elapsed:Long,duration:Long,options:LumenAssetOptions):Float {
        if(duration<=0)return 1f
        if(options.reduceMotion)return 0f
        val scaled=elapsed.coerceAtLeast(0L).toDouble()*options.speed
        if(scaled>=duration.toDouble()*options.repeatCount)return 1f
        return ((scaled%duration)/duration).toFloat()
    }
    fun finished(elapsed:Long,duration:Long,options:LumenAssetOptions):Boolean =
        elapsed>=options.maximumDurationMs||elapsed.toDouble()*options.speed>=duration.toDouble()*options.repeatCount
    fun nextDelayMillis(metadata:LumenAssetMetadata,elapsed:Long,options:LumenAssetOptions):Long{
        val duration=if(metadata.durationMs>0)metadata.durationMs else options.maximumDurationMs
        val end=minOf(options.maximumDurationMs,kotlin.math.ceil(duration.toDouble()*options.repeatCount/options.speed).toLong())
        val remaining=(end-elapsed).coerceAtLeast(1L)
        return if(metadata.nativeClock)remaining else minOf(remaining,(1000L/options.framesPerSecond).coerceAtLeast(16))
    }
    fun readBounded(input:InputStream,limit:Int):ByteArray {
        require(limit in 1024..16*1024*1024)
        val output=ByteArrayOutputStream(minOf(limit,8192));val buffer=ByteArray(8192);var total=0
        while(true){if(Thread.currentThread().isInterrupted)throw InterruptedException();val n=input.read(buffer);if(n<0)break;if(n==0)throw IllegalArgumentException("Asset stream stalled");if(n>limit-total)throw AssetTooLarge();output.write(buffer,0,n);total+=n}
        require(total>0);return output.toByteArray()
    }
}
internal class AssetTooLarge:IllegalArgumentException("Asset exceeds byte budget")
