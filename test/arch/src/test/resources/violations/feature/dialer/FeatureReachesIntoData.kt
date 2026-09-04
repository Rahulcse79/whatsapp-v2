package fixtures

// VIOLATION FIXTURE - never compiled. Rule 3 must reject this.
import com.whatsappv2.data.account.SipAccountRepositoryImpl

class FeatureReachesIntoData(private val repository: SipAccountRepositoryImpl)
