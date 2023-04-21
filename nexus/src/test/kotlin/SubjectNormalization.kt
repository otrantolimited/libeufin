import org.junit.Test
import tech.libeufin.util.extractReservePubFromSubject

class SubjectNormalization {

    @Test
    fun testBeforeAndAfter() {
        val mereValue = "1ENVZ6EYGB6Z509KRJ6E59GK1EQXZF8XXNY9SN33C2KDGSHV9KA0"
        assert(mereValue == extractReservePubFromSubject(mereValue))
        assert(mereValue == extractReservePubFromSubject("noise before ${mereValue} noise after"))
        val mereValueNewLines = "\t1ENVZ6EYGB6Z\n\n\n509KRJ6E59GK1EQXZF8XXNY9\nSN33C2KDGSHV9KA0"
        assert(mereValue == extractReservePubFromSubject(mereValueNewLines))
    }
}