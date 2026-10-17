package com.whatsappv2.onboarding

/** The terms themselves. See [TermsSection] for what these are and are not. */
internal val TermsText: List<TermsSection> = listOf(
    TermsSection(
        heading = "What Coral SIP Client is",
        body = "Coral SIP Client is a SIP softphone. It places and receives calls over a network " +
            "using an account you supply — your own PBX, your employer's, or a provider " +
            "you have signed up with. It is not a calling service of its own and it does " +
            "not sell you minutes, numbers or connectivity.",
    ),
    TermsSection(
        heading = "Emergency calls",
        body = "Do not rely on Coral SIP Client to call the emergency services. Calls need a working " +
            "data connection and a SIP account that is registered and willing to route " +
            "them, and none of that is guaranteed at the moment you need it. Your mobile " +
            "network's own dialler reaches emergency services when Coral SIP Client cannot. Keep a " +
            "phone that can.",
    ),
    TermsSection(
        heading = "Your account and your calls",
        body = "You are responsible for the account you configure and for what is done " +
            "with it. Your credentials are stored encrypted on this device and are sent " +
            "only to the server you name. Calls go directly between this device and that " +
            "server: they are not routed through us, and we cannot see, store or recover " +
            "them.",
    ),
    TermsSection(
        heading = "Recording",
        body = "Coral SIP Client can record a call you are on. Recordings are encrypted and kept on " +
            "this device only. Whether you may lawfully record a call — and whether you " +
            "must tell the other party first — depends on where you and they are, and it " +
            "is your responsibility to know. The app asks you to confirm before it starts.",
    ),
    TermsSection(
        heading = "Acceptable use",
        body = "Do not use Coral SIP Client to place unlawful, fraudulent, automated bulk or " +
            "harassing calls, to impersonate anyone, or to get around a restriction your " +
            "provider has placed on your account.",
    ),
    TermsSection(
        heading = "No warranty",
        body = "Coral SIP Client is provided as it is, without warranty of any kind. Call quality, " +
            "call delivery and registration depend on your network, your device and your " +
            "provider, and none of those are under the app's control.",
    ),
    TermsSection(
        heading = "Changes",
        body = "These terms may change. When they do, you will be asked to accept the new " +
            "version before continuing. You can stop using Coral SIP Client at any time by " +
            "uninstalling it, which also removes your accounts, your call history and any " +
            "recordings held on this device.",
    ),
)
