package com.whatsappv2.domain.model

/** How a call transfer is performed (§5.2, DoD 10). */
enum class TransferType {
    /**
     * `REFER` straight to the target. The transferor drops out immediately and never
     * learns whether the transferee answered.
     */
    BLIND,

    /**
     * The transferor consults the target on a second call first, then sends `REFER`
     * with `Replaces`. Needs the second-call machinery from Task 56.
     */
    ATTENDED,
}
