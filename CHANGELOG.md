# Changelog

## 0.4.0
- Expanded search with kind, author, tag, and time filters, plus NIP-05 name lookup
- Added profile details for websites, banners, birthdays, and automated accounts
- Added NIP-89 app discovery for supported events, with controls to publish app recommendations and opt in to client tags
- Added readable previews for non-text events that include an `alt` description

## 0.3.0
- Added NIP-05 verification badges to the feed and Nostr Screen
- Made NIP-19 references in notes readable and clickable, with in-game views for profiles, notes, events, and addressable events

## 0.2.0
- Added a Following feed based on the account's NIP-02 contact list
- Added note search, using NIP-50 on supporting relays and local matching on other relays
- Expanded NIP-01 support with reusable filters, custom subscriptions, and publishing signed events
- Improved validation and handling of Nostr events and relay messages

## 0.1.2
- Reduced Nostr Screen text rendering distance
- Added occlusion-based rendering to hide text when obstructed by solid blocks

## 0.1.1
- Fixed dark text issue on Nostr Screen block
- Fixed Nostr Screen text clipping within the inset area
- Nostr Screen block now emits light

## 0.1.0
- Initial release
- Added NIP-01 event handling and relay feed/profile subscriptions
- Added NIP-19 `npub` and `nsec` support
- Implemented BIP-340 Schnorr signing and verification
- Added client-side `/nostr` commands for key management, posting, and relay configuration
- Added the Nostr Screen block and crafting recipe
- Added a rebindable key to open the Nostr feed
