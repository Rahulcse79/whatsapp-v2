package com.whatsappv2.domain.usecase

/**
 * ## Why there is no `ObserveAccountsUseCase` or `SetDefaultAccountUseCase`
 *
 * Task 19 lists four use cases, but §4.2 also says: *"Pass-through use cases that only
 * forward to a repository are noise — call the repository directly and say so."* Both
 * rules cannot be satisfied, so this file says which one won and why.
 *
 * - **Observing accounts** is `repository.observeAccounts()`. A use case wrapping it
 *   would add a class, a binding and a test for a method body that is one delegation.
 * - **Setting the default** is `repository.setDefault(id)`. The rule that matters —
 *   never leave the app with accounts but no default — is enforced *inside* the
 *   repository, where the delete and the promotion can share one transaction. A use case
 *   could not make it more atomic; it could only re-state it less reliably.
 *
 * The use cases that do exist earn their place by composing something no single
 * collaborator can:
 *
 * - [SaveAccountUseCase] combines validation, persistence and the full
 *   unregister-then-register cycle.
 * - [DeleteAccountUseCase] enforces a rule the repository cannot see (a call in progress)
 *   and orders unregistration before the credentials disappear.
 * - [LogoutUseCase] enforces the same call-in-progress rule and separates "stop
 *   registering" from "forget this account", which the repository has no notion of.
 *
 * ## Why [LoginUseCase] exists despite being two lines
 *
 * It looks like the pass-through the rule above forbids, and it is not. Its body is the
 * *point*: it takes an id and re-reads the stored, password-free account, so a decrypted
 * credential cannot travel through the call. A caller registering directly would pass
 * whatever account it happened to be holding — including, in the editor's case, the
 * plaintext the user just typed. Naming that rule once, in a place both the save path and
 * the account list go through, is what makes it hold.
 *
 * A ViewModel calls the repository directly for the other two. That is not a shortcut —
 * it is the layering working: the repository interface already lives in `:domain`, so
 * nothing is bypassed by using it.
 */
internal object UseCaseRationale
