# Third Reality Soil Moisture Sensor for Hubitat

A Hubitat driver for the Third Reality Zigbee soil moisture sensor, models 3RSM0147Z and 3RSM0347Z. It reports soil moisture, temperature, and battery level. It also learns how fast your soil dries and estimates how many days are left until the plant needs water.

The driver builds a **Plant Card** for Hubitat dashboards. The card shows a ring for how much usable water is left in the soil, plus the days remaining, battery, moisture, and temperature. When the plant needs water, the card switches to a "WATER NOW" alert.

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

The ring and the days-left estimate use three moisture levels. Field Capacity and Wilting Point compare against the **raw moisture** reading on the device page. Watering Threshold compares against **soil moisture**, which is raw moisture plus any Moisture Offset. With no offset, the two are the same.

| Setting | What it means | How to find it |
|---|---|---|
| **Field Capacity** | The wettest the soil stays after draining. This is a full ring. | Water the plant thoroughly, let it drain for about an hour, then read the raw moisture. |
| **Watering Threshold** | The level where the plant should be watered. The card shows "WATER NOW" at or below it. | Read the raw moisture on a day when you'd normally water. |
| **Permanent Wilting Point** | The level where the plant can no longer take up water. This is an empty ring. | Set it somewhat below the watering threshold. |

**Max Days Limit** caps the days-left display when the soil is drying very slowly. **Moisture Offset** and **Temperature Offset** shift readings if your sensor reads high or low. Temperature follows your hub's Celsius or Fahrenheit setting, and the offset is in the same scale.

The days-left estimate shows **CALC** until the driver has about five hours of history. After each watering, it averages the newest drying rate with past ones, so the estimate improves over time.

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
2. On the device page, click **Reset Sensor**. Leave the option at **No** to keep your calibration settings, or pick **Yes** to put Field Capacity, Watering Threshold, Wilting Point, and both offsets back to their defaults.

The reset clears the hourly history and the learned drying rate. The card shows **CALC** again until it has about five hours of new readings. The first new history point comes from the current reading, so move the sensor before resetting, not after. A new pot or soil mix usually needs recalibrating anyway (see step 2).

Hubitat's own **Events** tab still lists the old readings. The driver can't delete those. Hubitat removes them over time.

## Automations

The device also acts as a contact sensor: **open** means the plant needs water (soil moisture is at or below the Watering Threshold), and **closed** means it's fine. Use it in Rule Machine, or expose it to Alexa or HomeKit as a "needs water" signal.

## Updating the driver

After importing a new version, click **Configure** on each device page. This applies any new reporting settings, such as the battery percentage.

## Notes

- **Missing sensor.** If the hub hears nothing from the sensor for over two hours, the hourly scan is skipped and a warning is logged. This keeps a dead battery or lost signal from looking like a plant that has stopped drying out. The `healthStatus` attribute switches to `offline` at the same time and back to `online` on the next report, so you can get a Rule Machine notification about it.
- **Browser support.** The CSS uses the `:has()` selector to find Plant Card tiles. Current Chrome, Safari, Edge, and Firefox support it. Very old wall tablets may not, and the card will look unstyled there.
- **Card title.** The title is the device name, minus a trailing "Soil Sensor" or "Moisture Sensor". Clicking it opens the device page. That link only works on your local network, not through a cloud dashboard link.
- **Troubleshooting.** Click **Log History** on the device page to write the last three days of hourly readings to **Logs** as a table, with rejected readings in red. Turn on **Enable debug logging** to log every hourly scan and drying-rate calculation as it happens. It turns itself off after 24 hours.
- **Customizing the card.** Hubitat won't show tile values longer than 1,024 characters. The driver keeps the card HTML short and puts all static styling in the CSS. Keep it that way if you change the card.

## License

GPL-3.0. See `LICENSE`.
