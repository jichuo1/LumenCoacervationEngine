package com.lumen.coacervation.engine.assets

import org.junit.Assert.*
import org.junit.Test

class AssetPlaybackPolicyTest {
    @Test fun streamsHaveHardLimitsAndEmptyInputIsRejected(){
        assertEquals(1024,AssetPlaybackPolicy.readBounded(ByteArray(1024).inputStream(),1024).size)
        assertThrows(AssetTooLarge::class.java){AssetPlaybackPolicy.readBounded(ByteArray(1025).inputStream(),1024)}
        assertThrows(IllegalArgumentException::class.java){AssetPlaybackPolicy.readBounded(ByteArray(0).inputStream(),1024)}
    }
    @Test fun timelineStopsAtFiniteRepeatCountAndSupportsSpeed(){
        val c=LumenAssetOptions(repeatCount=2,speed=2f)
        assertEquals(.5f,AssetPlaybackPolicy.progress(250,1000,c),0f)
        assertEquals(1f,AssetPlaybackPolicy.progress(1000,1000,c),0f);assertTrue(AssetPlaybackPolicy.finished(1000,1000,c))
        assertEquals(0f,AssetPlaybackPolicy.progress(1000,1000,c.copy(reduceMotion=true)),0f)
    }
    @Test fun dimensionsDurationAndDecodedImagesAreSeparateBudgets(){
        val c=LumenAssetOptions();val m=LumenAssetMetadata(LumenAssetFormat.LOTTIE,100,100,1000,true)
        assertTrue(AssetPlaybackPolicy.metadataAllowed(m,c))
        assertFalse(AssetPlaybackPolicy.metadataAllowed(m.copy(width=8193),c))
        assertFalse(AssetPlaybackPolicy.metadataAllowed(m.copy(durationMs=60_001),c))
        assertFalse(AssetPlaybackPolicy.metadataAllowed(m.copy(decodedImagePixels=4_194_305),c))
    }
    @Test fun durationLimitCapsRepeatsAndNativeClockDoesNotPretendToControlFrameRate(){
        val c=LumenAssetOptions(maximumDurationMs=1000,repeatCount=10)
        assertTrue(AssetPlaybackPolicy.finished(1000,900,c))
        val native=LumenAssetMetadata(LumenAssetFormat.RIVE,100,100,1000,false,nativeClock=true,speedControl=false,repeatControl=false,frameRateControl=false)
        assertTrue(AssetPlaybackPolicy.capabilitiesAllowed(native,LumenAssetOptions(framesPerSecond=10)))
        assertFalse(AssetPlaybackPolicy.capabilitiesAllowed(native,LumenAssetOptions(speed=2f)))
    }
    @Test fun nativeClockUsesOneDeadlineAndLowSubmissionRatesDoNotDelayTheEnd(){
        val c=LumenAssetOptions(framesPerSecond=1,maximumDurationMs=1000)
        val native=LumenAssetMetadata(LumenAssetFormat.RIVE,100,100,0,false,nativeClock=true)
        assertEquals(1000L,AssetPlaybackPolicy.nextDelayMillis(native,0,c))
        assertEquals(800L,AssetPlaybackPolicy.nextDelayMillis(native,200,c))
        assertEquals(100L,AssetPlaybackPolicy.nextDelayMillis(native,0,c.copy(maximumDurationMs=100)))
        assertEquals(100L,AssetPlaybackPolicy.nextDelayMillis(native.copy(nativeClock=false),900,c))
    }
}
