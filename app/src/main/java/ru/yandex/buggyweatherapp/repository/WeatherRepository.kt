package ru.yandex.buggyweatherapp.repository

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import ru.yandex.buggyweatherapp.api.RetrofitInstance
import ru.yandex.buggyweatherapp.model.Location
import ru.yandex.buggyweatherapp.model.WeatherData

/** 
 * Исправлено:
 * 1. getWeatherData вызывал Call.execute() на главном потоке, что могло приводить к NetworkOnMainThreadException,
 * исправлено использованием Call.enqueue().
 * 2. "temp_min" и "temp_max" являются необязательными в протоколе. Код не учитывал этот нюанс и 
 * погода бы не отобразилась, если бы эти значения не пришли в ответе. Исправлено обработкой значений как
 * nullable и корректной обработкой их в UI.
 *  */
class WeatherRepository {
    
    
    private val weatherApi = RetrofitInstance.weatherApi
    
    
    private var cachedWeatherData: WeatherData? = null
    
    
    fun getWeatherData(location: Location, callback: (WeatherData?, Exception?) -> Unit) {
        
        val call = weatherApi.getCurrentWeather(location.latitude, location.longitude)
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful) {
                    try {
                        val weatherData = parseWeatherData(response.body()!!, location)
                        cachedWeatherData = weatherData
                        callback(weatherData, null)
                    } catch (e: Exception) {
                        Log.e("WeatherRepository", "Error fetching weather", e)
                        callback(null, e)
                    }
                } else {
                    callback(null, Exception("API Error: ${response.code()}"))
                }
            }

            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                callback(null, Exception(t))
            }
        })
    }
    
    fun getWeatherByCity(cityName: String, callback: (WeatherData?, Exception?) -> Unit) {
        weatherApi.getWeatherByCity(cityName).enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val json = response.body()!!
                        val location = extractLocationFromResponse(json)
                        val weatherData = parseWeatherData(json, location)
                        callback(weatherData, null)
                    } catch (e: Exception) {
                        
                        callback(null, e)
                    }
                } else {
                    callback(null, Exception("Error fetching weather data"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                callback(null, Exception(t))
            }
        })
    }
    
    
    private fun parseWeatherData(json: JsonObject, location: Location): WeatherData {
        
        val main = json.getAsJsonObject("main")
        val wind = json.getAsJsonObject("wind")
        val sys = json.getAsJsonObject("sys")
        val weather = json.getAsJsonArray("weather").get(0).asJsonObject
        val clouds = json.getAsJsonObject("clouds")

        fun JsonObject.getOrNull(memberName: String): JsonElement? {
            return if (has(memberName)) get(memberName) else null
        }
        return WeatherData(
            cityName = json.get("name").asString,
            country = sys.get("country").asString,
            temperature = main.get("temp").asDouble,
            feelsLike = main.get("feels_like").asDouble,
            minTemp = main.getOrNull("temp_min")?.asDouble,
            maxTemp = main.getOrNull("temp_max")?.asDouble,
            humidity = main.get("humidity").asInt,
            pressure = main.get("pressure").asInt,
            windSpeed = wind.get("speed").asDouble,
            windDirection = if (wind.has("deg")) wind.get("deg").asInt else 0,
            description = weather.get("description").asString,
            icon = weather.get("icon").asString,
            cloudiness = clouds.get("all").asInt,
            sunriseTime = sys.get("sunrise").asLong,
            sunsetTime = sys.get("sunset").asLong,
            timezone = json.get("timezone").asInt,
            timestamp = json.get("dt").asLong,
            rawApiData = json.toString(),
            rain = if (json.has("rain") && json.getAsJsonObject("rain").has("1h")) 
                    json.getAsJsonObject("rain").get("1h").asDouble else null,
            snow = if (json.has("snow") && json.getAsJsonObject("snow").has("1h"))
                    json.getAsJsonObject("snow").get("1h").asDouble else null,
            location = location
        )
    }
    
    private fun extractLocationFromResponse(json: JsonObject): Location {
        val coord = json.getAsJsonObject("coord")
        val lat = coord.get("lat").asDouble
        val lon = coord.get("lon").asDouble
        val name = json.get("name").asString
        
        return Location(lat, lon, name)
    }
}