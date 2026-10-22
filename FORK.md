# This fork: a calm family Home for a Sony BRAVIA

This branch, `sony-x82l-sky-photos`, is the cleanyourtv launcher with a Home built around family photos, the
weather and the day ahead, tuned on a Sony BRAVIA KD-65X82L (Google TV 12). Everything below is **off by
default**: with the stock `config.xml` it behaves like the upstream launcher it branched from. You turn on what you want in
`launcher/app/src/main/res/values/config.xml` (or, better, in an uncommitted `src/release/res/values/config.xml`
next to it, so your keys and tokens never reach git).

Unlike upstream's launcher, this one has the **`INTERNET` permission**, for the features marked *network* below.
Nothing uses it until you turn one of them on.

## What it adds

**Home**

| Key | What it does | Network |
|---|---|---|
| `dock_glass` | The dock as a compact frosted-glass tray with one even gap around and between the tiles | none |
| `soft_banners` | App banners a little darker and less saturated until focused, with a thin light edge, so different apps' art sits together | none |
| `tile_size_percent` | Smaller app tiles in the same columns (60 to 100) | none |
| `more_apps_hint` | A small chevron in the dock band, only while the dock has focus, saying Down brings up the other apps | none |
| `top_menu`, `inputs_icon_only`, `network_tray`, `screensaver_button` | One round button after the weather line that slides out Inputs, Wi-Fi and Settings; a Wi-Fi tray with nearby networks; a button that starts the screensaver | none |
| `on_this_day` | One of your photos taken on today's date in an earlier year (else this week or month) as a print on the right; Home pressed on Home shows the next one | none |
| `night_dim` | From 23:00 to 06:00 the photo, panel and apps fade to 60%; any key brings them back for five minutes | none |
| `weather` | Now, today's high and low and the next sunrise or sunset under the clock, for `latitude` and `longitude` | Open-Meteo |
| `hourly_forecast`, `air_quality` | The next six hours, and India's NAQI from PM2.5 and PM10 (a ~10 km model grid, not a station) | Open-Meteo |
| `calendar_ics_urls` | The next events from one or more calendars' secret iCal addresses, with "+N more"; a trip's destination ("Flight to Goa", "@Goa") gets its own forecast | your calendar's host, Open-Meteo |
| `reminder_minutes` | A banner over any app this many minutes before a timed event | none (uses the agenda) |
| `home_notes` | A Telegram bot of your own: any text becomes a note on Home, `/event fri 6pm Dinner` adds an event, photos sent to it join the screensaver. Only your own Telegram account is accepted | Telegram |

**Screensaver:** Photos, your own photos as three dated prints on the time-of-day sky, dealt in every 15
seconds above a clock line with the weather. Fill it with `tools/photos.sh` from a Mac, or by sending photos to
the bot. It is also an add-on for upstream's launcher: [Photo Prints](https://github.com/Gathin23/cleanyourtv/pull/936).

## What is sent where

| Feature | Sends | To | When |
|---|---|---|---|
| `weather`, `hourly_forecast` | latitude and longitude, two decimals | `api.open-meteo.com` | every 30 minutes, only while Home is on screen |
| `air_quality` | the same | `air-quality-api.open-meteo.com` | the same |
| trip weather | the place name from the event title | `geocoding-api.open-meteo.com`, then `api.open-meteo.com` | once per place, forecasts kept three hours |
| `calendar_ics_urls` | a request for your calendar's iCal file | the address you give (Google Calendar, for example) | every 30 minutes while Home is on screen, every 10 within the hour of an event |
| `home_notes` | your bot token, and replies to your own messages | `api.telegram.org` | every 45 seconds while the screen is on, every 5 minutes while it is off |

No account, analytics or telemetry. The bot accepts messages only from `telegram_owner_id` and ignores everyone
else. The bot token and the calendar's secret address give whoever holds them your notes and your events: keep
them in the uncommitted release config, never in git.

## Set it up

Build and install as upstream's README describes (`launcher/build-and-install.sh`), with your keys in
`launcher/app/src/release/res/values/config.xml`. Then, once:

```bash
# reminder_minutes: the banner over other apps
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow'
# the photos screensaver
TV_HOST=<tv ip> tools/photos.sh ~/Pictures/TV
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.PhotoDream'
```

For the bot: create one with @BotFather, put its token in `telegram_bot_token` and your numeric Telegram user id
in `telegram_owner_id`, and send it `/help`.

## Tested on

Sony BRAVIA KD-65X82L (BRAVIA_VH22), Google TV 12, in use since October 2026. Its debloat list is upstream's
[`examples/sony-kd-65x82l/`](https://github.com/Gathin23/cleanyourtv/tree/main/examples/sony-kd-65x82l).
