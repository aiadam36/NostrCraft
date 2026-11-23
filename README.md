# NostrCraft

Browse and post to **Nostr** from inside of your Minecraft

![Cover image](https://static.adamiskandar.com/images/nostrcraft/cover-6ddccb.webp)

> [!NOTE]
> We only develop for **Forge 1.12.2**, other version might be an unofficial port so be cautious

- **Nostr Screen block** – a monitor that shows a live, rotating ticker of the global feed on its front face. Right-click it to open the full browser (scroll the feed, write notes)
- Press **N** anywhere (rebindable) to open the same browser without a block
- Works in singleplayer *and* multiplayer. All Nostr traffic and your key live **on your client only**

## Implemented

| Spec    | What                                                                                                                                                                                                                                           |
|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIP-01  | validated events with canonical id hashing and signatures; general `REQ` filters (ids, authors, kinds, tags, time, limit), persistent subscriptions with reconnect, `EVENT` publishing, and relay `OK` / `EOSE` / `NOTICE` / `CLOSED` handling |
| NIP-02  | reads the logged-in account's kind-3 follow list and filters a Following feed by `p` tags                                                                                                                                                      |
| NIP-05  | verifies kind-0 identifiers against `https://<domain>/.well-known/nostr.json`, marks matching profiles, and resolves searched identifiers back to matching profiles                                                                            |
| NIP-19  | `npub` / `nsec` bech32 encode + decode; parses public entities, renders references as readable links, and opens profile/event detail views when clicked                                                                                        |
| NIP-50  | checks relay NIP-11 capabilities before using `search`; supports `from:`, `kind:`, `since:`, `until:`, and `#tag` filters with local text matching on relays without NIP-50                                                                    |
| BIP-340 | Schnorr sign + verify (pure `BigInteger`, tested against the official vector)                                                                                                                                                                  |

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

## Search bar

Search for note text or enter a NIP-19 profile/event identifier or a NIP-05 identifier such as `name@example.com`

> [!TIP]
> Add filters to narrow the results; filters can be combined with text

| Search term         | What it matches                                         | Example             |
|---------------------|---------------------------------------------------------|---------------------|
| Text                | Note content                                            | `hello nostr`       |
| NIP-19 identifier   | A profile or event                                      | `npub1...`          |
| NIP-05 identifier   | A verified name and domain                              | `name@example.com`  |
| `from:`             | Notes by an author                                      | `from:npub1...`     |
| `kind:`             | A Nostr event kind                                      | `kind:1`            |
| `#tag`              | Notes with a tag                                        | `#nostr`            |
| `since:` / `until:` | A time range, using Unix seconds or a relative duration | `since:7d until:2h` |

For example, `nostr from:npub1... kind:1 since:7d #nostr` searches for matching text and applies all the listed filters

> Relative durations include `2h` (two hours) and `7d` (seven days)

## Crafting recipe

You need *7x Obsidian*, *1x Glass Pane* and *1x Ender Pearl*

![Crafting recipe](https://static.adamiskandar.com/images/nostrcraft/recipe-1cde8475.png)

> Refer to `src/main/resources/assets/nostrcraft/recipes/nostr_screen.json`

## Build / run

> [!NOTE]
> ForgeGradle 2.3 requires **JDK 8**. The project targets Forge **1.12.2-14.23.5.2847**

This repository already includes the Gradle wrapper (Gradle **4.9**), so you don't need to download the Forge MDK. To build locally:

1. Clone this repository and make sure `java -version` reports JDK 8

2. In the repository root, run the build:

```sh
./gradlew --no-daemon setupDecompWorkspace build
```

For second run:

```sh
./gradlew --no-daemon build
```

The built mod is in `build/libs/`. To launch the Minecraft client for development, run `./gradlew runClient` from the same directory after setup

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

## Screenshots

![In-game Nostr Screen block](https://static.adamiskandar.com/images/nostrcraft/ingame-b7bfcf.webp)

![Nostr browser](https://static.adamiskandar.com/images/nostrcraft/browser-e8fd0c.webp)

## Ideas / next steps

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
- test NIP-02 follow list functionality since it's experimental feat

## Contributing

Feel free to fork and submit PR
