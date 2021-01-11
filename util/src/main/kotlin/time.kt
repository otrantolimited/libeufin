/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import java.time.*
import java.time.format.DateTimeFormatter

private var LIBEUFIN_CLOCK = Clock.system(ZoneId.systemDefault())

fun setClock(abs: ZonedDateTime) {
    LIBEUFIN_CLOCK = Clock.fixed(abs.toInstant(), abs.zone)
}
fun setClock(rel: Duration) {
    LIBEUFIN_CLOCK = Clock.offset(LIBEUFIN_CLOCK, rel)
}

fun getNow(): ZonedDateTime {
    return ZonedDateTime.now(LIBEUFIN_CLOCK)
}

fun ZonedDateTime.toZonedString(): String {
    return this.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

fun ZonedDateTime.toDashedDate(): String {
    return this.format(DateTimeFormatter.ISO_DATE)
}

fun dashedDateToZonedDateTime(date: String): ZonedDateTime {
    val dtf = DateTimeFormatter.ISO_DATE
    val asLocalDate = LocalDate.parse(date, dtf)
    return asLocalDate.atStartOfDay(ZoneId.systemDefault())
}

fun importZonedDateFromSecond(second: Long): ZonedDateTime {
    return ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(second),
        ZoneId.systemDefault()
    )
}

fun importZonedDateFromMillis(millis: Long): ZonedDateTime {
    return ZonedDateTime.ofInstant(
        Instant.ofEpochMilli(millis),
        ZoneId.systemDefault()
    )
}

fun ZonedDateTime.millis(): Long {
    return this.toInstant().toEpochMilli()
}