package fixtures

// VIOLATION FIXTURE - never compiled. Rule 6 must reject both the exposed
// MutableStateFlow and the public var.
class LeakyViewModel : ViewModel() {
    val uiState: MutableStateFlow<String> = MutableStateFlow("")
    var selectedAccount: String = ""
}
