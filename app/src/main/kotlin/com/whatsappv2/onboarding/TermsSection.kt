package com.whatsappv2.onboarding

/**
 * The terms shown before the app can be used, as data rather than as layout.
 *
 * ## These are a starting text, not legal advice
 *
 * They say plainly what this app does, what it does not do, and where a call and a
 * recording actually go — which is the part a user of a SIP softphone can genuinely be
 * surprised by. They have not been reviewed by anyone qualified to review them, and the
 * clause that matters most legally is the emergency-calling one: a softphone that depends
 * on a data connection and a third-party PBX is not a substitute for a mobile network's
 * emergency service, and saying so is not optional in most jurisdictions.
 *
 * Whoever owns this product should have these replaced before it ships to anyone outside
 * the team. Raising [FirstRunStore.TERMS_VERSION] is what makes the new text be accepted
 * again by people who accepted this one.
 *
 * Plain data so the screen stays a layout and the text stays reviewable in a diff by
 * somebody who does not read Compose.
 */
internal data class TermsSection(val heading: String, val body: String)
