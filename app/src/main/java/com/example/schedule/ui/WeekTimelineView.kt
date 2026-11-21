package com.example.schedule.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import com.example.schedule.R
import com.example.schedule.sources.Lesson
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

class WeekTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private val dayNames = resources.getStringArray(R.array.schedule_days)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridDash = DashPathEffect(floatArrayOf(dp(3), dp(3)), 0f)
    private val ink = context.getColor(R.color.schedule_ink)
    private val textColor = context.getColor(R.color.schedule_text)
    private val rowColor = context.getColor(R.color.schedule_row)
    private val gridColor = context.getColor(R.color.schedule_grid)
    private val axisColor = context.getColor(R.color.schedule_axis)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val rowPadding = dp(8)
    private val pillHeight = dp(24)
    private val trackGap = dp(4)
    private val headerHeight = dp(38)
    private val rowGap = dp(4)
    private val sidePadding = dp(10)
    private var startMinute = 8 * 60
    private var endMinute = 20 * 60
    private var rows = List(7) { DayRow(emptyList(), 0) }

    init {
        setWillNotDraw(false)
    }

    internal fun showWeek(
        weekStart: LocalDate,
        yulia: List<Lesson>,
        nastya: List<Lesson>,
        onLessonClick: (Lesson) -> Unit,
    ) {
        removeAllViews()
        val yuliaByDate = yulia.groupBy(Lesson::date)
        val nastyaByDate = nastya.groupBy(Lesson::date)
        val lessons = yulia + nastya
        startMinute = minOf(8 * 60, (lessons.minOfOrNull { it.startTime.minuteOfDay() } ?: 480) / 120 * 120)
        endMinute = maxOf(20 * 60, ((lessons.maxOfOrNull { it.endTime.minuteOfDay() } ?: 1200) + 119) / 120 * 120)

        rows = dayNames.indices.map { day ->
            val date = weekStart.plusDays(day.toLong())
            val first = yuliaByDate[date].orEmpty()
            val second = nastyaByDate[date].orEmpty()
            val firstTracks = placeLessons(first, 0, R.color.schedule_yulia, onLessonClick)
            val secondTracks = placeLessons(
                second, firstTracks.second, R.color.schedule_nastya, onLessonClick,
            )
            DayRow(firstTracks.first + secondTracks.first, firstTracks.second + secondTracks.second)
        }
        requestLayout()
        invalidate()
    }

    private fun placeLessons(
        lessons: List<Lesson>,
        firstTrack: Int,
        color: Int,
        onLessonClick: (Lesson) -> Unit,
    ): Pair<List<Pill>, Int> {
        val trackEnds = mutableListOf<LocalTime>()
        val pills = lessons.sortedBy(Lesson::startTime).map { lesson ->
            val available = trackEnds.indexOfFirst { it <= lesson.startTime }
            val track = if (available >= 0) available else trackEnds.size
            if (track == trackEnds.size) trackEnds.add(lesson.endTime) else trackEnds[track] = lesson.endTime

            val label = lesson.timeLabel()
            val view = TextView(context).apply {
                typeface = medium
                setTextColor(context.getColor(R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                contentDescription = "${lesson.university}, ${lesson.date}, $label, ${lesson.subject}"
                background = GradientDrawable().apply {
                    setColor(context.getColor(color))
                    cornerRadius = pillHeight / 2
                }
                isFocusable = true
                setOnClickListener { onLessonClick(lesson) }
            }
            addView(view)
            Pill(view, lesson.startTime, lesson.endTime, label, firstTrack + track)
        }
        return pills to trackEnds.size
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rows.forEach { row ->
            row.pills.forEach { pill ->
                val widthPx = (x(pill.end.minuteOfDay(), width) - x(pill.start.minuteOfDay(), width))
                    .roundToInt().coerceAtLeast(1)
                val label = when {
                    pill.view.paint.measureText(pill.label) + dp(6) <= widthPx -> pill.label
                    pill.view.paint.measureText(pill.start.toString()) + dp(3) <= widthPx &&
                        pill.view.lineHeight * 2 <= pillHeight -> "${pill.start}\n${pill.end}"
                    else -> ""
                }
                if (pill.view.text.toString() != label) pill.view.text = label
                pill.view.measure(
                    MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(pillHeight.roundToInt(), MeasureSpec.EXACTLY),
                )
            }
        }
        val height = headerHeight + rows.sumOf { (rowHeight(it) + rowGap).toDouble() } + dp(14)
        setMeasuredDimension(width, resolveSize(height.roundToInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var rowTop = headerHeight
        for (row in rows) {
            for (pill in row.pills) {
                val x = x(pill.start.minuteOfDay(), width).roundToInt()
                val y = (rowTop + rowPadding + pill.track * (pillHeight + trackGap)).roundToInt()
                pill.view.layout(x, y, x + pill.view.measuredWidth, y + pill.view.measuredHeight)
            }
            rowTop += rowHeight(row) + rowGap
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var rowTop = headerHeight
        for ((index, row) in rows.withIndex()) {
            val rowBottom = rowTop + rowHeight(row)
            paint.color = rowColor
            canvas.drawRoundRect(sidePadding, rowTop, width - sidePadding, rowBottom, dp(9), dp(9), paint)
            drawCentered(
                canvas, dayNames[index], (sidePadding + x(startMinute, width)) / 2,
                (rowTop + rowBottom) / 2, 18f, textColor,
            )
            rowTop = rowBottom + rowGap
        }

        val scaleBottom = rowTop - rowGap
        val labelStep = if (width < dp(340) && endMinute - startMinute > 12 * 60) 240 else 120
        for (minute in startMinute..endMinute step 120) {
            val x = x(minute, width)
            paint.color = if (minute == startMinute) axisColor else gridColor
            paint.strokeWidth = dp(0.75f)
            paint.pathEffect = if (minute == startMinute) null else gridDash
            canvas.drawLine(x, headerHeight, x, scaleBottom, paint)
            paint.pathEffect = null
            paint.color = axisColor
            canvas.drawLine(x, headerHeight - dp(7), x, headerHeight, paint)
            if ((minute - startMinute) % labelStep == 0) {
                val label = "${(minute / 60).toString().padStart(2, '0')}:00"
                drawCentered(canvas, label, x, dp(15), 10f, ink)
            }
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, centerY: Float, size: Float, color: Int) {
        paint.color = color
        paint.typeface = medium
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
        canvas.drawText(text, x, centerY - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun rowHeight(row: DayRow): Float =
        maxOf(dp(37), rowPadding * 2 + row.trackCount * (pillHeight + trackGap) - trackGap)

    private fun x(minute: Int, width: Int): Float {
        val axisStart = maxOf(dp(55), width * 0.175f)
        return axisStart + (width - axisStart - dp(22)).coerceAtLeast(0f) *
            (minute - startMinute) / (endMinute - startMinute)
    }

    private fun dp(size: Int) = dp(size.toFloat())
    private fun dp(size: Float) = size * resources.displayMetrics.density
    private fun LocalTime.minuteOfDay() = hour * 60 + minute

    private data class DayRow(val pills: List<Pill>, val trackCount: Int)
    private data class Pill(
        val view: TextView,
        val start: LocalTime,
        val end: LocalTime,
        val label: String,
        val track: Int,
    )
}

internal fun Lesson.timeLabel() = "$startTime – $endTime"
