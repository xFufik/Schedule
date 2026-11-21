package com.example.schedule.sources

import java.time.LocalDate
import java.time.LocalTime

data class Lesson(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val subject: String,
    val university: String,
    val type: String? = null,
) {
    init {
        require(subject.isNotBlank()) { "В расписании отсутствует название предмета" }
        require(endTime.isAfter(startTime)) { "Некорректный интервал занятия: $startTime–$endTime" }
    }
}
