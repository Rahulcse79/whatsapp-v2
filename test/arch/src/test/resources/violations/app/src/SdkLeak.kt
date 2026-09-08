package fixtures

// VIOLATION FIXTURE - never compiled. Rule 2 must reject both of these, for different
// reasons: liblinphone is banned outright (ADR-006 removed it), and PJSIP is banned here
// because `app/src` is not `:data:sip`.
import org.linphone.core.Core
import org.pjsip.pjsua2.Endpoint

class SdkLeak(private val core: Core, private val endpoint: Endpoint)
