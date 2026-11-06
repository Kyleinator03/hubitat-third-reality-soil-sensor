# Third Reality Soil Moisture Sensor for Hubitat

A Hubitat driver for the Third Reality Zigbee soil moisture sensor, models 3RSM0147Z and 3RSM0347Z. It reports soil moisture, temperature, and battery level. It also learns how fast your soil dries and estimates how many days are left until the plant needs water.

The driver builds a **Plant Card** for Hubitat dashboards. The card shows a ring for how much water is left before the plant needs watering, plus the days remaining, battery, moisture, temperature, and when the sensor last reported. When the plant needs water, the card switches to a "WATER NOW" alert.

## What's in this repo

| File | Purpose |
|---|---|
| `third-reality-soil-sensor-custom.groovy` | The device driver |
| `dashboard.css` | Styling for the Plant Card dashboard tile |

## 1. Install the driver

1. In Hubitat, open **Drivers Code** and click **New Driver**.
2. Click **Import** and paste this URL, then click **Import** and **Save**:

   ```
   https://raw.githubusercontent.com/Kyleinator03/hubitat-third-reality-soil-sensor/main/third-reality-soil-sensor-custom.groovy
   ```

3. Pair the sensor through **Devices > Add Device > Zigbee**. If it's already paired, open the device and change its **Type** to *Third Reality Soil Moisture Sensor Custom*.
4. On the device page, click **Save Preferences**, then **Configure**.

**Configure** sets up the sensor's reporting and starts the hourly history scan that the days-left estimate depends on.

## 2. Calibrate the moisture settings

The ring and the days-left estimate use two moisture levels, both compared against the sensor's moisture reading (**humidity** on the device page). If you have an analog moisture meter you trust, use it to pick these levels: the two devices measure differently and won't show the same numbers, but you can note what this sensor reads when your meter says the soil is wet or dry.

| Setting | What it means | How to find it |
|---|---|---|
| **Field Capacity** | The wettest the soil stays after draining. This is a full ring. | Water the plant thoroughly, let it drain for about an hour, then read the moisture. |
| **Watering Threshold** | The level where the plant should be watered. This is an empty ring, and the card shows "WATER NOW" at or below it. | Read the moisture on a day when you'd normally water, or when your analog meter says the plant needs water. |

**Temperature Offset** shifts the temperature if your sensor reads high or low. Temperature follows your hub's Celsius or Fahrenheit setting, and the offset is in the same scale.

The days-left estimate shows **CALC** until the driver has about five hours of history. Plants drink mostly during the day, so a few hours of readings can make the soil look like it's drying much faster or slower than it really is. The drying rate is the slope of a best-fit line through every hourly reading since the last watering, so a single noisy reading can't swing it. After a watering, the driver starts from the drying rate it learned on earlier cycles and shifts toward the live rate over the first 24 hours. Each watering updates the learned rate, with longer drying cycles counting for more. Cycles shorter than 12 hours are ignored, so the estimate improves over time.

Days left shows one decimal under 10 days and whole days above that, up to **99+**. If the soil hasn't dropped by even one sensor step (about 0.15%) since the driver started measuring, there's no rate to divide by, so the card shows a guaranteed minimum instead, like **38+**. That minimum grows the longer the soil stays flat, and switches to a normal estimate once the soil starts drying. If the hourly readings stop for more than three hours (the sensor dropped offline, or the hub stopped running scheduled jobs), the driver starts measuring again after the gap rather than across it.

After a watering, the driver watches the sensor's reports until the extra water has drained out of the pot, then starts measuring the drying rate. Readings during drainage are left out of the estimate, so Field Capacity only sets where the ring is full and doesn't affect the days-left math.

## 3. Add the Plant Card to a dashboard

The card needs the classic **Hubitat Dashboard** app. **Easy Dashboard** doesn't support custom CSS, so the card won't be styled there.

1. Open your dashboard, click **+** to add a tile, and pick the soil sensor.
2. Set the template to **Attribute** and the attribute to **plantCard**.
3. Open the dashboard's settings, go to **Advanced**, open the **CSS** tab, and paste the contents of `dashboard.css`. Save it.

A 1×1 tile works well on a grid of roughly 200 pixels per tile. The grid size is set in the dashboard's settings. The CSS only restyles tiles that contain a Plant Card, so your other tiles keep their look.

The last block in `dashboard.css` sets a dark page background to match the cards. Delete that block to keep your dashboard's own background.

## Moving a sensor to a new plant

The driver learns how fast each pot dries. When you move a sensor to a different plant or pot, clear what it learned:

1. Put the sensor in the new pot first.
2. On the device page, click **Reset Sensor**. Leave the option at **No** to keep your calibration settings, or pick **Yes** to put Field Capacity, Watering Threshold, and the temperature offset back to their defaults.

The reset clears the hourly history and the learned drying rate. The card shows **CALC** again until it has about five hours of new readings. The first new history point comes from the current reading, so move the sensor before resetting, not after. A new pot or soil mix usually needs recalibrating anyway (see step 2).

Hubitat's own **Events** tab still lists the old readings. The driver can't delete those. Hubitat removes them over time.

## Automations

The device also acts as a contact sensor: **open** means the plant needs water (moisture is at or below the Watering Threshold), and **closed** means it's fine. Use it in Rule Machine, or expose it to Alexa or HomeKit as a "needs water" signal.

## Logging

The device page has two logging toggles. With both off, the driver only logs warnings (sensor offline, a new baseline after a sudden drop, or preference values that don't make sense), when the sensor comes back online, and replies to things you do, like saving preferences or clicking **Reset Sensor**.

- **Enable descriptionText logging** (on by default) adds one check-in line per hourly scan, plus a line when the plant is watered, when drainage finishes, and when the plant starts or stops needing water. A check-in looks like `Fern 61.2% moisture, 74.1°F, battery 90%. ET 3.1%/day, 3.6 days left`.
- **Enable debug logging** adds every sensor reading, every hourly scan (including rejected readings), the drying-rate math, and each drainage sample. Debug lines start with `reading:`, `scan:`, `et:`, or `drain:` so they're easy to filter. It turns itself off after 24 hours.

Every reading is also recorded in the device's **Events** tab, whichever logging toggles are on.

## Updating the driver

After importing a new version, click **Save Preferences** and then **Configure** on each device page. Saving clears out settings and attributes that older versions used, and Configure applies any new reporting settings, such as the battery percentage.

If `dashboard.css` changed in the new version, paste it into your dashboard's CSS again. Version 1.3.0 moved the "WATER NOW" water drop icon into the CSS, so the icon disappears with older CSS.

## Notes

- **Missing sensor.** If the hub hears nothing from the sensor for over two hours, the hourly scan is skipped and a warning is logged. This keeps a dead battery or lost signal from looking like a plant that has stopped drying out. The `healthStatus` attribute switches to `offline` at the same time and back to `online` on the next report, so you can get a Rule Machine notification about it. While the sensor is offline, the Plant Card dims and its **Updated** time turns amber.
- **Last updated time.** The bottom of the card shows when the hub last heard anything from the sensor. A plain time means today. Older reports also show the date. The time doesn't count up on its own, because dashboard tiles can't run scripts. It only changes when the sensor reports.
- **Browser support.** The CSS uses the `:has()` selector to find Plant Card tiles. Current Chrome, Safari, Edge, and Firefox support it. Very old wall tablets may not, and the card will look unstyled there.
- **Card title.** The title is the device name, minus a trailing "Soil Sensor" or "Moisture Sensor". Clicking it opens the device page. That link only works on your local network, not through a cloud dashboard link.
- **Experimental: learned Field Capacity.** After each full soak, the driver records the moisture reading where drainage stopped. The `learnedFieldCapacity` attribute is the median of the last five of those readings. It isn't used for anything yet. It's there to check how well it matches a manually measured Field Capacity before the driver relies on it. `debugFcSampleCount` shows how many soaks it's based on and will be removed later. A small drink that doesn't drain doesn't count as a soak.
- **Troubleshooting.** Click **Log History** on the device page to write the last three days of hourly readings to **Logs** as a table, with rejected readings in red and post-watering drainage in gray. Turn on **Enable debug logging** to see every reading and calculation as it happens (see [Logging](#logging)).
- **Customizing the card.** Hubitat won't show tile values longer than 1,024 characters. The driver keeps the card HTML short and puts all static styling in the CSS. Keep it that way if you change the card.

## License

GPL-3.0. See `LICENSE`.
