package com.example.schedule.sources

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

class AltstuScheduleSource {
    fun load(weekStart: LocalDate): List<Lesson> {
        // АлтГТУ возвращает HTTP 403 на запрос с User-Agent по умолчанию.
        val schedule = Jsoup.connect(GROUP_URL)
            .userAgent("Mozilla/5.0 ScheduleCompare")
            .timeout(15_000)
            .get()
        return parse(schedule, weekStart)
    }

    internal fun parse(schedule: Document, weekStart: LocalDate): List<Lesson> {
        val week = weekStart..weekStart.plusDays(6)
        val content = schedule.selectFirst("#content")
            ?: throw IOException("АлтГТУ: в ответе отсутствует раздел расписания")
        val days = content.select(".block-index")
        if (days.isEmpty()) {
            throw IOException("АлтГТУ: в разделе расписания отсутствуют даты занятий")
        }
        return days.flatMap { day ->
            val dayLabel = day.selectFirst("h2")?.text().orEmpty()
            val dateText = DATE_PREFIX.find(dayLabel)?.value
                ?: throw IOException("АлтГТУ: не удалось разобрать дату «$dayLabel»")
            val date = LocalDate.parse(dateText, DAY_FORMAT)
            if (date !in week) return@flatMap emptyList()

            day.select(".list-group-item").mapNotNull { row ->
                val subject = row.selectFirst("strong")?.text().orEmpty()
                if (subject.equals("Физическая культура и спорт", ignoreCase = true) ||
                    subject.equals("Иностранный язык", ignoreCase = true) ||
                    (subject.equals("Инженерная геодезия", ignoreCase = true) &&
                        GEODESY_LAB_SUBGROUP_B.containsMatchIn(row.text()))
                ) {
                    return@mapNotNull null
                }
                val rowText = row.ownText()
                val time = TIME_RANGE.find(rowText)
                    ?: throw IOException("АлтГТУ: не удалось разобрать время занятия за $date")
                val afterTime = rowText.substring(time.range.last + 1).trimStart()
                val type = LESSON_TYPE.find(afterTime)?.groupValues?.get(1)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val subgroup = row.select("nobr").firstNotNullOfOrNull { element ->
                    element.text().takeIf { it.startsWith("подгруппа ", ignoreCase = true) }
                }
                val displaySubject = if (subgroup != null && subject.isNotBlank()) {
                    "$subject ($subgroup)"
                } else {
                    subject
                }
                Lesson(
                    date = date,
                    startTime = LocalTime.parse(time.groupValues[1]),
                    endTime = LocalTime.parse(time.groupValues[2]),
                    subject = displaySubject,
                    university = UNIVERSITY,
                    type = type,
                )
            }
        }.distinct().sortedWith(compareBy(Lesson::date, Lesson::startTime))
    }

    companion object {
        const val UNIVERSITY = "АлтГТУ"
        private const val GROUP_URL = "https://www.altstu.ru/m/s/0300022502/"
        private val DATE_PREFIX = Regex("""^\d{2}\.\d{2}\.\d{2}\b""")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val TIME_RANGE = Regex("""^(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})\b""")
        private val LESSON_TYPE = Regex("""^\(([^,)]+)[,)]""")
        private val GEODESY_LAB_SUBGROUP_B =
            Regex("""\(\s*л\.\s*р\.\s*,\s*подгруппа\s+Б\s*\)""", RegexOption.IGNORE_CASE)
    }
}
