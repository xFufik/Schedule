package com.example.schedule

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.schedule.sources.AltstuScheduleSource
import com.example.schedule.sources.AsuScheduleSource
import com.example.schedule.sources.Lesson
import com.example.schedule.ui.WeekTimelineView
import com.example.schedule.ui.timeLabel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {
    private val sources by lazy {
        listOf(
            ScheduleSource(R.string.yulia, AsuScheduleSource.UNIVERSITY, AsuScheduleSource(resources)::load),
            ScheduleSource(R.string.nastya, AltstuScheduleSource.UNIVERSITY, AltstuScheduleSource()::load),
        )
    }
    private val executor by lazy { Executors.newFixedThreadPool(sources.size) }
    private val pending = mutableListOf<Future<*>>()
    private val results = mutableMapOf<String, Result<List<Lesson>>>()
    private var requestId = 0

    private lateinit var weekStart: LocalDate
    private lateinit var weekLabel: TextView
    private lateinit var status: TextView
    private lateinit var timeline: WeekTimelineView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        weekLabel = findViewById(R.id.week)
        status = findViewById(R.id.status)
        timeline = findViewById(R.id.timeline)
        weekStart = savedInstanceState?.getString(WEEK_START)?.let(LocalDate::parse)
            ?: LocalDate.now(ZoneId.of("Asia/Barnaul"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        findViewById<View>(R.id.previous_week).setOnClickListener { loadWeek(weekStart.minusWeeks(1)) }
        findViewById<View>(R.id.next_week).setOnClickListener { loadWeek(weekStart.plusWeeks(1)) }
        findViewById<View>(R.id.refresh).setOnClickListener { loadWeek(weekStart) }
        loadWeek(weekStart)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(WEEK_START, weekStart.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        requestId++
        pending.forEach { it.cancel(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadWeek(start: LocalDate) {
        val id = ++requestId
        pending.forEach { it.cancel(true) }
        pending.clear()
        weekStart = start
        weekLabel.text = getString(
            R.string.schedule_week, start.format(WEEK_DATE), start.plusDays(6).format(WEEK_DATE),
        )
        results.clear()
        renderSchedule()
        for (source in sources) {
            pending += executor.submit {
                val result = fetchSchedule(source, start)
                runOnUiThread {
                    if (id == requestId) {
                        results[source.university] = result
                        renderSchedule()
                    }
                }
            }
        }
    }

    private fun fetchSchedule(source: ScheduleSource, start: LocalDate): Result<List<Lesson>> {
        return try {
            val lessons = source.load(start)
            Log.i(TAG, "${source.university}: неделя с $start, занятий: ${lessons.size}")
            lessons.forEach { Log.d(TAG, "${it.university} ${it.date} ${it.timeLabel()} ${it.subject}") }
            Result.success(lessons)
        } catch (error: Exception) {
            Log.e(TAG, "${source.university}: не удалось получить расписание на неделю с $start", error)
            Result.failure(error)
        }
    }

    private fun renderSchedule() {
        val yulia = results[AsuScheduleSource.UNIVERSITY]?.getOrNull().orEmpty()
        val nastya = results[AltstuScheduleSource.UNIVERSITY]?.getOrNull().orEmpty()
        timeline.showWeek(weekStart, yulia, nastya, ::showLesson)

        val messages = buildList {
            if (results.size < sources.size) add(getString(R.string.schedule_loading))
            if (results.size == sources.size && results.values.all { it.getOrNull()?.isEmpty() == true }) {
                add(getString(R.string.schedule_no_lessons))
            } else {
                for (source in sources) {
                    val result = results[source.university] ?: continue
                    val name = getString(source.name)
                    if (result.isFailure) {
                        add(getString(R.string.schedule_error_source, name))
                    } else if (result.getOrNull().orEmpty().isEmpty()) {
                        add(getString(R.string.schedule_empty_source, name))
                    }
                }
            }
        }
        status.text = messages.joinToString("\n")
        status.isVisible = messages.isNotEmpty()
    }

    private fun showLesson(lesson: Lesson) {
        val source = sources.first { it.university == lesson.university }
        val details = getString(
            R.string.schedule_lesson_details,
            getString(source.name), lesson.university, lesson.date.format(DETAIL_DATE), lesson.timeLabel(),
        )
        val type = lesson.type?.let { getString(R.string.schedule_lesson_type, it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(lesson.subject)
            .setMessage(listOfNotNull(details, type).joinToString("\n"))
            .setPositiveButton(R.string.schedule_close, null)
            .show()
    }

    private data class ScheduleSource(
        val name: Int,
        val university: String,
        val load: (LocalDate) -> List<Lesson>,
    )

    companion object {
        private const val TAG = "ScheduleCompare"
        private const val WEEK_START = "week_start"
        private val WEEK_DATE = DateTimeFormatter.ofPattern("dd.MM")
        private val DETAIL_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu")
    }
}
