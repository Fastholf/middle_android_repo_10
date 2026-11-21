package ru.yandex.buggyweatherapp.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.yandex.buggyweatherapp.model.Location
import ru.yandex.buggyweatherapp.model.WeatherData
import ru.yandex.buggyweatherapp.repository.LocationRepository
import ru.yandex.buggyweatherapp.repository.WeatherRepository
import ru.yandex.buggyweatherapp.utils.ImageLoader
import java.util.Timer
import java.util.TimerTask

/**
 * Исправлено:
 * 1. Операция из refreshTimer выполнялась на бэкграунд потоке и при этом пыталась записать в 
 * LiveData, исправлено переносом операции на UI поток с помощью Handler.post()
 * 2. Запись напрямую String? в cityName: MutableLiveData<String>() могло привести к NPE в Compose.
 * Исправлено записью пустой строки, вместо null - Compose в текущей реализации нормально обрабатывает
 * пустую строку.
 * 3. Были возможны одновременные запросы, в результате чего результат более нового запроса мог быть
 * отменён запоздавшим результатом более старого. Исправлено введением айдишника для текущещего запроса.
 * 4. Поиск по городу сохранял нулевые координаты для текущеё геолокации, хотя репозиторий парсил их из ответа.
 * Исправлено добавлением локации в ответ от репозитория и выставлением корректной локации в обработчике.
 * 5. cityName выставлялся до успешного запроса погоды. В результате могла возникнуть ситуация, когда 
 * мы запросили город, выставили его, запросили погоду, а её получить не удалось. Тогда город будет
 * новый, а погода от старого города. Исправлено выставлением имени города на успехе запроса погоды.
 * 6. ViewModel сохранял Activity's Context. При этом пока ViewModel создавался прямо внутри Activity
 * это не было большой проблемой. Но мы переделали это на хранение в ViewModel Store и Activity Context
 * начал утекать. Исправлено использованием Application Context.
 * 7. Таймер автообновления продолжал работать после очистки ViewModel, поэтому при повторном 
 * открытии экрана могли работать два таймера. Исправлено отменой текущего таймера в onCleared() 
 * и перед созданием нового.
 * 8. toggleFavorite() не приводил к публикации изменения для Compose. Потому что сам объект не менялся,
 * менялось его изменяемоу содержимое. Исправлено изменение поля на val и публикацией копии объекта.
 * 9. Ручное обновление запрашивало геолокацию и могло переключить карточку на другой город.
 * Исправлено обновлением по координатам открытой карточки. Для геолокации добавлена отдельная кнопка.
 * 10. Типы LiveData не согласуются с тем, как они используются. Исправлено указание nullable там, где
 * LiveData используется как nullable
 * */
class WeatherViewModel(private val application: Application) : ViewModel() {
    private val weatherRepository = WeatherRepository()
    private val locationRepository by lazy { 
        LocationRepository(application)
    }
    
    
    val weatherData = MutableLiveData<WeatherData?>()
    val currentLocation = MutableLiveData<Location?>()
    val isLoading = MutableLiveData<Boolean>()
    val error = MutableLiveData<String?>()
    val cityName = MutableLiveData<String>()
    
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var hasLocationPermission = false
    
    private var refreshTimer: Timer? = null
    
    @Volatile
    private var currentRequestId: Int? = null
    private var nextRequestId = 0
    
    fun initialize(locationPermission: Boolean) {
        val shouldFetchLocation = locationPermission && !hasLocationPermission
        hasLocationPermission = locationPermission
        if (shouldFetchLocation) fetchCurrentLocationWeather()
        
        
        if (refreshTimer == null) startAutoRefresh()
    }
    
    
    fun fetchCurrentLocationWeather() {
        if (!hasLocationPermission) return
        isLoading.value = true
        error.value = null

        val id = nextRequestId++
        currentRequestId = id
        locationRepository.getCurrentLocation { location ->
            if (id != currentRequestId) return@getCurrentLocation
            if (location != null) {
                currentLocation.value = location
                
                
                locationRepository.getCityNameFromLocation(location) { cityNameFromLocation->
                    mainHandler.post {
                        if (id != currentRequestId) return@post
                        val namedLocation = location.copy(name = cityNameFromLocation)
                        currentLocation.value = namedLocation
                        getWeatherForLocation(namedLocation, id)
                    }
                }
            } else {
                isLoading.value = false
                error.value = "Unable to get current location"
                currentRequestId = null
            }
        }
    }
    
    fun refreshDisplayedWeather() {
        val location = weatherData.value?.location ?: return
        val id = nextRequestId++
        currentRequestId = id
        currentLocation.value = location
        getWeatherForLocation(location, id)
    }

    fun getWeatherForLocation(location: Location, requestId: Int) {
        isLoading.value = true
        error.value = null

        weatherRepository.getWeatherData(location) { data, exception ->
            Handler(Looper.getMainLooper()).post {
                if (requestId != currentRequestId) return@post
                isLoading.value = false
                
                if (data != null) {
                    weatherData.value = data
                    cityName.value = location.name.orEmpty()
                } else {
                    error.value = exception?.message ?: "Unknown error"
                }
                currentRequestId = null
            }
        }
    }
    
    fun searchWeatherByCity(city: String) {
        if (city.isBlank()) {
            error.value = "City name cannot be empty"
            return
        }
        
        isLoading.value = true
        error.value = null

        val id = nextRequestId++
        currentRequestId = id
        weatherRepository.getWeatherByCity(city) { data, exception ->
            if (id != currentRequestId) return@getWeatherByCity
            isLoading.value = false
            
            if (data != null) {
                weatherData.value = data
                cityName.value = data.cityName
                currentLocation.value = data.location
            } else {
                error.value = exception?.message ?: "Unknown error"
            }
            currentRequestId = null
        }
    }
    
    
    fun formatTemperature(temp: Double): String {
        return "${temp.toInt()}°C"
    }
    
    
    fun loadWeatherIcon(iconCode: String) {
        coroutineScope.launch {
            val iconUrl = "https://openweathermap.org/img/wn/$iconCode@2x.png"
            ImageLoader.loadImage(iconUrl)
        }
    }
    
    
    private fun startAutoRefresh() {
        refreshTimer?.cancel()
        refreshTimer = Timer()
        refreshTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                mainHandler.post {
                    if (currentRequestId == null) {
                        currentLocation.value?.let { location ->
                            val id = nextRequestId++
                            currentRequestId = id
                            getWeatherForLocation(location, id)
                        }
                    }
                }
            }
        }, 60000, 60000)
    }
    
    
    fun toggleFavorite() {
        weatherData.value?.let {
            weatherData.value = it.copy(isFavorite = !it.isFavorite)
        }
    }
    
    
    override fun onCleared() {
        super.onCleared()
        refreshTimer?.cancel()
    }
}
