package com.anilili.ui.home

/**
 * One row Home can draw. [key] is what is stored, so it must never change once shipped; the
 * enum's own name and order are free to.
 */
enum class HomeRow(val key: String, private val phoneLabel: String, private val tvLabel: String = phoneLabel) {
    CONTINUE("continue", "Continue Watching"),
    FOR_YOU("for_you", "For You", "For you"),
    BECAUSE("because", "Because you watched…"),
    NEWEST("newest", "New Episodes", "Latest releases"),
    TRENDING("trending", "Trending", "Trending now"),
    POPULAR("popular", "Popular This Season", "Popular this season"),
    TOP_RATED("top_rated", "Top Rated", "Top rated"),
    MOVIES("movies", "Movies"),
    UPCOMING("upcoming", "Upcoming"),
    TOP_BY_YEAR("top_by_year", "Top scored by year"),
    ;

    fun label(isTv: Boolean): String = if (isTv) tvLabel else phoneLabel

    companion object {
        fun fromKey(key: String): HomeRow? = entries.firstOrNull { it.key == key }
    }
}

/** The paged catalogue list behind a row, or null for the rows built from the viewer's own data. */
val HomeRow.catalogSection: HomeSection?
    get() = when (this) {
        HomeRow.NEWEST -> HomeSection.NEWEST
        HomeRow.TRENDING -> HomeSection.TRENDING
        HomeRow.POPULAR -> HomeSection.POPULAR
        HomeRow.TOP_RATED -> HomeSection.TOP_RATED
        HomeRow.MOVIES -> HomeSection.MOVIES
        HomeRow.UPCOMING -> HomeSection.UPCOMING
        HomeRow.CONTINUE, HomeRow.FOR_YOU, HomeRow.BECAUSE, HomeRow.TOP_BY_YEAR -> null
    }

/**
 * The viewer's Home: which rows, in what order.
 *
 * Stored as one string — `continue,for_you,-because,newest,…`, a leading `-` meaning hidden — in
 * the settings DataStore, so it travels with a TV profile and a backup like any other taste. Absent
 * means "never customised", which is not the same as "customised to today's default": a viewer who
 * never touched it should get tomorrow's default too, so the default is not written down.
 */
data class HomeLayout(val order: List<HomeRow>, val hidden: Set<HomeRow> = emptySet()) {
    val visible: List<HomeRow> get() = order.filterNot { it in hidden }

    /** The rows that will actually be drawn: shown, and with something in them right now. */
    fun present(hasContent: (HomeRow) -> Boolean): List<HomeRow> = visible.filter(hasContent)

    fun encode(): String = order.joinToString(",") { if (it in hidden) "-${it.key}" else it.key }

    fun move(row: HomeRow, to: Int): HomeLayout {
        val from = order.indexOf(row)
        if (from < 0) return this
        val target = to.coerceIn(0, order.lastIndex)
        if (target == from) return this
        val reordered = order.toMutableList().apply { removeAt(from); add(target, row) }
        return copy(order = reordered)
    }

    /**
     * [row] taken out and put back right after [anchor] (or right before it).
     *
     * What a drag on Home itself means. Home draws only the rows that are shown *and* have something
     * in them, so the row a finger passes is a neighbour on screen, not at an index of [order]: hidden
     * and empty rows can sit between the two, and they keep their places relative to the rest.
     */
    fun placeBeside(row: HomeRow, anchor: HomeRow, after: Boolean): HomeLayout {
        if (row == anchor || row !in order || anchor !in order) return this
        val reordered = order.toMutableList().apply {
            remove(row)
            add(indexOf(anchor) + if (after) 1 else 0, row)
        }
        return copy(order = reordered)
    }

    fun withHidden(row: HomeRow, hide: Boolean): HomeLayout =
        copy(hidden = if (hide) hidden + row else hidden - row)

    companion object {
        /** The phone's feed: what is out now first, then the catalogue's long-standing order. */
        private val PHONE_DEFAULT = listOf(
            HomeRow.CONTINUE, HomeRow.FOR_YOU, HomeRow.BECAUSE, HomeRow.NEWEST, HomeRow.TRENDING,
            HomeRow.POPULAR, HomeRow.TOP_RATED, HomeRow.MOVIES, HomeRow.UPCOMING,
        )

        /** Television leads with Trending, which its spotlight is drawn from. */
        private val TV_DEFAULT = listOf(
            HomeRow.CONTINUE, HomeRow.FOR_YOU, HomeRow.BECAUSE, HomeRow.TRENDING, HomeRow.NEWEST,
            HomeRow.POPULAR, HomeRow.MOVIES, HomeRow.TOP_RATED, HomeRow.UPCOMING, HomeRow.TOP_BY_YEAR,
        )

        fun default(isTv: Boolean): HomeLayout = HomeLayout(if (isTv) TV_DEFAULT else PHONE_DEFAULT)

        /**
         * The stored layout, or the default when there is none.
         *
         * Unknown keys (a row a later version removed) and repeats are dropped. A row the stored
         * layout does not mention — one added after the viewer customised — is put back where the
         * default has it, after the row that precedes it there, rather than lost or dumped at the
         * bottom: someone who moved Movies up should still meet a new row where it was designed to
         * sit.
         */
        fun decode(stored: String?, isTv: Boolean): HomeLayout {
            val default = default(isTv).order
            if (stored.isNullOrBlank()) return HomeLayout(default)
            val order = mutableListOf<HomeRow>()
            val hidden = mutableSetOf<HomeRow>()
            stored.split(',').map(String::trim).forEach { token ->
                val hide = token.startsWith("-")
                val row = HomeRow.fromKey(token.removePrefix("-")) ?: return@forEach
                if (row in order) return@forEach
                order += row
                if (hide) hidden += row
            }
            default.forEachIndexed { index, row ->
                if (row in order) return@forEachIndexed
                val before = default.subList(0, index).lastOrNull { it in order }
                order.add(if (before == null) 0 else order.indexOf(before) + 1, row)
            }
            return HomeLayout(order, hidden)
        }
    }
}
