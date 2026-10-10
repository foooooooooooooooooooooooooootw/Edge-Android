# Edge wire protocol (as implemented in `src/network.js`, v1.0.7)

Everything is derived from the desktop code. A client that follows this is
interoperable with the desktop app. Integers are big-endian.

## Ports

| Purpose   | Transport | Port                                             |
|-----------|-----------|--------------------------------------------------|
| Discovery | UDP       | 42068 (bind + send to the same port)             |
| Data      | TCP       | 42069 by default; the desktop tries +1 if taken. Real port is advertised in discovery / handshake `listenPort` |

## Discovery (LAN)

Every 3 s each node broadcasts a UDP datagram (UTF-8 JSON) to `255.255.255.255`
and to every IPv4 interface's broadcast address, destination port 42068:

```json
{"service":"edge-p2p","id":"<12 hex>","name":"<display name>","port":42069}
```

On receipt: ignore if `service != "edge-p2p"`, if `id` is our own, or if that
peer is already connected. Otherwise open a TCP connection to
`<datagram source address>:<port>` as **LAN** (`lan:true`).
Both sides do this at the same time, so duplicate connections happen and are
resolved in `registerPeer` (below).

## Framing

```
[u32 len][u8 type][ inner ]          len = 1 + inner.length
type 0x00  JSON frame
type 0x01  BINARY frame (file data)
```

Max `len` = 68 MiB, otherwise drop the connection.

Before encryption is enabled `inner` is the plaintext: UTF-8 JSON for type 0,
or `[4-byte fileId][chunk]` for type 1.

After encryption is enabled `inner` is:

```
[12-byte IV][16-byte GCM tag][ciphertext]
```

i.e. **IV, then tag, then ciphertext**. (Java/Android `Cipher` emits
`ciphertext||tag`, so it must be reordered.) AES-256-GCM, no AAD. Plaintext
is the same bytes as the unencrypted case. A fresh random IV for every frame.

## Handshake and key agreement

1. Connector opens TCP, immediately sends a **plaintext** JSON frame:
   `{"type":"handshake","id","name","listenPort","lan","ecdhPubKey","profilePic"}`
2. Listener replies with a **plaintext** handshake frame of the same shape
   (without `lan`), then enables encryption.
3. Connector, on receiving the reply, enables encryption.
4. Everything after that is encrypted. Frames the connector sends right after
   the reply are already encrypted, so the connector must set its key before
   sending anything else.

Both sides give up if the handshake isn't completed within 5 s.

* Curve: **prime256v1 (P-256)**; fresh ephemeral key pair per connection.
* `ecdhPubKey`: **uncompressed point, 65 bytes (`04||X||Y`), lowercase hex**.
* shared secret = ECDH **X coordinate, 32 bytes**.
* **AES key = SHA-256(shared secret)**.
* Fingerprint shown in the UI = first 32 hex chars of SHA-256(shared secret).
* `profilePic` is a data-URL string or null. May be large; the peer never
  has to use it.

There is no authentication of the peer, only the (ephemeral) key exchange.

## registerPeer: duplicate connection rule

If we already have a live connection to `id`:
`iShouldBeConnector = myId > peerId` (string comparison);
`keepNew = iShouldBeConnector ? !incoming : incoming`.
If not `keepNew`, close the new socket. Otherwise swap it in and close the old
one. A close event from a replaced socket must be ignored.

## JSON messages (all flat objects with `type`)

| type | fields | direction / notes |
|------|--------|-------------------|
| `chat` | `id` (8 hex), `text`, `timestamp` (ms), `replyTo?` | either |
| `reaction` | `msgId`, `emoji`, `remove` | either |
| `profile_update` | `profilePic` | either |
| `file_request` | `fileId` (8 hex = 4 bytes), `name`, `size`, `mime` | sender -> receiver |
| `file_accept` / `file_reject` | `fileId` | receiver -> sender |
| `file_start` | `fileId`, `name`, `size`, `mime` | sender, only after accept |
| *(binary frames)* | `[fileId 4 bytes][chunk <= 512 KiB]` | sender |
| `file_end` | `fileId` | sender |
| `transfer_cancel` | `transferId` (= fileId or folderId) | sender; receiver drops partial |
| `transfer_pause` / `transfer_resume` | `transferId` | receiver -> sender (sender stops/starts sending chunks) |
| `transfer_paused_by_sender` / `transfer_resumed_by_sender` | `transferId` | UI hint only |
| `folder_request` | `folderId`, `name`, `totalSize`, `fileCount` | sender; reply `folder_accept` / `folder_reject` with `folderId` |
| `folder_start` ... `folder_end` | see below | |

Sending a file: `file_request` -> wait up to 10 min for `file_accept`
-> `file_start` -> N binary frames -> `file_end`.
Receiving: on `file_request` ask the user, answer with accept/reject; on
`file_start` open a sink; write each binary frame whose fileId matches; on
`file_end` close.

## Folders

After `folder_accept` the sender sends `folder_start {folderId,name,totalSize,fileCount}`,
then one **ustar tar stream** as binary frames whose 4-byte id is the
`folderId`, then `folder_end {folderId}`. The tar ends with two 512-byte zero
blocks. (Older note: earlier Android builds answered `folder_request` with
`folder_reject`; now implemented.)

## WAN mode

Same protocol, but dialled by address instead of discovered: connect to `host:port` with `lan:false`.
Only the dialling side needs to know an address; the other side must be reachable (UPnP / port forward).
The Android client only ever dials out (phones are behind carrier NAT), keeps saved addresses in
preferences and re-dials them with backoff (`Redialer`). Because the key exchange is unauthenticated,
the security-code comparison matters most over the internet.

## Key derivation v2 (kdf)

Handshakes now carry `"kdf":2`. If both sides send it: AES key = HKDF-SHA256(secret, salt empty,
info "edge-aes-key", 32 bytes) and fingerprint = hex(HKDF(secret, info "edge-fingerprint", 16 bytes)).
If either side lacks it, the legacy sha256 derivation below is used, so old builds still connect.
Long folder paths use the ustar `prefix` field (offset 345, 155 bytes), split at a '/'.

## Security notes (items 1 fixed by kdf v2 when both ends are updated)

1. **The displayed fingerprint is the first half of the AES key** (both are
   derived from `SHA-256(secret)`). Anyone who sees or is read the fingerprint
   learns 128 of the 256 key bits. Derive the key with a different label
   (e.g. HKDF with `info="edge-aes"`) and the fingerprint with another.
2. Keys are unauthenticated, so an active man-in-the-middle on the LAN can
   sit between two peers. Comparing the fingerprint out of band is the only
   defence, which is another reason it shouldn't be key material.
3. The nonce is random 96-bit per frame, fine for a single session.

If you change the derivation on the desktop, the Android client has to change
in lockstep (see `Crypto.kt`).
