package com.lumen.coacervation.engine.assets.lottie

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.ImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.lumen.coacervation.engine.assets.*

/** JSON and embedded raster only; no URL/disk/network image or font resolver is installed. */
public class LumenLottieFactory:LumenAssetFactory {
    override val format=LumenAssetFormat.LOTTIE
    override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset {
        val result=LottieCompositionFactory.fromJsonInputStreamSync(bytes.inputStream(),null)
        val composition=result.value?:throw IllegalArgumentException("Invalid Lottie composition")
        if(composition.bounds.width().toLong()*composition.bounds.height()>options.maximumRenderPixels||composition.duration>options.maximumDurationMs)throw LumenAssetException(LumenAssetFailure.BUDGET)
        var pixels=0L
        for(image in composition.images.values){
            val name=image.fileName
            require(name.startsWith("data:image/")&&name.contains(";base64,")){"External images require an explicit host resolver"}
            val delimiter=name.indexOf(',');require(delimiter>0)
            val encoded=Base64.decode(name.substring(delimiter+1),Base64.DEFAULT)
            val header=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(encoded,0,encoded.size,header)
            require(header.outWidth>0&&header.outHeight>0)
            pixels+=header.outWidth.toLong()*header.outHeight
            if(pixels>options.maximumDecodedImagePixels)throw LumenAssetException(LumenAssetFailure.BUDGET)
            image.bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.size)?:throw IllegalArgumentException("Invalid embedded image")
        }
        require(composition.fonts.isEmpty()){ "External fonts require an explicit host resolver" }
        return Decoded(composition,pixels)
    }
    override fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer=Player(context,asset as Decoded,options)
    private class Decoded(val composition:LottieComposition,pixels:Long):LumenDecodedAsset {
        override val metadata=LumenAssetMetadata(LumenAssetFormat.LOTTIE,composition.bounds.width(),composition.bounds.height(),composition.duration.toLong(),true,pixels)
        override fun close(){for(image in composition.images.values)image.bitmap=null}
    }
    private class Player(context:Context,private val asset:Decoded,options:LumenAssetOptions):LumenAssetPlayer {
        private val widget=LottieAnimationView(context);private var closed=false
        override val view:android.view.View get()=widget
        override val metadata get()=asset.metadata
        init{widget.setComposition(asset.composition);update(options)}
        override fun update(options:LumenAssetOptions){if(closed)return;widget.scaleType=when(options.fit){LumenAssetFit.CONTAIN->ImageView.ScaleType.FIT_CENTER;LumenAssetFit.COVER->ImageView.ScaleType.CENTER_CROP;LumenAssetFit.FILL->ImageView.ScaleType.FIT_XY}}
        override fun render(progress:Float):Boolean{if(closed)return false;widget.progress=progress;return true}
        override fun setPlaying(playing:Boolean){if(!closed)widget.pauseAnimation()}
        override fun close(){if(closed)return;closed=true;widget.cancelAnimation();widget.setImageDrawable(null)}
    }
}
