package com.safad.bmsprobe

/**
 * Parses the two BookMyShow URLs a person can actually copy:
 *
 *   https://in.bookmyshow.com/cinemas/kochi/padma-cinema-kochi/buytickets/PMCE/20261217
 *   https://in.bookmyshow.com/movies/kochi/avengers-doomsday/ET00526329
 *
 * The cinema URL must include /buytickets/<CODE>; that code and the slug are all
 * the app needs to rebuild the page for any date.
 */
object BmsLinks {

    private val venueRe = Regex(
        """bookmyshow\.com/cinemas/([a-z0-9-]+)/([a-z0-9-]+)/buytickets/([a-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val movieRe = Regex(
        """bookmyshow\.com/movies/([a-z0-9-]+)/([a-z0-9-]+)/(ET\d{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val eventCodeRe = Regex("""\b(ET\d{6,})\b""", RegexOption.IGNORE_CASE)
    private val venueCodeRe = Regex("""^[A-Z0-9]{3,10}$""")

    fun parseVenue(input: String, fallbackRegion: String = "kochi"): Venue? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        venueRe.find(raw)?.let { m ->
            val region = m.groupValues[1].lowercase()
            val slug = m.groupValues[2].lowercase()
            val code = m.groupValues[3].uppercase()
            if (validPart(region) && validPart(slug) && validCode(code)) {
                return Venue(code, prettySlug(slug), region, slug)
            }
        }

        // A bare cinema code is only useful if it is one of the built-ins; for
        // anything else we need the /cinemas/<region>/<slug>/buytickets/<CODE> URL.
        val code = raw.uppercase()
        if (venueCodeRe.matches(code)) {
            SEED_VENUES.firstOrNull { it.code == code }?.let { return it }
        }
        return null
    }

    fun parseMovie(input: String, fallbackRegion: String = "kochi"): Movie? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        movieRe.find(raw)?.let { m ->
            val region = m.groupValues[1].lowercase()
            val slug = m.groupValues[2].lowercase()
            val code = m.groupValues[3].uppercase()
            if (validPart(region) && validPart(slug)) {
                return Movie(code = code, title = prettySlug(slug), slug = slug, region = region)
            }
        }

        val code = eventCodeRe.find(raw)?.groupValues?.get(1)?.uppercase() ?: return null
        return Movie(code = code, title = code, region = fallbackRegion)
    }

    private fun validPart(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' }
    private fun validCode(s: String) = s.length in 3..12 && s.all { it.isLetterOrDigit() }

    fun prettySlug(slug: String): String = slug
        .split('-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
