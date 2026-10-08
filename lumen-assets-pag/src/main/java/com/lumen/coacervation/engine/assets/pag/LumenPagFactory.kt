package com.lumen.coacervation.engine.assets.pag

import android.content.Context
import android.view.View
import android.view.TextureView
import android.graphics.SurfaceTexture
import com.lumen.coacervation.engine.assets.*
import org.libpag.PAGFile
import org.libpag.PAGScaleMode
import org.libpag.PAGPlayer
import org.libpag.PAGSurface

/** Native PAG parser is restricted to bounded, host-trusted bytes; disk cache and automatic playback stay off. */
public class LumenPagFactory:LumenAssetFactory {
    override val format=LumenAssetFormat.PAG
    override fun decode(bytes:ByteArray,selection:LumenAssetSelection,options:LumenAssetOptions):LumenDecodedAsset {
        val file=PAGFile.Load(bytes)?:throw IllegalArgumentException("Invalid PAG")
        if(file.width().toLong()*file.height()>options.maximumRenderPixels||file.duration()/1000>options.maximumDurationMs)throw LumenAssetException(LumenAssetFailure.BUDGET)
        if(!options.videoEnabled&&file.numVideos()>0)throw LumenAssetException(LumenAssetFailure.UNSUPPORTED_OPERATION)
        return Decoded(file)
    }
    override fun attach(context:Context,asset:LumenDecodedAsset,options:LumenAssetOptions):LumenAssetPlayer=Player(context,asset as Decoded,options)
    private class Decoded(file:PAGFile):LumenDecodedAsset {
        private var owned:PAGFile?=file
        val file:PAGFile get()=checkNotNull(owned)
        override val metadata=LumenAssetMetadata(LumenAssetFormat.PAG,file.width(),file.height(),file.duration()/1000,true)
        // PAGFile exposes no release API. Drop this owner after the explicitly released player/surface.
        override fun close(){owned=null}
    }
    private class Player(context:Context,private val asset:Decoded,options:LumenAssetOptions):LumenAssetPlayer,TextureView.SurfaceTextureListener {
        private val widget=TextureView(context);private val renderer=PAGPlayer();private var surface:PAGSurface?=null;private var closed=false;private var progress=0f
        private var failure:LumenAssetPlayerFailureListener?=null
        override fun setOnFailure(listener:LumenAssetPlayerFailureListener?){failure=listener}
        private inline fun callback(action:()->Unit){if(closed)return;try{action()}catch(_:LinkageError){failure?.onFailure(LumenAssetFailure.UNSUPPORTED_ABI)}catch(_:Exception){failure?.onFailure(LumenAssetFailure.RENDERER)}}
        override val view:View get()=widget
        override val metadata get()=asset.metadata
        init{widget.isOpaque=false;widget.surfaceTextureListener=this;renderer.setComposition(asset.file);renderer.setCacheEnabled(false);renderer.setUseDiskCache(false);update(options)}
        override fun update(options:LumenAssetOptions){if(closed)return;renderer.setMaxFrameRate(options.framesPerSecond.toFloat());renderer.setVideoEnabled(options.videoEnabled)
            renderer.setScaleMode(when(options.fit){LumenAssetFit.CONTAIN->PAGScaleMode.LetterBox;LumenAssetFit.COVER->PAGScaleMode.Zoom;LumenAssetFit.FILL->PAGScaleMode.Stretch})}
        override fun render(progress:Float):Boolean{if(closed)return false;this.progress=progress;renderer.setProgress(progress.toDouble());return surface!=null&&renderer.flush()}
        override fun setPlaying(playing:Boolean)=Unit
        override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int)=callback{surface=PAGSurface.FromSurfaceTexture(texture);renderer.setSurface(surface);render(progress)}
        override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int)=callback{surface?.updateSize();render(progress)}
        override fun onSurfaceTextureUpdated(texture:SurfaceTexture)=Unit
        override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean{callback{renderer.setSurface(null);surface?.release();surface=null};return true}
        override fun close(){if(closed)return;closed=true;failure=null;widget.surfaceTextureListener=null
            try{renderer.setSurface(null)}finally{try{surface?.release()}finally{surface=null;try{renderer.setComposition(null)}finally{renderer.release()}}}}
    }
}
