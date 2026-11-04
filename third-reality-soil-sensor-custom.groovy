/**
 *  Third Reality Soil Moisture Sensor (with Smart Plant Card & ET Prediction)
 *
 *  Version: 1.3.0
 *  https://github.com/Kyleinator03/hubitat-third-reality-soil-sensor
 *  License: GPL-3.0
 */

import groovy.transform.Field
import java.math.RoundingMode

@Field static final Map DEFAULTS = [fieldCapacity: 75, wateringThreshold: 50, permanentWiltingPoint: 30, tempOffset: 0.0]
@Field static final Map DRAIN = [windowMs: 1800000L, endRatePerHr: 1.0, maxMs: 21600000L, soakMinDrop: 2.0, fcSamplesKept: 5]
@Field static final Map ET = [minPoints: 5, gapMs: 10800000L, blendMs: 86400000L, minCycleMs: 43200000L, maxSavedDays: 7.0, stepPct: 0.15, maxDaysShown: 99]

metadata {
    definition(name: "Third Reality Soil Moisture Sensor Custom", namespace: "kyleinator03", author: "Kyle", importUrl: "https://raw.githubusercontent.com/Kyleinator03/hubitat-third-reality-soil-sensor/main/third-reality-soil-sensor-custom.groovy") {
        capability "Configuration"
        capability "Refresh"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Contact Sensor"
        capability "Sensor"

        attribute "waterRemaining", "number"
        attribute "daysRemaining", "string"
        attribute "plantCard", "string"
        attribute "etRate", "number"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "learnedFieldCapacity", "number"
        attribute "debugFcSampleCount", "number"

        command "logHistory"
        command "resetSensor", [[name: "Also reset calibration settings?", type: "ENUM", constraints: ["No", "Yes"], description: "Yes also restores Field Capacity, Watering Threshold, Wilting Point, and the temperature offset to their defaults"]]

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0147Z", deviceJoinName: "Third Reality Soil Sensor"
        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0347Z", deviceJoinName: "Third Reality Soil Sensor Gen2"
    }

    preferences {
        input name: "fieldCapacity", type: "number", title: "Field Capacity (%)", description: "The maximum amount of water the soil holds against gravity. Set this to the sensor's moisture reading about an hour after a full soaking drain. (e.g., 72)", defaultValue: DEFAULTS.fieldCapacity, required: true
        input name: "wateringThreshold", type: "number", title: "Watering Threshold (%)", description: "The moisture level where the plant actually needs to be watered. This triggers the 'WATER NOW' alert and is the target for the Days Left countdown. (e.g., 50)", defaultValue: DEFAULTS.wateringThreshold, required: true
        input name: "permanentWiltingPoint", type: "number", title: "Permanent Wilting Point (%)", description: "The absolute zero limit where the plant can no longer extract water. This sets the 0% visual empty state of the blue ring gauge. (e.g., 30)", defaultValue: DEFAULTS.permanentWiltingPoint, required: true
        input name: "tempOffset", type: "decimal", title: "Temperature Offset (°)", description: "In your hub's temperature scale.", defaultValue: DEFAULTS.tempOffset, required: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", description: "Logs readings, waterings, and learned drying rates.", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Logs every hourly scan and ET calculation. Turns itself off after 24 hours.", defaultValue: false
    }
}

private pref(String name) {
    settings[name] != null ? settings[name] : DEFAULTS[name]
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName} ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable != false) log.info "${device.displayName} ${msg}"
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName} debug logging turned off"
}

private void scheduleHistory() {
    unschedule("updateHistory")
    runEvery1Hour("updateHistory")
}

def installed() {
    scheduleHistory()
}

def parse(String description) {
    state.lastReportAt = now()
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.value == null) return

    if (descMap.clusterInt == 0x0405 && descMap.attrInt == 0x0000) {
        def moisture = Integer.parseInt(descMap.value, 16) / 100.0
        state.moisture = moisture
        int rounded = Math.min(100, Math.max(0, Math.round(moisture).toInteger()))
        boolean changed = device.currentValue("humidity") != rounded
        sendEvent(name: "humidity", value: rounded, unit: "%", descriptionText: "${device.displayName} soil moisture is ${rounded}%")
        if (changed) logInfo "soil moisture is ${rounded}% (${moisture})"
        if (state.drainSamples != null) trackDrainage(moisture as double, now())
        updatePlantCard()
    } else if (descMap.clusterInt == 0x0402 && descMap.attrInt == 0x0000) {
        int rawInt = Integer.parseInt(descMap.value, 16)
        if (rawInt == 0x8000) return
        if (rawInt > 0x7FFF) rawInt -= 0x10000
        String scale = location.temperatureScale
        double celsius = rawInt / 100.0
        double temp = (scale == "F" ? celsius * 1.8 + 32 : celsius) + pref("tempOffset").toDouble()
        BigDecimal rounded = new BigDecimal(temp).setScale(1, RoundingMode.HALF_UP)
        boolean changed = device.currentValue("temperature") != rounded
        sendEvent(name: "temperature", value: rounded, unit: "°${scale}", descriptionText: "${device.displayName} temperature is ${rounded}°${scale}")
        if (changed) logInfo "temperature is ${rounded}°${scale}"
        updatePlantCard()
    } else if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0021) {
        int rawPct = Integer.parseInt(descMap.value, 16)
        if (rawPct == 0xFF) return
        state.reportsBatteryPct = true
        sendBattery(Math.round(rawPct / 2.0).toInteger())
    } else if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020) {
        if (state.reportsBatteryPct) return
        def rawVolts = Integer.parseInt(descMap.value, 16) / 10.0
        sendBattery(Math.round((rawVolts - 1.0) / 0.5 * 100).toInteger())
    }
}

private void sendBattery(int pct) {
    pct = Math.min(100, Math.max(0, pct))
    boolean changed = device.currentValue("battery") != pct
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}%")
    if (changed) logInfo "battery is ${pct}%"
    updatePlantCard()
}

def updateHistory() {
    long now = new Date().getTime()
    if (state.moisture == null) {
        logDebug "Hourly Scan -> Skipped, no moisture reading yet"
        return
    }
    if (state.lastReportAt != null && now - (state.lastReportAt as long) > 7200000) {
        if (device.currentValue("healthStatus") != "offline") {
            log.warn "${device.displayName} Hourly Scan -> Skipped, nothing heard from the sensor in over 2 hours. Check the sensor's battery and signal."
            sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        } else {
            logDebug "Hourly Scan -> Skipped, sensor still offline"
        }
        updatePlantCard()
        return
    }
    def rawMoist = state.moisture.toDouble()

    if (state.history == null) state.history = []

    if (state.drainSamples != null) {
        if (now - (state.wateredAt as long) >= DRAIN.maxMs) {
            finishDrainage(rawMoist, now)
        } else {
            state.history << [time: now, rawMoisture: rawMoist, statusText: "Draining", isSpeedOutlier: false, isDraining: true]
            logDebug "Hourly Scan -> Draining (${rawMoist})"
        }
        trimHistory(now)
        updatePlantCard(true)
        return
    }

    def validHistory = validPoints()
    def lastValidPoint = validHistory.size() > 0 ? validHistory[-1] : null

    boolean isSpeedOutlier = false
    double drop = 0.0
    double hours = 1.0
    String statusText = "Success"

    if (lastValidPoint != null) {
        drop = lastValidPoint.rawMoisture - rawMoist
        // Floor at 1h so a rescheduled scan minutes after the last one doesn't inflate the rate
        hours = Math.max(1.0d, (now - (lastValidPoint.time as long)) / 3600000.0d)
        if (drop / hours > 2.0) {
            isSpeedOutlier = true
        }
    }

    if (isSpeedOutlier) {
        statusText = "Rejected - Speed Limit hit (Diff: ${String.format('%.1f', drop)} over ${String.format('%.1f', hours)}h)"
        log.warn "${device.displayName} Hourly Scan -> ${statusText}"
    } else {
        logDebug "Hourly Scan -> Success (${rawMoist})"
    }

    state.history << [
        time: now,
        rawMoisture: rawMoist,
        statusText: statusText,
        isSpeedOutlier: isSpeedOutlier,
        isDraining: false
    ]

    // A drop that holds steady for 5 scans is a real step change (sensor moved/bumped), not a glitch
    if (isSpeedOutlier && state.history.size() >= 5) {
        def recent = state.history[-5..-1]
        def levels = recent*.rawMoisture
        if (recent.every { it.isSpeedOutlier ?: false } && (levels.max() - levels.min()) <= 2.0) {
            log.warn "${device.displayName} Hourly Scan -> 5 steady readings after a drop. Starting new baseline at ${rawMoist}"
            state.history = recent.collect { entry ->
                entry + [isSpeedOutlier: false, statusText: "Accepted - New baseline after step change"]
            }
            clearLearnedFc()
        }
    }

    trimHistory(now)
    updatePlantCard(true)
}

private void trimHistory(long now) {
    long cutoff = now - 259200000 // 3 Days
    state.history = state.history.findAll { it.time >= cutoff }
}

private List validPoints() {
    (state.history ?: []).findAll { !(it.isSpeedOutlier ?: false) && !(it.isDraining ?: false) }
}

// Gaps are measured between all scans, so a run of rejected readings doesn't count as one
private List etWindow() {
    List history = state.history ?: []
    int start = 0
    for (int i = history.size() - 1; i > 0; i--) {
        if ((history[i].time as long) - (history[i - 1].time as long) > ET.gapMs) {
            start = i
            break
        }
    }
    history.drop(start).findAll { !(it.isSpeedOutlier ?: false) && !(it.isDraining ?: false) }
}

private Map windowRate(List points) {
    if (points.size() < ET.minPoints) return null
    long spanMs = (points[-1].time as long) - (points[0].time as long)
    if (spanMs <= 0) return null
    long t0 = points[0].time as long
    List<Double> days = points.collect { ((it.time as long) - t0) / 86400000.0d }
    List<Double> moist = points.collect { it.rawMoisture as double }
    double meanDay = days.sum() / days.size()
    double meanMoist = moist.sum() / moist.size()
    double cov = 0.0d
    double var = 0.0d
    days.eachWithIndex { double d, int i ->
        cov += (d - meanDay) * (moist[i] - meanMoist)
        var += (d - meanDay) * (d - meanDay)
    }
    [rate: Math.max(0.0d, -cov / var), spanMs: spanMs]
}

private void saveCycleRate() {
    Map cycle = windowRate(etWindow())
    if (cycle == null || cycle.spanMs < ET.minCycleMs || cycle.rate <= 0) {
        logInfo "drying cycle was too short or flat to learn from. Keeping the saved ET rate."
        return
    }
    double days = cycle.spanMs / 86400000.0d
    if (state.savedEtRate == null) {
        state.savedEtRate = cycle.rate
        state.savedEtDays = days
        logInfo "learned an ET rate of ${String.format('%.1f', cycle.rate)}%/day from ${String.format('%.1f', days)} days of drying"
        return
    }
    double oldRate = state.savedEtRate as double
    double oldDays = state.savedEtDays != null ? state.savedEtDays as double : 1.0d
    state.savedEtRate = (oldRate * oldDays + cycle.rate * days) / (oldDays + days)
    state.savedEtDays = Math.min(ET.maxSavedDays as double, oldDays + days)
    logInfo "this cycle dried at ${String.format('%.1f', cycle.rate)}%/day over ${String.format('%.1f', days)} days. Saved ET rate ${String.format('%.1f', oldRate)} -> ${String.format('%.1f', state.savedEtRate as double)}%/day"
}

private void startDrainage(double moisture, long now) {
    state.wateredAt = now
    state.drainPeak = [time: now, moisture: moisture]
    state.drainSamples = [[time: now, moisture: moisture]]
    logDebug "Drainage -> Started at ${moisture}"
}

private void trackDrainage(double moisture, long now) {
    if (now - (state.wateredAt as long) >= DRAIN.maxMs) {
        finishDrainage(moisture, now)
        return
    }
    if (moisture > state.drainPeak.moisture) {
        state.drainPeak = [time: now, moisture: moisture]
    }
    def samples = state.drainSamples + [[time: now, moisture: moisture]]
    long windowStart = now - DRAIN.windowMs
    def older = samples.findAll { (it.time as long) <= windowStart }
    state.drainSamples = (older ? [older[-1]] : []) + samples.findAll { (it.time as long) > windowStart }

    if (now - (state.drainPeak.time as long) < DRAIN.windowMs || !older) return
    def ref = older[-1]
    double ratePerHr = (ref.moisture - moisture) / ((now - (ref.time as long)) / 3600000.0d)
    logDebug "Drainage -> ${moisture}, dropping ${String.format('%.2f', ratePerHr)}%/hr"
    if (ratePerHr < DRAIN.endRatePerHr) finishDrainage(moisture, now)
}

private void finishDrainage(double moisture, long now) {
    double drained = state.drainPeak.moisture - moisture
    long mins = Math.round((now - (state.wateredAt as long)) / 60000.0d)
    boolean timedOut = now - (state.wateredAt as long) >= DRAIN.maxMs
    String drainedText = "drained ${String.format('%.1f', drained)} over ${mins} min"
    String statusText = timedOut ? "Drainage timed out - ${drainedText}" : "Drainage complete - ${drainedText}"

    if (state.history == null) state.history = []
    state.history << [time: now, rawMoisture: moisture, statusText: statusText, isSpeedOutlier: false, isDraining: false]
    ["wateredAt", "drainPeak", "drainSamples"].each { state.remove(it) }

    if (timedOut) {
        logInfo "drainage still going after ${mins} min, no field capacity sample taken"
    } else if (drained < DRAIN.soakMinDrop) {
        logInfo "drainage done, ${drainedText}. Looks like a partial watering, no field capacity sample taken"
    } else {
        recordFcSample(moisture, drainedText)
    }
}

private void recordFcSample(double moisture, String drainedText) {
    List samples = ((state.fcSamples ?: []) + [moisture]).takeRight(DRAIN.fcSamplesKept)
    state.fcSamples = samples
    List sorted = samples.sort(false)
    int n = sorted.size()
    double median = n % 2 ? sorted[n.intdiv(2)] : (sorted[n.intdiv(2) - 1] + sorted[n.intdiv(2)]) / 2.0
    BigDecimal learned = new BigDecimal(median).setScale(1, RoundingMode.HALF_UP)
    String soaks = n == 1 ? "1 soak" : "${n} soaks"
    sendEvent(name: "learnedFieldCapacity", value: learned, unit: "%", descriptionText: "${device.displayName} learned field capacity is ${learned} from ${soaks} (shadow, not in use)")
    sendEvent(name: "debugFcSampleCount", value: n)
    logInfo "soak detected, ${drainedText}, settled at ${moisture}. Learned field capacity now ${learned} from ${soaks} (your setting: ${pref('fieldCapacity')})"
}

private void clearLearnedFc() {
    state.remove("fcSamples")
    ["learnedFieldCapacity", "debugFcSampleCount"].each { device.deleteCurrentState(it) }
}

def updatePlantCard(boolean isHourlyCron = false) {
    boolean hasReading = state.moisture != null
    int moist = device.currentValue("humidity") != null ? device.currentValue("humidity").toInteger() : 0
    double rawMoist = hasReading ? state.moisture.toDouble() : 0.0
    def temp = device.currentValue("temperature") != null ? device.currentValue("temperature") : "--"
    int battVal = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
    
    int threshold = pref("permanentWiltingPoint").toInteger()
    int capacity = pref("fieldCapacity").toInteger()
    int waterTarget = pref("wateringThreshold").toInteger()

    long now = new Date().getTime()
    if (state.history == null) state.history = hasReading ? [[time: now, rawMoisture: rawMoist, statusText: "Success", isSpeedOutlier: false, isDraining: false]] : []

    // Find the last valid point for watering detection (so speed glitches don't reset memory)
    def validHistory = validPoints()
    def lastValidPoint = validHistory ? validHistory[-1] : (state.history ? state.history[-1] : null)

    if (state.drainSamples == null && lastValidPoint != null && rawMoist > (lastValidPoint.rawMoisture + 5)) {
        logInfo "watered. Calculating previous ET rate and resetting memory."
        saveCycleRate()
        state.history = []
        startDrainage(rawMoist, now)
    }

    // Visual ring gauge maps the distance between Wilting Point (empty) and Field Capacity (full)
    int displayPct = 0
    if (capacity <= threshold) {
        displayPct = rawMoist > threshold ? 100 : 0
    } else if (rawMoist > threshold) {
        // Calculate percentage based on raw capacity instead of an arbitrary 100
        displayPct = Math.round(((rawMoist - threshold) / (capacity - threshold).toDouble()) * 100).toInteger()
        
        // Clamp the visual value between 0% and 100% (so watering spikes don't break the ring SVG)
        displayPct = Math.max(0, Math.min(100, displayPct))
    }
    sendEvent(name: "waterRemaining", value: displayPct, unit: "%")

    String daysLeft = "--"
    double dropPerDay = 0.0

    List windowPoints = etWindow()
    Map live = windowRate(windowPoints)
    Double saved = state.savedEtRate != null ? state.savedEtRate as double : null
    String etMath
    if (live != null && saved != null) {
        // Short windows swing with the day/night cycle, so lean on the saved rate until a full day is covered
        double w = Math.min(1.0d, live.spanMs / (ET.blendMs as double))
        dropPerDay = w * live.rate + (1.0d - w) * saved
        etMath = "live ${String.format('%.1f', live.rate)} over ${String.format('%.1f', live.spanMs / 3600000.0d)}h, saved ${String.format('%.1f', saved)}, live weight ${Math.round(w * 100)}%"
    } else if (live != null) {
        dropPerDay = live.rate
        etMath = "live ${String.format('%.1f', live.rate)} over ${String.format('%.1f', live.spanMs / 3600000.0d)}h, no saved rate yet"
    } else if (saved != null) {
        dropPerDay = saved
        etMath = "${windowPoints.size()}/${ET.minPoints} scans since the last gap or watering, using saved rate"
    } else {
        etMath = "${windowPoints.size()}/${ET.minPoints} scans since the last gap or watering, gathering data"
    }

    if (isHourlyCron) {
        logDebug "ET Math -> ${String.format('%.1f', dropPerDay)}%/day (${etMath})"
    }

    // Calculate estimated days remaining before hitting the Watering Threshold (Gas Light)
    if (!hasReading) {
        daysLeft = "--"
    } else if (dropPerDay > 0 && rawMoist > waterTarget) {
        daysLeft = formatDays((rawMoist - waterTarget) / dropPerDay)
    } else if (rawMoist <= waterTarget) {
        daysLeft = "0.0"
    } else if (live != null) {
        // No drop of even one sensor step over the window caps the rate, which gives a guaranteed minimum
        double maxRate = ET.stepPct / (live.spanMs / 86400000.0d)
        int minDays = Math.floor((rawMoist - waterTarget) / maxRate).toInteger()
        daysLeft = "${Math.min(minDays, ET.maxDaysShown as int)}+"
    }

    sendEvent(name: "etRate", value: new BigDecimal(dropPerDay).setScale(1, RoundingMode.HALF_UP), unit: "%/day")
    sendEvent(name: "daysRemaining", value: daysLeft, unit: "days")

    // UI Variables required by the HTML string...
    // Styling lives in dashboard CSS to keep plantCard under the 1024-char tile limit
    String cleanName = device.displayName.replace(" Soil Sensor", "").replace(" Moisture Sensor", "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    String batt = battVal.toString()
    String battClass = battVal <= 20 ? "batt-low" : ""
    int battWidth = battVal
    boolean isDry = hasReading && rawMoist <= waterTarget
    if (hasReading) sendEvent(name: "contact", value: isDry ? "open" : "closed")
    String updated = "--"
    if (state.lastReportAt != null) {
        Date seen = new Date(state.lastReportAt as long)
        String fmt = seen.format("yyyyMMdd", location.timeZone) == new Date(now).format("yyyyMMdd", location.timeZone) ? "h:mm a" : "MM/dd h:mm a"
        updated = seen.format(fmt, location.timeZone)
    }
    String staleClass = device.currentValue("healthStatus") == "offline" ? " is-stale" : ""
    String stateClass = ""
    String centerBig = daysLeft
    String centerSmall = "DAYS LEFT"

    if (isDry) {
        // The water drop icon is drawn by the dashboard CSS to save card characters
        stateClass = "is-dry"
        centerBig = ""
        centerSmall = "WATER NOW"
    } else if (daysLeft == "--" || daysLeft == "0.0") {
        stateClass = "is-calc"
        centerBig = "CALC"
        centerSmall = "GATHERING DATA"
    }

    String cardHtml = """<div class="pw-wrap ${stateClass}${staleClass}"><div class="pw-head"><a href="/device/edit/${device.id}" target="_blank" class="pw-title" title="Click to edit threshold">${cleanName}</a><div class="pw-batt ${battClass}"><div class="pw-b-icon"><div class="pw-b-fill" style="width:${battWidth}%;"></div></div><div class="pw-b-txt">${batt}%</div></div></div><div class="pw-hero"><div class="pw-ring" style="--val:${displayPct};"><div class="pw-ring-in"><div class="pw-val">${centerBig}</div><div class="pw-pct">${centerSmall}</div></div></div></div><div class="pw-foot"><div class="pw-metrics"><span class="pw-temp">💧 ${moist}%</span><span class="pw-temp">🌡 ${temp}°${location.temperatureScale}</span></div></div><div class="pw-upd">Updated ${updated}</div></div>"""
    sendEvent(name: "plantCard", value: cardHtml)
}

private String formatDays(double days) {
    if (days > ET.maxDaysShown) return "${ET.maxDaysShown}+"
    days < 10 ? String.format("%.1f", days) : Math.round(days).toString()
}

def logHistory() {
    def history = state.history ?: []
    if (!history) {
        log.info "${device.displayName} history is empty"
        return
    }
    String rows = history.collect { entry ->
        String color = (entry.isSpeedOutlier ?: false) ? "#d9534f" : ((entry.isDraining ?: false) ? "#888" : null)
        String readableDate = new Date(entry.time as long).format("MM/dd h:mm a")
        "<tr${color ? " style='color:${color}'" : ""}><td>${readableDate}</td><td>${entry.rawMoisture}</td><td>${entry.statusText ?: 'Success'}</td></tr>"
    }.join()
    log.info "${device.displayName} history, oldest first:<table style='border-spacing:12px 0'><tr><th>Time</th><th>Moisture</th><th>Status</th></tr>${rows}</table>"
}

def updated() {
    log.info "${device.displayName} preferences updated"
    int fc = pref("fieldCapacity").toInteger()
    int wt = pref("wateringThreshold").toInteger()
    int pwp = pref("permanentWiltingPoint").toInteger()
    if (pwp >= fc) {
        log.warn "${device.displayName}: Permanent Wilting Point (${pwp}) must be below Field Capacity (${fc}). The ring gauge will only show full or empty until this is fixed."
    } else if (wt <= pwp || wt >= fc) {
        log.warn "${device.displayName}: Watering Threshold (${wt}) should be between Permanent Wilting Point (${pwp}) and Field Capacity (${fc})."
    }
    if (state.moisture == null && device.currentValue("rawMoisture") != null) {
        state.moisture = device.currentValue("rawMoisture")
    }
    ["historyTable", "soilMoisture", "rawMoisture"].each { device.deleteCurrentState(it) }
    ["moistOffset", "maxDaysLimit"].each { device.removeSetting(it) }
    scheduleHistory()
    if (logEnable) runIn(86400, "logsOff")
    updatePlantCard()
}

def resetSensor(String resetCalibration = "No") {
    log.info "${device.displayName} reset: clearing history, learned ET rate, and learned field capacity"
    ["history", "savedEtRate", "savedEtDays", "wateredAt", "drainPeak", "drainSamples"].each { state.remove(it) }
    clearLearnedFc()

    if (resetCalibration == "Yes") {
        log.info "${device.displayName} reset: restoring calibration settings to defaults"
        ["fieldCapacity", "wateringThreshold", "permanentWiltingPoint"].each {
            device.updateSetting(it, [value: DEFAULTS[it], type: "number"])
        }
        device.updateSetting("tempOffset", [value: DEFAULTS.tempOffset, type: "decimal"])
    }

    // Deferred so updatePlantCard sees the restored settings rather than this execution's cached copy
    runIn(1, "updatePlantCard")
}

def refresh() {
    return zigbee.readAttribute(0x0405, 0x0000) + 
           zigbee.readAttribute(0x0402, 0x0000) + 
           zigbee.readAttribute(0x0001, 0x0020) +
           zigbee.readAttribute(0x0001, 0x0021)
}

def configure() {
    scheduleHistory()
    return zigbee.configureReporting(0x0405, 0x0000, 0x21, 10, 300, 100) + 
           zigbee.configureReporting(0x0402, 0x0000, 0x29, 10, 300, 50) +  
           zigbee.batteryConfig() +
           zigbee.configureReporting(0x0001, 0x0021, 0x20, 30, 21600, 2) +
           refresh()
}
