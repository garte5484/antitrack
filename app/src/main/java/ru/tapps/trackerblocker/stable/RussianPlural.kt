package ru.tapps.trackerblocker.stable

/**
 * Русские плюральные формы без зависимости от системной локали:
 * Android выбирает категорию <plurals> по локали устройства, что ломает
 * русскую грамматику на не-русских системах, поэтому формы выбираем сами.
 *
 * Пример: 1 -> "трекер", 2 -> "трекера", 5 -> "трекеров", 21 -> "трекер".
 */
internal fun russianPlural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    return when {
        mod100 in 11..14 -> many
        count % 10 == 1 -> one
        count % 10 in 2..4 -> few
        else -> many
    }
}