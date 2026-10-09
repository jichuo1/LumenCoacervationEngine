package com.lumen.coacervation.engine.host

import org.json.JSONArray
import org.json.JSONObject

/** Versioned metadata only. It contains neither source pixels nor paths/URIs. Unknown fields are ignored. */
public data class LumenEffectPreset(
    val surface: LumenSurfaceOptions=LumenSurfaceOptions(),
    val enhancements: LumenSurfaceEnhancements=LumenSurfaceEnhancements.DEFAULT,
    val seed: Int=1
) {
    public fun toJson(): String {
        val c=surface;val e=enhancements;val s=c.sampling
        val root=JSONObject().put("schema",1).put("seed",seed).put("colorAssumption","SRGB_ARGB8888_PREMULTIPLIED")
        root.put("surface",JSONObject().put("enabled",c.enabled).put("material",c.material.name).put("role",c.role.name)
            .put("radiusDp",c.radiusDp).put("opacity",c.opacity).put("color",c.color?:JSONObject.NULL)
            .put("tintEnabled",c.tintEnabled).put("tintOpacity",c.tintOpacity).put("fallbackTintOpacity",c.fallbackTintOpacity)
            .put("edgeEnabled",c.edgeEnabled).put("edgeWidthDp",c.edgeWidthDp).put("edgeIntensity",c.edgeIntensity).put("clipBackground",c.clipBackground)
            .put("edgeTopColor",c.edgeTopColor?:JSONObject.NULL).put("edgeBottomColor",c.edgeBottomColor?:JSONObject.NULL).put("backdropOpacity",c.backdropOpacity))
        root.put("sampling",JSONObject().put("enabled",s.enabled).put("backend",s.backend.name).put("blurEnabled",s.blurEnabled)
            .put("blurRadiusDp",s.blurRadiusDp).put("softwareBlurRadiusDp",s.softwareBlurRadiusDp?:JSONObject.NULL)
            .put("refractionEnabled",s.refractionEnabled).put("refractionStrength",s.refractionStrength)
            .put("minIntervalMs",s.minIntervalMs).put("softwareScale",s.softwareScale).put("maxSoftwarePixels",s.maxSoftwarePixels)
            .put("softwareFallback",s.softwareFallback).put("fadeEnabled",s.fadeEnabled).put("fadeHold",s.fadeHold).put("fadeEnd",s.fadeEnd).put("fadeDirection",s.fadeDirection.name).put("fadeCurve",s.fadeCurve.name))
        val g=e.geometry
        root.put("geometry",JSONObject().put("cornersEnabled",g.cornersEnabled).put("corners",JSONArray().put(g.corners.topLeft).put(g.corners.topRight).put(g.corners.bottomRight).put(g.corners.bottomLeft))
            .put("mirrorCornersInRtl",g.mirrorCornersInRtl).put("fusionEnabled",g.fusionEnabled).put("fusionRadiusDp",g.fusionRadiusDp)
            .put("antiAliasWidthDp",g.antiAliasWidthDp).put("shadowEnabled",g.shadowEnabled).put("shadowRadiusDp",g.shadowRadiusDp).put("shadowOpacity",g.shadowOpacity))
        val b=e.progressiveBlur
        root.put("progressiveBlur",JSONObject().put("enabled",b.enabled).put("weakRadiusDp",b.weakRadiusDp).put("strongRadiusDp",b.strongRadiusDp)
            .put("weakStart",b.weakStart).put("weakEnd",b.weakEnd).put("strongStart",b.strongStart).put("strongEnd",b.strongEnd)
            .put("direction",b.direction.name).put("strength",b.strength).put("maxBandHeightDp",b.maxBandHeightDp))
        val p=e.press
        root.put("press",JSONObject().put("enabled",p.enabled).put("displacementDp",p.displacementDp).put("radiusFraction",p.radiusFraction).put("highlightStrength",p.highlightStrength)
            .put("cancelOutside",p.cancelOutside).put("releaseDurationMs",p.releaseDurationMs).put("rippleEnabled",p.rippleEnabled).put("rippleAmplitudeDp",p.rippleAmplitudeDp)
            .put("rippleSpeedDpPerSecond",p.rippleSpeedDpPerSecond).put("rippleWidthDp",p.rippleWidthDp).put("rippleLifetimeMs",p.rippleLifetimeMs).put("maxRipples",p.maxRipples))
        val l=e.light
        root.put("light",JSONObject().put("enabled",l.enabled).put("angleDegrees",l.angleDegrees).put("altitude",l.altitude).put("intensity",l.intensity)
            .put("specularStrength",l.specularStrength).put("specularPower",l.specularPower).put("edgeWidthDp",l.edgeWidthDp).put("transformNormals",l.transformNormals)
            .put("gestureInfluence",l.gestureInfluence).put("smoothingTimeMs",l.smoothingTimeMs))
        val m=e.material
        root.put("recipe",JSONObject().put("intent",m.intent.name).put("normalizeBySize",m.normalizeBySize).put("maxEdgeFraction",m.maxEdgeFraction)
            .put("maxRefractionFraction",m.maxRefractionFraction).put("contrastFloor",m.contrastFloor).put("reduceTransparency",m.reduceTransparency)
            .put("reduceMotion",m.reduceMotion).put("chromaticStrength",m.chromaticStrength).put("saturation",m.saturation).put("useIntentDefaults",m.useIntentDefaults))
        val q=e.quality
        root.put("quality",JSONObject().put("enabled",q.enabled).put("mode",q.mode.name).put("adaptive",q.adaptive).put("lowerThreshold",q.lowerThreshold)
            .put("upperThreshold",q.upperThreshold).put("hysteresis",q.hysteresis).put("minimumDwellMs",q.minimumDwellMs).put("maxExecutionPixels",q.maxExecutionPixels)
            .put("maxBitmapPixels",q.maxBitmapPixels).put("bitmapIntervalMs",q.bitmapIntervalMs))
        val d=e.debug
        root.put("debug",JSONObject().put("countersEnabled",d.countersEnabled).put("timingEnabled",d.timingEnabled).put("drawSamplingBounds",d.drawSamplingBounds)
            .put("boundsLineWidthDp",d.boundsLineWidthDp).put("boundsOpacity",d.boundsOpacity).put("samplingRange",d.samplingRange.name))
        return root.toString()
    }
    public companion object {
        /** Reject unknown schemas/invalid known values. No partial configuration is applied. */
        public fun fromJson(text: String): LumenEffectPreset {
            LumenPresetInputPolicy.validate(text)
            val r=JSONObject(text);require(r.integer("schema",0)==1)
            if(r.has("colorAssumption"))require(r.get("colorAssumption")=="SRGB_ARGB8888_PREMULTIPLIED")
            val c=r.section("surface");val s=r.section("sampling")
            val g=r.section("geometry");val b=r.section("progressiveBlur")
            val p=r.section("press");val l=r.section("light")
            val m=r.section("recipe");val q=r.section("quality");val d=r.section("debug")
            val sd=LumenSurfaceSampling();val cd=LumenSurfaceOptions()
            val sampling=LumenSurfaceSampling(s.bool("enabled",sd.enabled),enumValue(s,"backend",sd.backend),s.bool("blurEnabled",sd.blurEnabled),s.number("blurRadiusDp",sd.blurRadiusDp),
                s.bool("refractionEnabled",sd.refractionEnabled),s.number("refractionStrength",sd.refractionStrength),s.long("minIntervalMs",sd.minIntervalMs),s.number("softwareScale",sd.softwareScale),
                s.integer("maxSoftwarePixels",sd.maxSoftwarePixels),s.bool("softwareFallback",sd.softwareFallback),s.bool("fadeEnabled",sd.fadeEnabled),s.number("fadeHold",sd.fadeHold),s.number("fadeEnd",sd.fadeEnd),enumValue(s,"fadeDirection",sd.fadeDirection),
                enumValue(s,"fadeCurve",sd.fadeCurve),if(s.has("softwareBlurRadiusDp")&&!s.isNull("softwareBlurRadiusDp"))s.number("softwareBlurRadiusDp",0f)else null)
            val color=if(c.has("color")&&!c.isNull("color"))c.integer("color",0)else null
            val surface=LumenSurfaceOptions(c.bool("enabled",cd.enabled),enumValue(c,"material",cd.material),enumValue(c,"role",cd.role),c.number("radiusDp",cd.radiusDp),c.number("opacity",cd.opacity),color,
                c.bool("tintEnabled",cd.tintEnabled),c.number("tintOpacity",cd.tintOpacity),c.number("fallbackTintOpacity",cd.fallbackTintOpacity),c.bool("edgeEnabled",cd.edgeEnabled),c.number("edgeWidthDp",cd.edgeWidthDp),c.number("edgeIntensity",cd.edgeIntensity),c.bool("clipBackground",cd.clipBackground),sampling,
                if(c.has("edgeTopColor")&&!c.isNull("edgeTopColor"))c.integer("edgeTopColor",0)else null,
                if(c.has("edgeBottomColor")&&!c.isNull("edgeBottomColor"))c.integer("edgeBottomColor",0)else null,c.number("backdropOpacity",cd.backdropOpacity))
            val gd=LumenSurfaceGeometryOptions();val corners=if(g.has("corners"))g.get("corners").also {require(it is JSONArray)} as JSONArray else null
            require(corners==null||corners.length()==4)
            fun corner(index: Int): Float {val value=corners!!.get(index);require(value is Number);return value.toFloat().also {require(it.isFinite())}}
            val gc=if(corners==null)gd.corners else LumenSurfaceCorners(corner(0),corner(1),corner(2),corner(3))
            val geometry=LumenSurfaceGeometryOptions(g.bool("cornersEnabled",gd.cornersEnabled),gc,g.bool("mirrorCornersInRtl",gd.mirrorCornersInRtl),g.bool("fusionEnabled",gd.fusionEnabled),g.number("fusionRadiusDp",gd.fusionRadiusDp),g.number("antiAliasWidthDp",gd.antiAliasWidthDp),g.bool("shadowEnabled",gd.shadowEnabled),g.number("shadowRadiusDp",gd.shadowRadiusDp),g.number("shadowOpacity",gd.shadowOpacity))
            val bd=LumenProgressiveBlurOptions()
            val blur=LumenProgressiveBlurOptions(b.bool("enabled",bd.enabled),b.number("weakRadiusDp",bd.weakRadiusDp),b.number("strongRadiusDp",bd.strongRadiusDp),b.number("weakStart",bd.weakStart),b.number("weakEnd",bd.weakEnd),b.number("strongStart",bd.strongStart),b.number("strongEnd",bd.strongEnd),enumValue(b,"direction",bd.direction),b.number("strength",bd.strength),b.number("maxBandHeightDp",bd.maxBandHeightDp))
            val pd=LumenLocalPressOptions()
            val press=LumenLocalPressOptions(p.bool("enabled",pd.enabled),p.number("displacementDp",pd.displacementDp),p.number("radiusFraction",pd.radiusFraction),p.number("highlightStrength",pd.highlightStrength),p.bool("cancelOutside",pd.cancelOutside),p.long("releaseDurationMs",pd.releaseDurationMs),p.bool("rippleEnabled",pd.rippleEnabled),p.number("rippleAmplitudeDp",pd.rippleAmplitudeDp),p.number("rippleSpeedDpPerSecond",pd.rippleSpeedDpPerSecond),p.number("rippleWidthDp",pd.rippleWidthDp),p.long("rippleLifetimeMs",pd.rippleLifetimeMs),p.integer("maxRipples",pd.maxRipples))
            val ld=LumenSurfaceLightOptions()
            val light=LumenSurfaceLightOptions(l.bool("enabled",ld.enabled),l.number("angleDegrees",ld.angleDegrees),l.number("altitude",ld.altitude),l.number("intensity",ld.intensity),l.number("specularStrength",ld.specularStrength),l.number("specularPower",ld.specularPower),l.number("edgeWidthDp",ld.edgeWidthDp),l.bool("transformNormals",ld.transformNormals),l.number("gestureInfluence",ld.gestureInfluence),l.long("smoothingTimeMs",ld.smoothingTimeMs))
            val md=LumenMaterialRecipeOptions()
            val recipe=LumenMaterialRecipeOptions(enumValue(m,"intent",md.intent),m.bool("normalizeBySize",md.normalizeBySize),m.number("maxEdgeFraction",md.maxEdgeFraction),m.number("maxRefractionFraction",md.maxRefractionFraction),m.number("contrastFloor",md.contrastFloor),m.bool("reduceTransparency",md.reduceTransparency),m.bool("reduceMotion",md.reduceMotion),m.number("chromaticStrength",md.chromaticStrength),m.number("saturation",md.saturation),m.bool("useIntentDefaults",md.useIntentDefaults))
            val qd=LumenSurfaceQualityOptions()
            val quality=LumenSurfaceQualityOptions(q.bool("enabled",qd.enabled),enumValue(q,"mode",qd.mode),q.bool("adaptive",qd.adaptive),q.number("lowerThreshold",qd.lowerThreshold),q.number("upperThreshold",qd.upperThreshold),q.number("hysteresis",qd.hysteresis),q.long("minimumDwellMs",qd.minimumDwellMs),q.integer("maxExecutionPixels",qd.maxExecutionPixels),q.integer("maxBitmapPixels",qd.maxBitmapPixels),q.long("bitmapIntervalMs",qd.bitmapIntervalMs))
            val dd=LumenSurfaceDebugOptions()
            val debug=LumenSurfaceDebugOptions(d.bool("countersEnabled",dd.countersEnabled),d.bool("timingEnabled",dd.timingEnabled),d.bool("drawSamplingBounds",dd.drawSamplingBounds),d.number("boundsLineWidthDp",dd.boundsLineWidthDp),d.number("boundsOpacity",dd.boundsOpacity),enumValue(d,"samplingRange",dd.samplingRange))
            return LumenEffectPreset(surface,LumenSurfaceEnhancements(geometry,blur,press,light,recipe,quality,debug),r.integer("seed",1))
        }
    }
}
private fun JSONObject.bool(key: String,default: Boolean): Boolean {if(!has(key))return default;val value=get(key);require(value is Boolean);return value}
private fun JSONObject.number(key: String,default: Float): Float {if(!has(key))return default;val value=get(key);require(value is Number);return value.toFloat().also {require(it.isFinite())}}
private fun JSONObject.section(key: String): JSONObject {if(!has(key))return JSONObject();return get(key).also {require(it is JSONObject)} as JSONObject}
private fun JSONObject.long(key: String,default: Long): Long {if(!has(key))return default;val value=get(key);require(value is Int||value is Long);return (value as Number).toLong()}
private fun JSONObject.integer(key: String,default: Int): Int = long(key,default.toLong()).also {require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()
private inline fun <reified T:Enum<T>> enumValue(json: JSONObject,key: String,default: T): T {if(!json.has(key))return default;val value=json.get(key);require(value is String);return enumValueOf(value)}
