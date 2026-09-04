package fixtures

// VIOLATION FIXTURE - never compiled. Rule 4 must reject both of these:
// an interface outside :domain, and an implementation outside :data.
interface CallLogRepository {
    fun everything(): List<String>
}

class CallLogRepositoryImpl : CallLogRepository {
    override fun everything(): List<String> = emptyList()
}
