# NostrCraft

Browse and post to **Nostr** from inside of your Minecraft

> [!NOTE]
> We only develop for **Forge 1.12.2**, other version might be an unofficial port so be cautious

- **Nostr Screen block** – a monitor that shows a live, rotating ticker of the global feed on its front face. Right-click it to open the full browser (scroll the feed, write notes)
- Press **N** anywhere (rebindable) to open the same browser without a block
- Works in singleplayer *and* multiplayer. All Nostr traffic and your key live **on your client only**

## Implemented

| Spec    | What                                                                                                                          |
|---------|-------------------------------------------------------------------------------------------------------------------------------|
| NIP-01  | events, canonical id hashing, `REQ` / `EVENT` / `CLOSE`, `OK` / `EOSE` / `NOTICE` / `CLOSED`, kind 0 (names) + kind 1 (notes) |
| NIP-19  | `npub` / `nsec` bech32 encode + decode                                                                                        |
| BIP-340 | Schnorr sign + verify (pure `BigInteger`, tested against the official vector)                                                 |

Incoming event ids **and signatures are verified**. Replies are hidden from the feed (`NostrClient.HIDE_REPLIES`)

## Commands

All commands were client-side only, they were never sent to the server

```
/nostr generate
/nostr login <nsec1...|hex>
/nostr logout
/nostr whoami
/nostr export
/nostr post <text>
/nostr relay list | add <wss://...> | remove <wss://...>
```

> Be careful with `/nostr export`, it prints your nsec

## Crafting recipe

You need *7x Obsidian*, *1x Glass Pane* and *1x Ender Pearl*

![Crafting recipe](https://static.adamiskandar.com/images/nostrcraft/recipe-1cde8475.png)

> Refer to `src/main/resources/assets/nostrcraft/recipes/nostr_screen.json`

## Build / run

> [!NOTE]
> ForgeGradle 2.3 does not work on newer JDKs

You'll need **JDK 8**. This repository already contains the mod sources and ForgeGradle build file

1. Start from the official Forge **1.12.2-14.23.5.2860 MDK** to get the Gradle wrapper and supporting files

2. Extract the MDK, then copy this project's `src/` and `build.gradle` into its root (replacing the MDK's files)

3. From there, run:

```
./gradlew setupDecompWorkspace
./gradlew build
```

The mod would end up in `build/libs/`

> You need to run `./gradlew setupDecompWorkspace` only for the first time

## Layout

```
src/main/java/com/nostrcraft/block/ -> BlockNostrScreen + TileNostrScreen (marker TE)
src/main/java/com/nostrcraft/client/ -> TESRNostrScreen (draws text on the block), GuiNostrFeed, GuiNostrLogin, /nostr command
src/main/java/com/nostrcraft/nostr/ -> Schnorr, Bech32, NostrEvent, RelayConnection (Netty ws), NostrClient
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
