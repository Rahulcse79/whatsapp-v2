package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.secret.Secret

/**
 * What the user typed on the sign-in screen.
 *
 * Deliberately short-lived: built by the ViewModel, handed to
 * [com.whatsappv2.domain.usecase.ChatSignInUseCase], encrypted and sent. Never stored,
 * never put on a [ChatSession], never held in a field. The rule is the one Task 18 sets
 * for SIP passwords — a decrypted credential must not outlive the operation that needs it
 * — and it applies here for the same reason.
 */
data class ChatCredentials(
    val username: String,
    val password: Secret,
)
