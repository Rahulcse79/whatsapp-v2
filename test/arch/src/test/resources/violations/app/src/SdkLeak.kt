package fixtures

// VIOLATION FIXTURE - never compiled. Rule 2 must reject this.
import org.linphone.core.Core
import org.pjsip.pjsua2.Endpoint

class SdkLeak(private val core: Core, private val endpoint: Endpoint)
