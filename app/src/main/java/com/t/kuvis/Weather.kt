package com.t.kuvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WDay(val date: String, val code: Int, val max: Double, val min: Double, val rain: Int)
data class WInfo(val place: String, val temp: Double, val code: Int, val days: List<WDay>, val fetched: Long)

/** 날씨: Open-Meteo (무료, 키 필요 없음) */
object Weather {
    private const val GUNSAN_LAT = 35.9676
    private const val GUNSAN_LON = 126.7366

    fun desc(code: Int) = when (code) {
        0 -> "맑음"
        1, 2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "눈 소나기"
        in 95..99 -> "뇌우"
        else -> "날씨"
    }

    fun icon(code: Int) = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun cached(ctx: Context): WInfo? {
        val s = Prefs(ctx).p.getString("weather", null) ?: return null
        return try { fromJson(JSONObject(s)) } catch (_: Exception) { null }
    }

    /** 네트워크를 쓰므로 백그라운드에서 부를 것 */
    fun get(ctx: Context, maxAgeMs: Long = 30 * 60_000L): WInfo? {
        val c = cached(ctx)
        if (c != null && System.currentTimeMillis() - c.fetched < maxAgeMs) return c
        return try { fetch(ctx) } catch (_: Exception) { c }
    }

    private fun location(ctx: Context): Location? {
        val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            } catch (_: SecurityException) {} catch (_: IllegalArgumentException) {}
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, lat: Double, lon: Double): String {
        return try {
            if (!Geocoder.isPresent()) return "군산"
            val a = Geocoder(ctx, Locale.KOREAN).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return "군산"
            a.subLocality ?: a.thoroughfare ?: a.locality ?: a.subAdminArea ?: "군산"
        } catch (_: Exception) { "군산" }
    }

    private fun fetch(ctx: Context): WInfo {
        val loc = location(ctx)
        val lat = loc?.latitude ?: GUNSAN_LAT
        val lon = loc?.longitude ?: GUNSAN_LON
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=Asia%2FSeoul&forecast_days=5"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        val j = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            WDay(
                dates.getString(i),
                d.getJSONArray("weather_code").optInt(i),
                d.getJSONArray("temperature_2m_max").optDouble(i),
                d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_probability_max").optInt(i)
            )
        }
        val info = WInfo(placeName(ctx, lat, lon), cur.optDouble("temperature_2m"), cur.optInt("weather_code"),
            days, System.currentTimeMillis())
        Prefs(ctx).p.edit().putString("weather", toJson(info).toString()).apply()
        return info
    }

    /** 쿠비스가 읽을 요약 */
    fun summary(w: WInfo): String {
        val f = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
        val sb = StringBuilder("${w.place} 지금 ${w.temp.toInt()}도, ${desc(w.code)}.\n")
        for (d in w.days) {
            val ds = try { LocalDate.parse(d.date).format(f) } catch (_: Exception) { d.date }
            sb.append("$ds ${desc(d.code)}, 최고 ${d.max.toInt()}도 최저 ${d.min.toInt()}도, 강수확률 ${d.rain}%\n")
        }
        return sb.toString().trim()
    }

    private fun toJson(w: WInfo): JSONObject {
        val a = JSONArray()
        for (d in w.days) a.put(JSONObject().put("date", d.date).put("code", d.code).put("max", d.max).put("min", d.min).put("rain", d.rain))
        return JSONObject().put("place", w.place).put("temp", w.temp).put("code", w.code).put("days", a).put("fetched", w.fetched)
    }

    private fun fromJson(o: JSONObject): WInfo {
        val a = o.getJSONArray("days")
        val days = (0 until a.length()).map { i ->
            val d = a.getJSONObject(i)
            WDay(d.getString("date"), d.getInt("code"), d.getDouble("max"), d.getDouble("min"), d.getInt("rain"))
        }
        return WInfo(o.getString("place"), o.getDouble("temp"), o.getInt("code"), days, o.getLong("fetched"))
    }
}
