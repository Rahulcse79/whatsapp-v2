package fixtures

// VIOLATION FIXTURE - never compiled. Rule 10 must reject this.
import androidx.lifecycle.SavedStateHandle
import com.whatsappv2.domain.engine.CallSnapshot

class RestoredCall(private val saved: SavedStateHandle) {
    fun restore(): CallSnapshot? = saved["call"]
}
