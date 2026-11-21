package ru.yandex.buggyweatherapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.yandex.buggyweatherapp.ui.screens.WeatherScreen
import ru.yandex.buggyweatherapp.ui.theme.BuggyWeatherAppTheme
import ru.yandex.buggyweatherapp.viewmodel.WeatherViewModel

/**
 * Исправлено:
 * 1. Результат разрешения на запрос локации никак не обрабатывался, запрос локации мог произойти
 * до результата и без разрешения. Исправлено переносом инициализации ViewModel в колбэк запроса.
 * 2. ViewModel создавался на каждый экземпляр Activity. В результате при повороте экрана мы теряли
 * уже загруженные данные. Исправлено созданием ViewModel с помощью by viewModels(), сохраняя экземпляр
 * во ViewModel Store.
 * 3. Таймер продолжал запускать запросы при свёрнутом приложении. Теперь автообновление
 * запускается в onStart() и останавливается в onStop().
 * */
class MainActivity : ComponentActivity() {
    
    private val weatherViewModel: WeatherViewModel by viewModels {
        viewModelFactory { 
            initializer {
                WeatherViewModel(application)
            }
        }
    }
    
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                weatherViewModel.initialize(true)
            }
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                weatherViewModel.initialize(true)
            }
            else -> {
                weatherViewModel.initialize(false)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFineLocation && !hasCoarseLocation) {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            weatherViewModel.initialize(true)
        }
        
        enableEdgeToEdge()
        
        setContent {
            BuggyWeatherAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WeatherScreen(
                        viewModel = weatherViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    
    override fun onStart() {
        super.onStart()
        weatherViewModel.startAutoRefresh()
    }

    override fun onStop() {
        weatherViewModel.stopAutoRefresh()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        
    }
}

@Preview(showBackground = true)
@Composable
fun WeatherAppPreview() {
    BuggyWeatherAppTheme {
        
        Text("Weather App Preview")
    }
}