# Retargeting the push gateway to the `whatsapp-39dcb` Firebase project

This service is WhatsApp V2's own push gateway (see `README.md`). It was already
independent of Sanchar — it never touches the Java `CoralRestAPIServer.jar`, it has its
own port and process, and the Firebase project it pushes through is **whatever
service-account key it is handed at start-up**, read straight from that key's
`project_id`:

    internal/fcm/fcm.go:  endpoint = https://fcm.googleapis.com/v1/projects/<project_id>/messages:send

So "retarget to `whatsapp-39dcb`" is a **credential and deployment** change, not a code
change. There is nothing in this repository to edit to point it at `whatsapp-39dcb`; you
give it a `whatsapp-39dcb` service-account key and it pushes through `whatsapp-39dcb`.

## Why this is needed

The call failure we reproduced (`INVITE → 100 → 183 → 480`) happened on the path that
runs through the **Java IMS on `:8061`**, which pushes through `projects/sanchar-d8f1c`.
WhatsApp V2's tokens are minted under `whatsapp-39dcb`, and FCM HTTP v1 is project-scoped,
so that path can never deliver to our handsets. This service is the path that can: it
reads our `pn-prid` off the REGISTER and pushes through our own project.

## What the client already does (verified in this repo)

* Firebase config `app/google-services.json` → project `whatsapp-39dcb`, package
  `com.whatsappv2` (validated; API key not reproduced here).
* `PushTokenPublisher` → `SipRegistrar.setPushToken` → the token rides the REGISTER
  `Contact` as `pn-provider=fcm;pn-param=273934559014;pn-prid=<token>;pn-type=firebase;
  app-id=273934559014;pn-silent=1` — captured on device 4006.
* `SipMessagingService` / `PushPayload` consume exactly the message this service sends:
  `type=incoming_call`, `call_id`, `account_id`, `sent_at`.

Client and gateway already speak the same protocol. No Android change is required for the
retarget.

## Administrator steps on the server (not performed by this task)

These touch production and a Google Cloud project, so they are yours to run. None of them
alter Sanchar.

1. **Create a WhatsApp V2 service account** in the `whatsapp-39dcb` project
   (Firebase console → Project settings → Service accounts → Generate new private key),
   with only the Firebase Cloud Messaging send permission
   (`roles/firebasecloudmessaging.admin`, or a custom role with
   `cloudmessaging.messages.create`). This is a *server* credential and must never be put
   in the app, the APK, or this repo.
2. **Place the key** at `/etc/coraltelecom/whatsapp-39dcb-fcm.json`, `root:root`, `chmod
   0600`. It sits beside Sanchar's `sanchar-d8f1c-*.json`; the systemd unit grants only the
   WhatsApp file (`LoadCredential=`), so this process never sees Sanchar's.
3. **Build and install the binary:** `go build -o push-sender ./cmd/push-sender` →
   `/opt/coralx-push-sender/push-sender`.
4. **Install the unit and env:** copy `deploy/systemd/coralx-push-sender.service` and
   `coralx-push-sender.env.example` (to `/etc/coraltelecom/coralx-push-sender.env`), edit,
   then `systemctl enable --now coralx-push-sender`. Confirm start-up logs
   `FCM configured project=whatsapp-39dcb`.
5. **Route WhatsApp V2 calls through this path.** This is the real integration decision and
   the one thing still unproven from the client side: the three FreeSWITCH files in
   `deploy/freeswitch/` must be installed so a call to a WhatsApp V2 extension fires
   `coralx::push_wake` and parks, instead of being handed to the Java IMS that currently
   answers it with a 480. Exactly how your dialplan chooses between the two paths per
   extension is a server-side question to settle before this rings.

## Protecting Sanchar

* Separate process, port (`:8085`), project (`whatsapp-39dcb`), key, and systemd unit.
* The Java JAR, its service account, and its dialplan are untouched.
* `LoadCredential=` grants this unit exactly one key file, so even co-located it cannot
  read `sanchar-d8f1c-*.json`.
* No change proposed to the Java controller or the JAR.

## Verifying without disturbing anything

* **Dry run:** start with no key — it logs the push it *would* send and sends nothing, so
  the ESL/dialplan wiring can be exercised with no FCM traffic at all.
* **Manual push:** `curl -XPOST 127.0.0.1:8085/push -d '{"account_id":"2003"}'` sends one
  wake to a registered device without placing a call — acceptance test for delivery alone.
* **End to end:** sleep 2003, call it from 4006, and record INVITE → push → FCM 200 →
  device wake → REGISTER → ring. Do not call it fixed until the handset demonstrably rings.
