package com.lumen.coacervation.engine.effects

import org.json.JSONObject

/** Parameter-only schema. Validation reuses the core's bounded JSON envelope, then validates every known field. */
public data class LumenEffectPreset(val options:LumenEffectOptions=LumenEffectOptions()) {
    public fun toJson():String {
        val c=options;val p=c.procedural;val f=p.film;val g=p.paper;val e=p.energy;val b=c.particles
        return JSONObject().put("schema",1).put("colorAssumption","SRGB_ARGB8888_PREMULTIPLIED")
            .put("layer",JSONObject().put("seed",c.seed).put("framesPerSecond",c.framesPerSecond).put("maximumRenderPixels",c.maximumRenderPixels).put("reduceMotion",c.reduceMotion).put("pauseWhenUnfocused",c.pauseWhenUnfocused).put("clearOnPause",c.clearOnPause).put("clipToBounds",c.clipToBounds))
            .put("procedural",JSONObject().put("enabled",p.enabled).put("kind",p.kind.name).put("opacity",p.opacity).put("color",p.color).put("radiusDp",p.radiusDp).put("gpuEnabled",p.gpuEnabled))
            .put("film",JSONObject().put("thicknessNm",f.thicknessNm).put("iridescence",f.iridescence).put("roughness",f.roughness).put("angleDegrees",f.angleDegrees).put("intensity",f.intensity))
            .put("paper",JSONObject().put("grainEnabled",g.grainEnabled).put("grainCount",g.grainCount).put("grainSizeDp",g.grainSizeDp).put("contrast",g.contrast).put("relief",g.relief))
            .put("energy",JSONObject().put("durationMs",e.durationMs).put("speed",e.speed).put("bands",e.bands).put("wavelengthDp",e.wavelengthDp).put("intensity",e.intensity))
            .put("particles",JSONObject().put("enabled",b.enabled).put("shape",b.shape.name).put("maximumParticles",b.maximumParticles).put("burstCount",b.burstCount).put("lifetimeMs",b.lifetimeMs).put("speedDpPerSecond",b.speedDpPerSecond).put("gravityDpPerSecondSquared",b.gravityDpPerSecondSquared).put("radiusDp",b.radiusDp).put("spreadDegrees",b.spreadDegrees).put("directionDegrees",b.directionDegrees).put("trailLengthDp",b.trailLengthDp).put("opacity",b.opacity).put("color",b.color).put("maximumParticlePixels",b.maximumParticlePixels)).toString()
    }
    public companion object {
        public fun fromJson(text:String):LumenEffectPreset {
            // Core schema validator checks size/depth/root/type and colour assumptions; these extra groups are unknown core fields.
            com.lumen.coacervation.engine.host.LumenEffectPreset.fromJson(text)
            val root=JSONObject(text);fun section(key:String):JSONObject=if(root.has(key))root.get(key).also{require(it is JSONObject)}as JSONObject else JSONObject()
            val l=section("layer");val p=section("procedural");val f=section("film");val g=section("paper");val e=section("energy");val b=section("particles")
            val d=LumenEffectOptions();val pd=d.procedural;val fd=pd.film;val gd=pd.paper;val ed=pd.energy;val bd=d.particles
            val film=LumenFilmOptions(f.num("thicknessNm",fd.thicknessNm),f.num("iridescence",fd.iridescence),f.num("roughness",fd.roughness),f.num("angleDegrees",fd.angleDegrees),f.num("intensity",fd.intensity))
            val paper=LumenPaperOptions(g.bool("grainEnabled",gd.grainEnabled),g.int("grainCount",gd.grainCount),g.num("grainSizeDp",gd.grainSizeDp),g.num("contrast",gd.contrast),g.num("relief",gd.relief))
            val energy=LumenEnergyOptions(e.long("durationMs",ed.durationMs),e.num("speed",ed.speed),e.int("bands",ed.bands),e.num("wavelengthDp",ed.wavelengthDp),e.num("intensity",ed.intensity))
            val procedural=LumenProceduralOptions(p.bool("enabled",pd.enabled),p.enum("kind",pd.kind),p.num("opacity",pd.opacity),p.int("color",pd.color),p.num("radiusDp",pd.radiusDp),p.bool("gpuEnabled",pd.gpuEnabled),film,paper,energy)
            val particles=LumenParticleOptions(b.bool("enabled",bd.enabled),b.enum("shape",bd.shape),b.int("maximumParticles",bd.maximumParticles),b.int("burstCount",bd.burstCount),b.long("lifetimeMs",bd.lifetimeMs),b.num("speedDpPerSecond",bd.speedDpPerSecond),b.num("gravityDpPerSecondSquared",bd.gravityDpPerSecondSquared),b.num("radiusDp",bd.radiusDp),b.num("spreadDegrees",bd.spreadDegrees),b.num("directionDegrees",bd.directionDegrees),b.num("trailLengthDp",bd.trailLengthDp),b.num("opacity",bd.opacity),b.int("color",bd.color),b.int("maximumParticlePixels",bd.maximumParticlePixels))
            return LumenEffectPreset(LumenEffectOptions(procedural,particles,l.int("seed",d.seed),l.int("framesPerSecond",d.framesPerSecond),l.int("maximumRenderPixels",d.maximumRenderPixels),l.bool("reduceMotion",d.reduceMotion),l.bool("pauseWhenUnfocused",d.pauseWhenUnfocused),l.bool("clearOnPause",d.clearOnPause),l.bool("clipToBounds",d.clipToBounds)))
        }
    }
}
private fun JSONObject.bool(key:String,default:Boolean):Boolean=if(!has(key))default else get(key).also{require(it is Boolean)}as Boolean
private fun JSONObject.num(key:String,default:Float):Float=if(!has(key))default else (get(key).also{require(it is Number)}as Number).toFloat().also{require(it.isFinite())}
private fun JSONObject.long(key:String,default:Long):Long=if(!has(key))default else (get(key).also{require(it is Int||it is Long)}as Number).toLong()
private fun JSONObject.int(key:String,default:Int):Int=long(key,default.toLong()).also{require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()
private inline fun <reified T:Enum<T>> JSONObject.enum(key:String,default:T):T=if(!has(key))default else enumValueOf(get(key).also{require(it is String)}as String)
