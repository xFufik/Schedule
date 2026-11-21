package com.example.schedule.sources

import android.content.res.Resources
import com.example.schedule.R
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AsuScheduleSource(private val resources: Resources) {
    fun load(weekStart: LocalDate): List<Lesson> {
        if (weekStart == OVP_WEEK) {
            return loadOvpSchedule()
        }
        val weekEnd = weekStart.plusDays(6)
        val url = "$GROUP_URL?date=${weekStart.format(URL_DATE)}-${weekEnd.format(URL_DATE)}"
        val session = Jsoup.newSession().timeout(15_000)
        val page = session.newRequest(url).get()
        val headerValue = page.select("script").firstNotNullOfOrNull { script ->
            REQUEST_ID.find(script.data())?.groupValues?.get(1)
        } ?: throw IOException("АлтГУ: на странице отсутствует идентификатор запроса расписания")

        // Страница запрашивает расписание отдельным GET в той же HTTP-сессии.
        val schedule = session.newRequest(page.location())
            .referrer(page.location())
            .header("X-CS-ID", headerValue)
            .get()
        return parse(schedule, weekStart)
    }

    private fun loadOvpSchedule(): List<Lesson> =
        resources.openRawResource(R.raw.asu_ovp_2026_11_23).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val (date, start, end, subject) = line.split('\t', limit = 4)
                Lesson(
                    date = LocalDate.parse(date),
                    startTime = LocalTime.parse(start),
                    endTime = LocalTime.parse(end),
                    subject = subject,
                    university = UNIVERSITY,
                )
            }.toList()
        }

    internal fun parse(schedule: Document, weekStart: LocalDate): List<Lesson> {
        val body = schedule.selectFirst(".schedule_table[data-type=students] .schedule_table-body")
            ?: throw IOException("АлтГУ: в ответе отсутствует таблица расписания")
        // В заголовке дня указан только день и месяц. Год берём из запрошенной недели.
        val weekDates = (0L..6L).map(weekStart::plusDays).associateBy { it.format(DAY_MONTH) }
        return body.select(".schedule_table-body-rows_group").flatMap { day ->
            val dayLabel = day.selectFirst(".schedule_table-date-dm")?.text()
            val date = weekDates[dayLabel]
                ?: throw IOException("АлтГУ: дата «$dayLabel» отсутствует в запрошенной неделе")
            day.select(".schedule_table-body-row:not(.schedule_table-body-row__dropdown)").mapNotNull { row ->
                val subject = row.selectFirst("[data-type=subject]")?.clone()
                val type = subject?.selectFirst(".schedule_table-badge")?.text()?.takeIf { it.isNotBlank() }
                val subgroup = subject?.selectFirst(".schedule_table-simple_badge")?.text().orEmpty()
                subject?.select(".schedule_table-badge")?.remove()
                if (type.equals("лаб.", ignoreCase = true) &&
                    (subgroup.isEmpty() || subgroup.equals("а)", ignoreCase = true)) &&
                    subject?.ownText().equals("Иностранный язык", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                val time = row.selectFirst("[data-type=time]")?.text().orEmpty()
                val times = time.split('-').map(String::trim)
                if (times.size != 2) {
                    throw IOException("АлтГУ: не удалось разобрать время «$time» за $date")
                }
                Lesson(
                    date = date,
                    startTime = LocalTime.parse(times[0]),
                    endTime = LocalTime.parse(times[1]),
                    subject = subject?.text().orEmpty(),
                    university = UNIVERSITY,
                    type = type,
                )
            }
        }.distinct().sortedWith(compareBy(Lesson::date, Lesson::startTime))
    }

    companion object {
        const val UNIVERSITY = "АлтГУ"
        private val OVP_WEEK = LocalDate.of(2026, 11, 23)
        private const val GROUP_URL = "https://www.asu.ru/timetable/students/8/2129442024/"
        private val REQUEST_ID = Regex("""setRequestHeader\s*\(\s*['"]X-CS-ID['"]\s*,\s*['"]([a-fA-F0-9]+)['"]""")
        private val URL_DATE = DateTimeFormatter.BASIC_ISO_DATE
        private val DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT)
    }
}
