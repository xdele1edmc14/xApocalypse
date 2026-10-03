# PlaceholderAPI

xApocalypse 1.6.3 provides an internal [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) expansion. PlaceholderAPI is optional: xApocalypse loads normally without it, and automatically registers the expansion when it is installed.

## Available placeholders

| Placeholder | Value |
|-------------|-------|
| `%xapocalypse_bloodmoon_days_left%` | Whole in-game days until the next natural Blood Moon. Returns `Tonight` while a Blood Moon is active or due that day. |
| `%xapocalypse_zombie_guts_duration%` | Whole seconds remaining on that player's Zombie Guts effect. Returns `0` when inactive or when no player context is available. |
| `%xapocalypse_current_scent%` | That player's current scent value, rounded to the nearest whole number. Returns `0` when no scent or player context is available. |

The expansion is bundled inside xApocalypse, so it does not need to be downloaded through PlaceholderAPI's eCloud. Use the placeholders anywhere supported by scoreboard, tab-list, chat, hologram, or other PlaceholderAPI-aware plugins.

## Installation

1. Install PlaceholderAPI alongside xApocalypse.
2. Start or restart the server. xApocalypse logs `Hooked into PlaceholderAPI successfully.` when registration succeeds.
3. Test a value with PlaceholderAPI's parse command, for example: `/papi parse me %xapocalypse_current_scent%`.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
