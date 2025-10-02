# ItemsAdderFix

<img width="1024" height="1024" alt="image" src="https://github.com/user-attachments/assets/ff8fa131-fe14-4d47-8ade-3bfc71009e51" />


ItemsAdderFix is a lightweight hotfix plugin for Paper/Spigot 1.20.1 servers that use [ItemsAdder](https://www.spigotmc.org/resources/75974/) together with ProtocolLib. Some vanilla packets still ship entity hover events that use Mojang's legacy UUID representation (an integer array or `{most,least}` object). When ItemsAdder/LoneLibs/Adventure try to deserialize these payloads they expect a string UUID and throw a `JsonSyntaxException`.

This plugin intercepts every outgoing Play packet through ProtocolLib with the lowest priority and normalizes the `hoverEvent` payloads so that entity UUIDs are always strings. This keeps ItemsAdder running without touching your existing configuration.

## Requirements
- Java 17 runtime
- Paper or Spigot 1.20.1
- [ProtocolLib 5.3.0+](https://github.com/dmulloy2/ProtocolLib) (already required by ItemsAdder)

## Building
```bash
mvn package
```
The shaded jar will be produced in `target/itemsadderfix-1.0.0-shaded.jar` with Gson relocated to `com.ssilensio.itemsadderfix.libs.gson` to avoid dependency clashes.

## Installation
1. Place the generated jar into your server's `plugins/` folder.
2. Ensure ProtocolLib is installed and updated to 5.3.0 or newer.
3. Start or reload the server. The console will confirm that hover event normalization is active.

## Configuration
ItemsAdderFix ships with a minimal configuration file located at `plugins/ItemsAdderFix/config.yml`:

```yaml
# Configuration for ItemsAdderFix
# Set to true to log every time the hover event UUID normalization alters XML payloads.
log-fixes: true
```

When `log-fixes` is enabled, normalized payload pairs are appended to `plugins/ItemsAdderFix/handled-errors.xml` so you can audit what the plugin adjusted. Malformed or empty payload data is ignored, ensuring the XML only tracks genuine fixes. Set the value to `false` if you do not want the XML log to be updated.

## How it works
- Registers a ProtocolLib listener with `ListenerPriority.LOWEST`, guaranteeing the fix runs before ItemsAdder's own listeners.
- Scans chat components in outgoing packets.
- Rewrites `hoverEvent:show_entity` payloads that carry legacy UUID formats (int arrays or `{most,least}` objects) into standard UUID strings.
- Leaves already valid payloads untouched.

No game mechanics are changed and no configuration options are added—this plugin simply prevents the `JsonSyntaxException` spam and crashes triggered by malformed hover event data.
