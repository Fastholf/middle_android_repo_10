package ru.yandex.buggyweatherapp.repository

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import ru.yandex.buggyweatherapp.model.Location
import ru.yandex.buggyweatherapp.utils.LocationTracker
import java.util.Locale
import java.util.concurrent.Executors

/** 
 * Исправлено:
 * 1. getCityNameFromLocation вызывался на UI потоке и вызывал потенциально блокирующую операцию
 * Geocoder.getFromLocation() на нём. Исправлено переносом вызова на фоновый поток.
 * 2. getCurrentLocation не отменял подписку на обновления локации, после первого успеха, что могло
 * привести к нескольким загрузкам или зависанию. Согласно рекомендациям от Google такой подход стоит
 * использовать только если он реально нужен, а здесь нам нужен просто одноразовый запрос:
 * https://developer.android.com/develop/sensors-and-location/location/retrieve-current?hl=en#BestEstimate
 * Исправлено использованием CurrentLocationRequest с конкретными параметрами для проверки кэша и 
 * запроса нового результата.
 */
class LocationRepository(
    
    private val context: Context
) {
    companion object {
        private val geocoderExecutor = Executors.newSingleThreadExecutor()
    }
    
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)
    
    
    private var currentLocation: Location? = null
    
    
    fun getCurrentLocation(callback: (Location?) -> Unit) {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000L)
            .setDurationMillis(20_000L)
            .build()

        val task = try {
            fusedLocationClient.getCurrentLocation(request, null)
        } catch (e: SecurityException) {
            Log.e("LocationRepository", "Location permission not granted", e)
            callback(null)
            return
        }

        task.addOnCompleteListener { completed ->
            val location = if (completed.isSuccessful) {
                completed.result?.let {
                    Location(latitude = it.latitude, longitude = it.longitude)
                }
            } else {
                completed.exception?.let {
                    Log.e("LocationRepository", "Error getting current location", it)
                }
                null
            }

            callback(location)
        }
    }
    
    
    fun getCityNameFromLocation(location: Location, onResult: (String?) -> Unit) {
        geocoderExecutor.execute {
            val name = try {

                val geocoder = Geocoder(context, Locale.getDefault())

                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    if (address.locality != null) {
                        address.locality
                    } else if (address.subAdminArea != null) {
                        address.subAdminArea
                    } else {
                        address.adminArea
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("LocationRepository", "Error getting city name", e)
                null
            }
            onResult(name)
        }
    }
    
    
    fun startLocationTracking() {
        LocationTracker.getInstance(context).startTracking()
    }
    
    
}