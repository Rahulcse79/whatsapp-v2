package fixtures

// VIOLATION FIXTURE - never compiled. Rule 5 must reject all of these.
import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import io.reactivex.rxjava3.core.Observable

class ForbiddenConcurrency {
    val live = MutableLiveData<String>()
    val stream: Observable<String>? = null

    fun spawn() {
        Thread({ println("raw thread") }).start()
    }
}
