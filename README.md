# NostrCraft (Forge 1.12.2)

Browse and post to **Nostr** from inside Minecraft.

* **Nostr Screen block** – a monitor that shows a live, rotating ticker of the global feed on its front face.
  Right-click it to open the full browser (scroll the feed, write notes).
* Press **N** anywhere (rebindable) to open the same browser without a block.
* Works in singleplayer *and* multiplayer. All Nostr traffic and your key live **on your client only**.

Craft: obsidian ring, glass pane in the middle, ender pearl below it (`recipes/nostr_screen.json`).

## Implemented

| Spec | What |
|------|------|
| NIP-01 | events, canonical id hashing, `REQ` / `EVENT` / `CLOSE`, `OK` / `EOSE` / `NOTICE` / `CLOSED`, kind 0 (names) + kind 1 (notes) |
| NIP-19 | `npub` / `nsec` bech32 encode + decode |
| BIP-340 | Schnorr sign + verify (pure `BigInteger`, tested against the official vector) |

Incoming event ids **and signatures are verified**. Replies are hidden from the feed (`NostrClient.HIDE_REPLIES`).

## Commands (client-side only, never sent to the server)

```
/nostr login <nsec1...|hex>     /nostr generate     /nostr logout
/nostr whoami                   /nostr export       (prints your nsec - careful)
/nostr post <text>
/nostr relay list | add <wss://...> | remove <wss://...>
```

## Build / run

Needs **JDK 8** (ForgeGradle 2.3 does not work on newer JDKs) and **Gradle 4.9**.

Easiest: download the official 1.12.2 MDK from files.minecraftforge.net, then copy this project's
`src/` and `build.gradle` over the MDK's, and run:

```
./gradlew setupDecompWorkspace     # first time only
./gradlew runClient
./gradlew build                    # jar ends up in build/libs/
```

(Or in this folder: `gradle wrapper --gradle-version 4.9` and use `./gradlew`.)
No extra libraries: Gson and Netty (WebSocket + TLS) already ship with Minecraft 1.12.2.

## Layout

```
nostr/    pure Java, no Minecraft imports: Schnorr, Bech32, NostrEvent, RelayConnection (Netty ws), NostrClient
block/    BlockNostrScreen + TileNostrScreen (marker TE)
client/   TESRNostrScreen (draws text on the block), GuiNostrFeed, GuiNostrLogin, /nostr command
```

## Security notes

- Secret key is stored **in plain text** in `config/nostrcraft.json`, need to implement NIP-46 (remote signer) before real use
- The Schnorr code is not constant-time. Fine for a game client, not for a server handling many keys
- All relay-supplied text is stripped of `§` formatting codes and control characters before display
- Old Java 8 builds may lack the root CA for some relays' TLS certs; update Java 8 or edit relays with `/nostr relay`

## Ideas / next steps

- NIP-02 follow list as a "Following" feed
- NIP-05 verified names
- NIP-10 threads & replies
- NIP-18 reposts
- NIP-22 comment
- NIP-25 reactions (right-click a note to like)
- NIP-42 auth
- NIP-46 remote signing
- NIP-57 zaps
- NIP-65 relay lists
- NIP-89 client tag
- per-block filters (hashtag / author / relay) stored in `TileNostrScreen`
- profile pictures via a texture cache
