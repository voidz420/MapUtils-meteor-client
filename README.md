# Mappart Utils

A [Meteor Client](https://meteorclient.com/) addon with utilities for map art creation.

Built for Minecraft 1.21.8 | **Beta**

## Installation

1. Download the latest release from [Releases](https://github.com/voidz420/MapUtils-meteor-client/releases)
2. Place the `.jar` file in your `.minecraft/mods` folder
3. Make sure you have Meteor Client installed

## Modules

### MapUtils Category

| Module | Description |
|--------|-------------|
| **FastMap** | Silently swaps to item frame when placing maps. Hold a map and click - it automatically places the item frame then the map in one action. |

## Features

### FastMap
- **Silent Swap**: Places item frames without visually switching your held item
- **One-Click Placement**: Just click with a map to place both frame and map
- **Auto Swap Back**: Automatically returns to holding the map after placement
- **Block Face Detection**: Only triggers on valid wall surfaces

#### Settings
- `Auto Swap Back` - Return to holding the map after placement (default: on)
- `Only On Blocks` - Only trigger when clicking valid block faces (default: on)

## Building

```bash
./gradlew build
```

The built jar will be in `build/libs/`

## License

This project is licensed under GPL-3.0 - see the [LICENSE](LICENSE) file for details.

## Credits

- [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) - The base client
- [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template) - Project template
