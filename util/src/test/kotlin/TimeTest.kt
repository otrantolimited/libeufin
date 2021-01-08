import org.junit.Test
import tech.libeufin.util.getNow
import tech.libeufin.util.setClock
import java.time.*
import java.time.format.DateTimeFormatter

// https://stackoverflow.com/questions/32437550/whats-the-difference-between-instant-and-localdatetime

class TimeTest {
    @Test
    fun mock() {
        println(getNow())
        setClock(Duration.ofHours(2))
        println(getNow())
    }

    @Test
    fun importMillis() {
        fun fromLong(millis: Long): LocalDateTime {
            return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(millis),
                ZoneId.systemDefault()
            )
        }
        val ret = fromLong(0)
        println(ret.toString())
    }

    @Test
    fun parseDashedDate() {
        fun parse(dashedDate: String): LocalDate {
            val dtf = DateTimeFormatter.ISO_LOCAL_DATE
            return LocalDate.parse(dashedDate, dtf)
        }
        val ret = parse("1970-01-01")
        println(ret.toString())
    }
}