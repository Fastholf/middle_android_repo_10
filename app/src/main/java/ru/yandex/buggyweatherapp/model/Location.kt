package ru.yandex.buggyweatherapp.model

/** Исправлено: Удалены ненужные перегрузки - для data class они будут сгенерированы компилятором.
 * Кроме того, был перегружен equals, а hashCode нет - они опирались на разный набор полей. */
data class Location(
    val latitude: Double,
    val longitude: Double,
    val name: String? = null
)
