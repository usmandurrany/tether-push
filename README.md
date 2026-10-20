# tether-push

A small push app server (XEP-0357) for apps built on Tether. The user's XMPP server sends it a notification whenever a message arrives for an account that is offline, and it passes the notification on to Firebase Cloud Messaging (FCM). The FCM message wakes the app, and the app fetches the message with `chat.wakeForPush()`.

A shared app server such as `push.tigase.im` or Conversations' `p2.siacs.eu` can't do this for your app. FCM only delivers to an app for the Firebase project that holds its keys, so the app server has to be yours.

## How it works

Most app servers are server components: they need a domain, a certificate and an open server-to-server port. This relay is an ordinary XMPP **account** instead. It logs in with a fixed resource (`push`), and devices register its full address (`relay@example.org/push`) as their push service. The user's server then sends each notification to that address like any other request. So the relay needs nothing but a connection out, and it runs anywhere a process can stay up.

```
sure.im ──(XEP-0357 publish, to relay@sure.im/push)──> push relay ──(FCM HTTP v1)──> phone
```

- **Registration** uses the p2 ad-hoc command, `register-push-fcm` with a `token` field, so the SDK's `PushAppServer.p2(...)` works unchanged. The relay answers with a random node, the token, and an HMAC of the token. The SDK passes the token and HMAC to the user's server as publish options, which sends them back with every notification.
- **No database.** Everything the relay needs arrives with the notification. It only pushes to tokens it signed, and only for the servers in `RELAY_ALLOWED_DOMAINS`.
- **Privacy.** The FCM message carries only `type`, `node` and the unread `count`. The sender and text are forwarded only with `RELAY_INCLUDE_MESSAGE=true`, and only if the server includes them.
- **Expired tokens.** When FCM says a token is gone, the relay answers `item-not-found`, and the user's server stops sending notifications for that device.

## What you need

1. **An XMPP account for the relay**, separate from any user's. On a public server such as sure.im, register one more account (for example `myapp-push@sure.im`). The relay only ever logs in with resource `push`; run one copy, because a second copy would take the resource from the first.
2. **A Firebase project** with your Android app in it (its `google-services.json` goes in the app).
3. **Somewhere to run it.** It is a long-running process that holds one XMPP connection open. Cloud Functions can't do that, because they only run while answering a request. These two options run in your Firebase project:
   - **Cloud Run** with one instance always running. It is the simplest to set up, but it costs money: an always-on instance is billed all month.
   - **A Compute Engine `e2-micro` VM**, which is in Google Cloud's free tier in some US regions. It takes slightly more setup.

   Any other host works too: a VPS, a NAS or a Raspberry Pi, as long as it can reach the XMPP server. Outside Google Cloud, give it a service-account key file.

## Configuration

Everything is set with environment variables, so no secret goes in the image or the repository.

| Variable | Meaning |
| --- | --- |
| `RELAY_JID` | The relay's account, such as `myapp-push@sure.im` |
| `RELAY_PASSWORD` | Its password |
| `RELAY_RESOURCE` | `push` by default. Devices register `RELAY_JID/RELAY_RESOURCE` |
| `RELAY_HOST`, `RELAY_PORT` | Optional, to connect without DNS SRV |
| `RELAY_TLS_PIN` | SHA-256 fingerprint of a self-signed server certificate to trust, as the tether-xmpp probe prints it. Unset for public servers |
| `RELAY_SECRET` | 32 random bytes, base64: `openssl rand -base64 32`. Changing it invalidates every registration |
| `RELAY_ALLOWED_DOMAINS` | Servers allowed to push through the relay, comma-separated, such as `sure.im`. Empty allows any |
| `RELAY_INCLUDE_MESSAGE` | `true` forwards the sender and text. Off by default |
| `FCM_PROJECT_ID` | Your Firebase project id. Found automatically on Google Cloud |
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to a service-account key file. Leave unset on Cloud Run and Compute Engine, where the relay uses the service account it runs as |
| `RELAY_DEBUG` | `true` prints every stanza in and out, to see what the XMPP server sends. SASL is redacted but FCM tokens are visible, so turn it off afterwards |
| `PORT` | The plain-text status page (online state, forwarded and refused counts). `8080` by default; Cloud Run sets it |

## Build and run it locally

The SDK is a git submodule, so clone with `--recursive` (or run `git submodule update --init`). Needs JDK 17 or newer.

```sh
git clone --recursive https://github.com/usmandurrany/tether-push.git && cd tether-push
./gradlew installDist
RELAY_JID=myapp-push@sure.im RELAY_PASSWORD=... RELAY_SECRET=$(openssl rand -base64 32) \
RELAY_ALLOWED_DOMAINS=sure.im GOOGLE_APPLICATION_CREDENTIALS=~/keys/myapp-service-account.json \
  build/install/tether-push/bin/tether-push
```

Download the key file in the Firebase console under *Project settings > Service accounts > Generate new private key*. Keep it out of the repository: `.gitignore` already ignores `*-service-account*.json` and `*.env`.

## Deploy to Cloud Run (in your Firebase project)

```sh
PROJECT=your-firebase-project-id
REGION=us-central1
gcloud config set project $PROJECT
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com fcm.googleapis.com

# Secrets: the relay account's password and the signing key
printf '%s' 'the-relay-password' | gcloud secrets create relay-password --data-file=-
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create relay-secret --data-file=-

# A service account that may send FCM messages and read the two secrets
gcloud iam service-accounts create push-relay
SA=push-relay@$PROJECT.iam.gserviceaccount.com
gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:$SA --role=roles/firebasecloudmessaging.admin
for s in relay-password relay-secret; do
  gcloud secrets add-iam-policy-binding $s --member=serviceAccount:$SA --role=roles/secretmanager.secretAccessor
done

# Build the image and deploy one always-on instance
gcloud artifacts repositories create tether --repository-format=docker --location=$REGION
IMAGE=$REGION-docker.pkg.dev/$PROJECT/tether/tether-push
gcloud builds submit --config cloudbuild.yaml --substitutions _IMAGE=$IMAGE .
gcloud run deploy tether-push --image $IMAGE --region $REGION --service-account $SA \
  --min-instances 1 --max-instances 1 --no-cpu-throttling --memory 512Mi --no-allow-unauthenticated \
  --set-env-vars RELAY_JID=myapp-push@sure.im,RELAY_ALLOWED_DOMAINS=sure.im \
  --set-secrets RELAY_PASSWORD=relay-password:latest,RELAY_SECRET=relay-secret:latest
```

`--min-instances 1 --max-instances 1 --no-cpu-throttling` keep exactly one copy running with its connection open. Check it with `gcloud run services logs read tether-push --region $REGION`, which should show `Online as myapp-push@sure.im/push`.

On an `e2-micro` VM, run the same image with Docker (`docker build -t tether-push .`) and the same variables. Attach the `push-relay` service account to the VM so no key file is needed.

## On your own server (MongooseIM, ejabberd, Prosody, Openfire)

Any server with XEP-0357 works the same way: give the relay an account on that server and register `that-account/push` in the app. On MongooseIM, `mod_event_pusher` with the `push` backend must be on (the server then advertises `urn:xmpp:push:0`). Use no `virtual_pubsub_hosts`, so notifications go to the relay instead of MongoosePush. For a server on your LAN, run the relay next to it rather than on Cloud Run, which can't reach your LAN:

```sh
docker build -t tether-push .
docker run -d --restart unless-stopped --name tether-push \
  -e RELAY_JID=push@nas.local -e RELAY_PASSWORD=... -e RELAY_SECRET=... \
  -e RELAY_HOST=192.168.1.10 -e RELAY_TLS_PIN=<sha-256 of the server certificate> \
  -e RELAY_ALLOWED_DOMAINS=nas.local -e FCM_PROJECT_ID=your-firebase-project-id \
  -e GOOGLE_APPLICATION_CREDENTIALS=/keys/sa.json -v ~/keys/myapp-service-account.json:/keys/sa.json:ro \
  tether-push
```

It only needs to reach the XMPP server and, outbound, Firebase. Nothing has to reach it.

## In the app

```kotlin
chat.registerPush(PushAppServer.p2("myapp-push@sure.im/push", fcmToken, deviceId = installId))
```

Then call `chat.wakeForPush()` from `FirebaseMessagingService.onMessageReceived`. `PushAppServer` and `wakeForPush` arrive in tether-xmpp with [PR #23](https://github.com/usmandurrany/tether-xmpp/pull/23); the [Android demo](https://github.com/usmandurrany/tether-demo-android) shows both.

## Code

- [`PushRelay.kt`](src/main/kotlin/io/github/usmandurrany/tether/push/PushRelay.kt): the XMPP side (registration command, notifications, disco), a `ClientExtension` on a Tether client.
- [`Fcm.kt`](src/main/kotlin/io/github/usmandurrany/tether/push/Fcm.kt): FCM HTTP v1, with access tokens from the metadata server or a service-account key. No Google libraries.
- [`Main.kt`](src/main/kotlin/io/github/usmandurrany/tether/push/Main.kt): configuration, reconnects, and the status page.

The SDK is pinned as a submodule. To move to the latest: `git submodule update --remote tether-xmpp`, then commit the new pointer.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
