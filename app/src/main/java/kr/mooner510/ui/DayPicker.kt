package kr.mooner510.ui

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvailableDayPickerDialog(
    availableDays: List<LocalDate>,
    initialDay: LocalDate,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val days = remember(availableDays) { availableDays.distinct().toSet() }
    if (days.isEmpty()) {
        onDismiss()
        return
    }

    val initial = remember(days, initialDay) {
        initialDay.takeIf { it in days }
            ?: days.minByOrNull { (it.toEpochDay() - initialDay.toEpochDay()).absoluteValue }
            ?: days.first()
    }
    val selectableDates = remember(days) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return day in days
            }

            override fun isSelectableYear(year: Int): Boolean = days.any { it.year == year }
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        initialDisplayedMonthMillis = initial.withDayOfMonth(1).toUtcMillis(),
        selectableDates = selectableDates,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    val selected = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (selected in days) onSelected(selected)
                },
            ) {
                Text("이동")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
