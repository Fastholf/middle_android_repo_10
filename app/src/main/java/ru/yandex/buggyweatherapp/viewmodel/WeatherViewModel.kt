package ru.yandex.buggyweatherapp.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
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
 * 11. Задача в очереди Handler могла выполниться после остановки таймера. Теперь перед
 * обновлением задача проверяет, что её таймер ещё активен. В onCleared() сбрасывается
 * ID запроса, поэтому поздние ответы геокодера и сервера игнорируются.
 * 12. scheduleAtFixedRate() мог запускать пропущенные тики подряд после паузы процесса.
 * Используется schedule() с фиксированной задержкой между запусками.
 * 13. Экран мог напрямую менять состояние через MutableLiveData. Теперь он получает LiveData,
 * а запись в состояние и вызов метода с ID запроса остаются внутри ViewModel.
 * 14. После неудачного запроса по GPS таймер мог переключить карточку на другую локацию.
 * Автообновление и кнопка Refresh используют локацию последней успешно загруженной карточки.
 * */
class WeatherViewModel(private val application: Application) : ViewModel() {
    private val weatherRepository = WeatherRepository()
    private val locationRepository by lazy { 
        LocationRepository(application)
    }
    
    
    private val _weatherData = MutableLiveData<WeatherData?>()
    private val _isLoading = MutableLiveData<Boolean>()
    private val _error = MutableLiveData<String?>()
    private val _cityName = MutableLiveData<String>()

    val weatherData: LiveData<WeatherData?> = _weatherData
    val isLoading: LiveData<Boolean> = _isLoading
    val error: LiveData<String?> = _error
    val cityName: LiveData<String> = _cityName
    
    
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
    }
    
    
    fun fetchCurrentLocationWeather() {
        if (!hasLocationPermission) return
        _isLoading.value = true
        _error.value = null

        val id = nextRequestId++
        currentRequestId = id
        locationRepository.getCurrentLocation { location ->
            if (id != currentRequestId) return@getCurrentLocation
            if (location != null) {
                locationRepository.getCityNameFromLocation(location) { cityNameFromLocation->
                    mainHandler.post {
                        if (id != currentRequestId) return@post
                        val namedLocation = location.copy(name = cityNameFromLocation)
                        getWeatherForLocation(namedLocation, id)
                    }
                }
            } else {
                _isLoading.value = false
                _error.value = "Unable to get current location"
                currentRequestId = null
            }
        }
    }
    
    fun refreshDisplayedWeather() {
        val location = weatherData.value?.location ?: return
        val id = nextRequestId++
        currentRequestId = id
        getWeatherForLocation(location, id)
    }

    private fun getWeatherForLocation(location: Location, requestId: Int) {
        _isLoading.value = true
        _error.value = null

        weatherRepository.getWeatherData(location) { data, exception ->
            if (requestId != currentRequestId) return@getWeatherData
            _isLoading.value = false

            if (data != null) {
                _weatherData.value = data
                _cityName.value = location.name.orEmpty()
            } else {
                _error.value = exception?.message ?: "Unknown error"
            }
            currentRequestId = null
        }
    }
    
    fun searchWeatherByCity(city: String) {
        if (city.isBlank()) {
            _error.value = "City name cannot be empty"
            return
        }
        
        _isLoading.value = true
        _error.value = null

        val id = nextRequestId++
        currentRequestId = id
        weatherRepository.getWeatherByCity(city) { data, exception ->
            if (id != currentRequestId) return@getWeatherByCity
            _isLoading.value = false
            
            if (data != null) {
                _weatherData.value = data
                _cityName.value = data.cityName
            } else {
                _error.value = exception?.message ?: "Unknown error"
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
    
    
    fun startAutoRefresh() {
        stopAutoRefresh()
        val timer = Timer()
        refreshTimer = timer
        timer.schedule(object : TimerTask() {
            override fun run() {
                mainHandler.post {
                    if (refreshTimer !== timer) return@post
                    if (currentRequestId == null) {
                        refreshDisplayedWeather()
                    }
                }
            }
        }, 60000, 60000)
    }

    fun stopAutoRefresh() {
        refreshTimer?.cancel()
        refreshTimer = null
    }
    
    
    fun toggleFavorite() {
        weatherData.value?.let {
            _weatherData.value = it.copy(isFavorite = !it.isFavorite)
        }
    }
    
    
    override fun onCleared() {
        super.onCleared()
        currentRequestId = null
        stopAutoRefresh()
    }
}
