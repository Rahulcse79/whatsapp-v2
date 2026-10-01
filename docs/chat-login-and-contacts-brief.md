# Chat: sign-in, sign-out and contacts — requirement brief

**Supersedes** the "Server URL in Settings" decision in `docs/chat-sdk-integration.md` §7.
**Status:** requirement agreed; implementation blocked on the API specification (§6).
**Order of work:** this brief lands *before* the chat UI in the integration plan.

---

## 1. The corrected requirement, in one paragraph

The app needs a **chat sign-in screen** carrying three fields — **Server URL**, **Username**,
**Password**. The URL is asked *here*, as part of signing in, and saved on success. It is
**not** a Settings entry: the user is asked once, the value is kept, and they are not asked
again until they sign out. The app then needs **sign-out**, and a **contacts** screen reached
from a floating button at the **bottom right of the Chats screen**, listing the people this
account can message; choosing one opens a direct conversation with them.

---

## 2. Screens and behaviour

### 2.1 Sign-in

| | |
|---|---|
| **Fields** | Server URL, Username, Password — in that order. |
| **URL** | Prefilled with `https://gujlogin.coraltele.com/chat/`. Editable. Validated before the request is sent (`ChatServerUrl.parse`, plan §7.3) — in particular `http://` is rejected with its own message, because this app refuses cleartext in **every** build and the runtime failure is otherwise unreadable. |
| **Password** | Masked, with a reveal toggle. Never logged, never in a crash report. Stored the way SIP passwords are — encrypted, via the same mechanism `:data:account` uses — or not stored at all (§5, decision D3). |
| **Submit** | Disabled until all three parse. One in-flight request at a time; the button shows progress and the fields lock. |
| **Errors** | Typed and placed on the field that caused them, not in a generic toast: bad URL → URL field; rejected credentials → password field; unreachable host / timeout → a banner with a retry. This mirrors `AccountRepositoryError`, which exists so the accounts screen can do exactly this. |
| **On success** | Persist the session, then land on Chats. |

### 2.2 Where sign-in sits

The app already has a first-run sequence — `FirstRunGate` runs terms → tour → permissions
before handing over to the app shell for good. **Chat sign-in is a separate gate from that
one**, because it can recur (after sign-out) whereas first-run cannot. It wraps the app
shell, not the first-run gate.

**A signed-out user must still be able to make and receive calls.** This is a SIP client
first; chat is one of its two tabs. So sign-in blocks the **Chats tab**, not the
application — a signed-out user sees a sign-in prompt in that tab and a fully working Calls
tab beside it. Do not put a full-screen login in front of the whole app.

### 2.3 Sign-out

Reached from Settings, in its own "Chat account" section. Confirms first
(`ConfirmDialog` exists in `:core:designsystem`). On confirm: disconnect the socket, clear
the session, return the Chats tab to its signed-out state. **The Server URL survives
sign-out** (decision D2) — the next sign-in is two fields, not three.

### 2.4 Contacts

| | |
|---|---|
| **Entry point** | A `FloatingActionButton` at the **bottom right of the Chats screen**, the position and gesture every messaging app this one sits beside uses for "new conversation". |
| **Content** | The people this signed-in account can message, from the server. |
| **Action** | Tapping one opens — creating if necessary — the direct conversation with them, and navigates to that thread. |
| **States** | Loading, empty ("no contacts yet"), error with retry, and a search field once the list can exceed roughly one screen. `:core:designsystem`'s `StateViews` and `Avatar` already cover the first three and the row. |

> **These are server contacts, not the phone's address book.** They are a different thing
> from `:data:contacts`, which reads the device address book to put a name on a ringing
> screen and is bound by architecture rule 9 — *contact data does not leave the device*.
> Nothing in this feature may read the device address book, and nothing in it may send
> device contact data anywhere. Keep the two apart in naming as well as in code:
> `ChatContact`, never `Contact`.

---

## 3. Naming: "login" is already taken

`LoginUseCase` and `LogoutUseCase` exist in `:domain` and mean **SIP registration** —
"login = save + register", against a registrar, with a `SipError` cause. They have nothing
to do with a chat account.

To keep that unambiguous, everything in this brief is named **sign-in / sign-out**:
`ChatSignInUseCase`, `ChatSignOutUseCase`, `ChatSession`, `ChatSessionRepository`,
`ChatSignInScreen`. Do not add `ChatLoginUseCase` beside `LoginUseCase`.

---

## 4. The architectural problem this creates, stated up front

**The SDK has no sign-in.** `ChatSdk` exposes `init / connect / me / getConversations /
openDirectConversation / getMessages / sendText` and nothing else. `ChatConfig` takes
`restBaseUrl`, `wsUrl`, `deviceKey`, `displayName` — there is no password, no token, no
auth call. There is also **no contacts API** in the SDK.

So sign-in, sign-out and contacts are **REST calls this app must make itself**, outside the
SDK. That has three consequences:

1. **A second HTTP client.** `:data:chat` gains its own Retrofit/OkHttp stack for the auth
   and contacts endpoints, alongside the SDK's private one. It is the module that already
   owns the chat server, so it is the right home; it must not leak upward (architecture
   rule 13, plan §9.5).
2. **The token cannot reach the SDK.** `ChatSdk` builds its `OkHttpClient` privately, so a
   host cannot add an `Authorization` header (plan §1.3, finding 8). If the chat server
   requires a bearer token — and `GET /chat/api/auth/me` currently answers
   `401 ERR_INVALID_ACCESS_TOKEN`, which says it does — **then signing in successfully will
   still not let the SDK connect.** This is the same blocker as plan §10.1, now reached from
   the other direction, and it is the single most important thing the API specification has
   to resolve.
3. **What sign-in produces must feed `ChatConfig`.** Whatever the login response returns, the
   integration needs it to yield a `deviceKey` (and ideally a display name) for
   `ChatSdk.init`. If the server's identity model is "guest mode keyed by username", that is
   simply the username and everything works. If it is "bearer token", see (2).

---

## 5. Decisions taken, so the spec is not ambiguous

| | Decision | Why |
|---|---|---|
| **D1** | The URL lives with the session, not in `AppSettings`. | It is an identity, not a preference. Putting it in Settings means it survives sign-out and can be edited into a state where the session no longer matches the server. |
| **D2** | Sign-out clears credentials and the session; it **keeps** the URL. | Re-signing in is then two fields. The URL is a deployment fact, not a secret. |
| **D3** | The password is **not stored** unless the API requires re-sending it. Store the session token / identity instead. | A stored password is a liability with no benefit if a token can be refreshed. If the API forces it, store it exactly as SIP passwords are stored — encrypted at rest in `:data:account`'s cipher — never in DataStore, which the settings store's own KDoc says is for nothing sensitive. |
| **D4** | Sign-in gates the **Chats tab only**. | Calling must not require a chat account. |
| **D5** | Contacts are server contacts. The device address book is untouched. | Architecture rule 9, and the two are genuinely different data. |

---

## 6. What the API specification must contain

Send these and the implementation plan can be written the same day. Anything missing here is
something that will otherwise be guessed, and a guessed field name is a shipped outage.

**For each endpoint:** method, full path relative to `https://gujlogin.coraltele.com/chat/`,
request headers, request body with **exact** field names and types, success response with
exact field names and types, and the error shape.

1. **Sign in** — presumably `POST api/auth/login`.
   - What identifies the user: username? extension? email?
   - What comes back: token, refresh token, `userId`, `deviceId`, display name, expiry?
   - **Critically:** what does the app then send on subsequent calls — `Authorization: Bearer …`, a cookie, or the `X-Device-Key` headers the SDK already sends?
2. **Sign out** — path, and whether it takes the token in a header or a body.
3. **Token refresh**, if one exists — and what a caller should do on a 401 mid-session.
4. **Contacts** — path, pagination (or confirmation there is none), and the exact fields per
   contact. At minimum the app needs: the id to pass to
   `ChatSdk.openDirectConversation`, a display name, and an avatar URL if one exists.
   Say which field is the id the conversation API expects — `userId`, `contactIdentifier`
   and `deviceKey` are three different things in this server's vocabulary and picking the
   wrong one fails at conversation-creation time, not at contacts time.
5. **The guest-mode answer.** Run this and paste the output — it decides whether the SDK can
   connect at all after a successful sign-in:
   ```bash
   curl -si -H "X-Device-Key: 1001" -H "X-Device-Id: $(uuidgen)" -H "X-Display-Name: 1001" https://gujlogin.coraltele.com/chat/api/auth/me
   ```
6. **A worked example per endpoint** — a real request and its real response, with values
   redacted rather than invented. One real response answers more questions than a schema.

---

## 7. What this changes in `docs/chat-sdk-integration.md`

| Section | Change |
|---|---|
| §0 | The "Server URL" row is superseded — marked in place. |
| §7 | Superseded banner added. **§7.1–§7.4 still stand**: the verified host facts, one-stored-value-two-derived-URLs, the validation rules, and the re-init guard that prevents the SDK's thread leak. Only the *placement* moved. |
| §7.5 | Void. No `AppSettings` / `AppSettingsRepository` / `DataStore` / `SettingsScreen` URL field. |
| §8 | Phase 1 is now **Login**, not "Configuration". A new phase 1b covers contacts. |
| §5.4 | `:domain` additionally gains `ChatSession`, `ChatContact`, `ChatSessionRepository`, `ChatContactRepository`, `ChatSignInUseCase`, `ChatSignOutUseCase`. |
| §5.5 | `:data:chat` additionally gains its own Retrofit stack, `ChatAuthApi`, `ChatContactsApi` and their implementations. |
| §5.6 | `:feature:chat` additionally gains `ChatSignInScreen` and `ChatContactsScreen`, and the Chats screen gains its FAB. |

---

## 8. Revised phase order

| Phase | Contents |
|---|---|
| 0 | Vendor `:chatsdk` (unchanged from the plan). |
| **1** | **Sign-in and sign-out** — URL validation, session storage, the screen, the gate, Settings' sign-out row. |
| **1b** | **Contacts** — the API, the screen, the FAB on Chats, open-conversation-on-tap. |
| 2 | Transport — `:data:chat`, the SDK lifecycle, the ports. |
| 3 | Chat UI — conversation list and message thread. |

Phases 1 and 1b are the only ones that can start before the APIs arrive, and only as far as
the parts that do not touch the wire: `ChatServerUrl` and its validation, the session model,
the screen scaffolding and their tests.
