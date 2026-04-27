# Pynapli iOS Shell

This folder will hold the native iOS shell once the project is opened on macOS with Xcode.

The Kotlin/Compose framework is produced by `:composeApp`. The native iOS shell should stay thin:

- Swift app entry.
- Compose host view.
- APNs setup.
- StoreKit bridge.
- Keychain bridge.
- Firebase Apple SDK setup.
- Native permission bridges.

Product rules belong in `shared` and `server`, not in Swift.

