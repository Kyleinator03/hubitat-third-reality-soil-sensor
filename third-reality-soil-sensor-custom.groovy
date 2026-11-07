/**
 *  Third Reality Soil Moisture Sensor (with Smart Plant Card & ET Prediction)
 *
 *  Version: 1.8.0
 *  https://github.com/Kyleinator03/hubitat-third-reality-soil-sensor
 *  License: GPL-3.0
 */

import groovy.transform.Field
import java.math.RoundingMode

@Field static final Map DEFAULTS = [fieldCapacity: 90, wateringThreshold: 50, tempOffset: 0.0]
@Field static final Map SETTLE = [windowMs: 21600000L, jitterMs: 600000L, maxMs: 172800000L, ceiling: 95.0, fcSlack: 1.0, etMultiple: 3.0, minLimit: 3.0, noSavedLimit: 6.0, soakMinDrop: 2.0, fcSamplesKept: 5, peakQuietSec: 120, sensorMax: 99.9]
@Field static final Map ET = [minPoints: 5, gapMs: 10800000L, minCycleMs: 43200000L, maxSavedDays: 7.0, stepPct: 0.15, maxDaysShown: 99]

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

        command "logHistory"
        command "resetSensor", [[name: "Also reset calibration settings?", type: "ENUM", constraints: ["No", "Yes"], description: "Yes also clears Field Capacity so it's learned again, and restores Watering Threshold and the temperature offset to their defaults"]]

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0147Z", deviceJoinName: "Third Reality Soil Sensor"
        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0347Z", deviceJoinName: "Third Reality Soil Sensor Gen2"
    }

    preferences {
        input name: "fieldCapacity", type: "decimal", title: "Field Capacity (%)", description: "Leave blank to learn it from your waterings. The wettest the soil stays after draining, which is a full ring. Enter a value only to override what's learned.", required: false
        input name: "wateringThreshold", type: "decimal", title: "Watering Threshold (%)", description: "The moisture level where the plant actually needs to be watered. This is the empty point of the ring, triggers the 'WATER NOW' alert, and is the target for the Days Left countdown. (e.g., 50)", defaultValue: DEFAULTS.wateringThreshold, required: true
        input name: "tempOffset", type: "decimal", title: "Temperature Offset (°)", description: "In your hub's temperature scale.", defaultValue: DEFAULTS.tempOffset, required: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", description: "Hourly check-ins, waterings, settling, and learned values.", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Every reading, scan, and calculation. Turns itself off after 24 hours.", defaultValue: false
    }
}

private pref(String name) {
    settings[name] != null ? settings[name] : DEFAULTS[name]
}

private BigDecimal decPref(String name) {
    new BigDecimal(pref(name).toString())
}

private BigDecimal learnedFc() {
    List sorted = (state.fcSamples ?: []).collect { it as double }.sort()
    int n = sorted.size()
    if (n == 0) return null
    double median = n % 2 ? sorted[n.intdiv(2)] : (sorted[n.intdiv(2) - 1] + sorted[n.intdiv(2)]) / 2.0
    new BigDecimal(median).setScale(1, RoundingMode.HALF_UP)
}

private boolean fcKnown() {
    settings.fieldCapacity != null || state.fcSamples
}

private BigDecimal fcInUse() {
    settings.fieldCapacity != null ? new BigDecimal(settings.fieldCapacity.toString()) : (learnedFc() ?: new BigDecimal(DEFAULTS.fieldCapacity.toString()))
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName} ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable != false) log.info "${device.displayName} ${msg}"
}

private void logAlways(String msg) {
    log.info "${device.displayName} ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName} ${msg}"
}

private String fmt1(Number n) {
    String.format('%.1f', n as double)
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    logAlways "debug logging turned off"
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
    String health = device.currentValue("healthStatus")
    if (health != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
        if (health == "offline") logAlways "is back online"
    }
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.value == null) return

    if (descMap.clusterInt == 0x0405 && descMap.attrInt == 0x0000) {
        def moisture = Integer.parseInt(descMap.value, 16) / 100.0
        state.moisture = moisture
        int rounded = Math.min(100, Math.max(0, Math.round(moisture).toInteger()))
        boolean changed = device.currentValue("humidity") != rounded
        sendEvent(name: "humidity", value: rounded, unit: "%", descriptionText: "${device.displayName} soil moisture is ${rounded}%")
        if (changed) logDebug "reading: soil moisture is ${rounded}% (${moisture})"
        if (state.settling) trackPeak(moisture as double, now())
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
        if (changed) logDebug "reading: temperature is ${rounded}°${scale}"
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
    if (changed) logDebug "reading: battery is ${pct}%"
    updatePlantCard()
}

def updateHistory() {
    long now = new Date().getTime()
    if (state.moisture == null) {
        logDebug "scan: skipped, no moisture reading yet"
        return
    }
    if (state.lastReportAt != null && now - (state.lastReportAt as long) > 7200000) {
        if (device.currentValue("healthStatus") != "offline") {
            logWarn "is offline, nothing heard from the sensor in over 2 hours. Hourly scans are paused until it reports again. Check the sensor's battery and signal."
            sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        } else {
            logDebug "scan: skipped, sensor still offline"
        }
        updatePlantCard()
        return
    }
    def rawMoist = state.moisture.toDouble()

    if (state.history == null) state.history = []
    if (state.settleModel == null) migrateToSettling()

    if (state.settling) {
        state.history << [time: now, rawMoisture: rawMoist, statusText: "Settling", isSpeedOutlier: false, isDraining: true]
        logDebug "scan: settling (${rawMoist})"
        checkSettled(now)
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
        statusText = "Rejected - Speed Limit hit (Diff: ${fmt1(drop)} over ${fmt1(hours)}h)"
        logDebug "scan: rejected ${rawMoist}, dropped ${fmt1(drop)} over ${fmt1(hours)}h"
    } else {
        logDebug "scan: accepted ${rawMoist}"
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
            logWarn "held steady for 5 scans after a sudden drop, starting a new baseline at ${rawMoist}${state.fcSamples ? ' and clearing learned field capacity' : ''}"
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

private String saveCycleRate() {
    Map cycle = windowRate(etWindow())
    if (cycle == null || cycle.spanMs < ET.minCycleMs || cycle.rate <= 0) {
        String kept = state.savedEtRate != null ? "keeping saved ET ${fmt1(state.savedEtRate)}%/day" : "no saved ET rate yet"
        return "Last cycle was too short or flat to learn from, ${kept}"
    }
    double days = cycle.spanMs / 86400000.0d
    if (state.savedEtRate == null) {
        state.savedEtRate = cycle.rate
        state.savedEtDays = days
        return "Learned ET ${fmt1(cycle.rate)}%/day from ${fmt1(days)} days of drying"
    }
    double oldRate = state.savedEtRate as double
    double oldDays = state.savedEtDays != null ? state.savedEtDays as double : 1.0d
    state.savedEtRate = (oldRate * oldDays + cycle.rate * days) / (oldDays + days)
    state.savedEtDays = Math.min(ET.maxSavedDays as double, oldDays + days)
    "Last cycle dried ${fmt1(cycle.rate)}%/day over ${fmt1(days)} days, saved ET ${fmt1(oldRate)} → ${fmt1(state.savedEtRate)}%/day"
}

private void startSettling(double moisture, long now) {
    state.wateredAt = now
    state.drainPeak = [time: now, moisture: moisture]
    state.settling = true
    ["settleWarned", "skipFcSample"].each { state.remove(it) }
    logDebug "settle: started at ${moisture}"
    waitForPeak(moisture)
}

// Announced once the reading tops out, so multi-pour waterings report the real peak rather than the first rise
private void waitForPeak(double moisture) {
    if (moisture >= SETTLE.sensorMax) {
        announceWatering()
    } else {
        runIn(SETTLE.peakQuietSec, "announceWatering")
    }
}

def announceWatering() {
    unschedule("announceWatering")
    if (state.wateredFrom == null) return
    double peak = state.drainPeak?.moisture != null ? state.drainPeak.moisture as double : state.moisture.toDouble()
    logInfo "watered (${fmt1(state.wateredFrom)} → ${fmt1(peak)}). ${state.wateredNote}"
    ["wateredFrom", "wateredNote"].each { state.remove(it) }
}

private void trackPeak(double moisture, long now) {
    if (state.drainPeak?.moisture != null && moisture <= (state.drainPeak.moisture as double)) return
    state.drainPeak = [time: now, moisture: moisture]
    if (state.wateredFrom != null) waitForPeak(moisture)
}

private Map settleWindow(List rows, double limit, Double cap) {
    for (int end = 0; end < rows.size(); end++) {
        long endTime = rows[end].time as long
        int start = rows.findIndexOf { (it.time as long) >= endTime - SETTLE.windowMs }
        List stretch = rows[start..end]
        if (endTime - (stretch[0].time as long) < SETTLE.windowMs - SETTLE.jitterMs) continue
        boolean level = stretch.every { double m = it.rawMoisture as double; m < SETTLE.ceiling && (cap == null || m <= cap) }
        if (!level) continue
        Map fit = windowRate(stretch)
        if (fit != null && fit.rate <= limit) return [start: start, end: end, rate: fit.rate]
    }
    null
}

private void checkSettled(long now) {
    List rows = state.history
    Double saved = state.savedEtRate != null ? state.savedEtRate as double : null
    double limit = saved != null ? Math.max(SETTLE.etMultiple * saved, SETTLE.minLimit as double) : SETTLE.noSavedLimit as double
    long elapsed = now - (state.wateredAt as long)
    boolean timedOut = elapsed >= SETTLE.maxMs
    // Past the timeout the field capacity check is dropped, so a capacity that has risen can still be learned
    Double cap = fcKnown() && !timedOut ? (fcInUse() + SETTLE.fcSlack) as double : null
    Map found = settleWindow(rows, limit, cap)
    if (found != null) {
        finishSettling(found, timedOut && fcKnown())
        return
    }
    logDebug "settle: not yet, needs 6h below ${fmt1(cap != null ? cap : SETTLE.ceiling)} dropping under ${fmt1(limit)}%/day"
    if (timedOut && !state.settleWarned) {
        state.settleWarned = true
        logWarn "still not settled ${Math.round(elapsed / 3600000.0d)}h after watering (reading ${fmt1(rows[-1].rawMoisture)}). Water may be pooled at the sensor, so try wiggling it. Days left uses the saved drying rate until it settles."
    }
}

private void finishSettling(Map found, boolean viaTimeout) {
    announceWatering()
    List rows = state.history
    double sample = rows[found.start].rawMoisture as double
    double peak = Math.max(state.drainPeak?.moisture != null ? state.drainPeak.moisture as double : 0.0d, rows*.rawMoisture.max() as double)
    long hrs = Math.round(((rows[found.end].time as long) - (state.wateredAt as long)) / 3600000.0d)
    List updatedRows = []
    rows.eachWithIndex { row, int i ->
        updatedRows << (i < found.end ? row : row + [isDraining: false, statusText: i == found.end ? "Settled - measuring ET from here" : "Success"])
    }
    state.history = updatedRows

    double dropped = peak - sample
    String settled = "soil settled at ${fmt1(sample)}, ${hrs}h after watering (dropped ${fmt1(dropped)} from the ${fmt1(peak)} peak). Measuring ET from here."
    if (state.skipFcSample) {
        logInfo settled
    } else if (dropped < SETTLE.soakMinDrop) {
        logInfo "${settled} Looks like a partial watering, no field capacity sample"
    } else {
        recordFcSample(sample, settled)
    }
    if (viaTimeout && settings.fieldCapacity != null) {
        logWarn "only settled after ignoring the Field Capacity setting (${settings.fieldCapacity}). It may be set too low, or clear it to let the driver learn it"
    }
    ["wateredAt", "drainPeak", "settling", "settleWarned", "skipFcSample"].each { state.remove(it) }
}

private void recordFcSample(double moisture, String settled) {
    state.fcSamples = ((state.fcSamples ?: []) + [moisture]).takeRight(SETTLE.fcSamplesKept)
    BigDecimal learned = learnedFc()
    int n = state.fcSamples.size()
    String soaks = n == 1 ? "1 soak" : "${n} soaks"
    sendEvent(name: "learnedFieldCapacity", value: learned, unit: "%", descriptionText: "${device.displayName} learned field capacity is ${learned} from ${soaks}")
    String use = settings.fieldCapacity != null ? "your setting of ${settings.fieldCapacity} is in use" : "in use"
    logInfo "${settled} Learned field capacity ${learned} from ${soaks} (${use})"
}

private void clearLearnedFc() {
    state.remove("fcSamples")
    device.deleteCurrentState("learnedFieldCapacity")
}

// One-time upgrade from the 1.7 drainage detector, so a watering still in the history is re-judged by the settle check
private void migrateToSettling() {
    state.settleModel = 1
    device.deleteCurrentState("debugFcSampleCount")
    List rows = state.history ?: []
    boolean midDrain = state.drainSamples != null
    state.remove("drainSamples")
    if (!midDrain && !(rows && (rows[0].statusText ?: "").startsWith("Drain"))) return
    if (!midDrain) {
        state.wateredAt = rows[0].time
        state.drainPeak = [time: rows[0].time, moisture: rows*.rawMoisture.max()]
        if (rows.any { oldFcSampled(it.statusText) }) state.skipFcSample = true
    }
    state.history = rows.collect { it + [isDraining: true, isSpeedOutlier: false, statusText: "Settling"] }
    state.settling = true
    logInfo "upgraded: re-checking the last watering with the new settle check"
}

private boolean oldFcSampled(String text) {
    if (!(text ?: "").startsWith("Drainage complete - drained ")) return false
    (text.tokenize(" ")[4] as double) >= SETTLE.soakMinDrop
}

def updatePlantCard(boolean isHourlyCron = false) {
    if (state.settleModel == null) migrateToSettling()
    boolean hasReading = state.moisture != null
    int moist = device.currentValue("humidity") != null ? device.currentValue("humidity").toInteger() : 0
    double rawMoist = hasReading ? state.moisture.toDouble() : 0.0
    def temp = device.currentValue("temperature") != null ? device.currentValue("temperature") : "--"
    int battVal = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
    
    BigDecimal capacity = fcInUse()
    BigDecimal waterTarget = decPref("wateringThreshold")

    long now = new Date().getTime()
    if (state.history == null) state.history = hasReading ? [[time: now, rawMoisture: rawMoist, statusText: "Success", isSpeedOutlier: false, isDraining: false]] : []

    // Find the last valid point for watering detection (so speed glitches don't reset memory)
    def validHistory = validPoints()
    def lastValidPoint = validHistory ? validHistory[-1] : (state.history ? state.history[-1] : null)

    if (lastValidPoint != null && rawMoist > (lastValidPoint.rawMoisture + 5)) {
        state.wateredFrom = lastValidPoint.rawMoisture
        state.wateredNote = saveCycleRate()
        state.history = []
        startSettling(rawMoist, now)
    }
    boolean settling = state.settling == true

    int displayPct = 0
    if (capacity <= waterTarget) {
        displayPct = rawMoist > waterTarget ? 100 : 0
    } else if (rawMoist > waterTarget) {
        displayPct = Math.round(((rawMoist - waterTarget) / (capacity - waterTarget)) * 100).toInteger()
        // Clamped so readings above field capacity right after watering don't overflow the ring
        displayPct = Math.max(0, Math.min(100, displayPct))
    }
    sendEvent(name: "waterRemaining", value: displayPct, unit: "%", descriptionText: "${device.displayName} water remaining is ${displayPct}%")

    String daysLeft = "--"
    double dropPerDay = 0.0

    List windowPoints = etWindow()
    Map live = settling ? null : windowRate(windowPoints)
    Double saved = state.savedEtRate != null ? state.savedEtRate as double : null
    String etMath
    if (settling) {
        if (saved != null) dropPerDay = saved
        etMath = saved != null ? "settling after watering, using saved rate" : "settling after watering, no saved rate yet"
    } else if (live != null && saved != null) {
        // Weighted by days of data, so a short live window can't outvote a rate learned over several days
        double liveDays = live.spanMs / 86400000.0d
        double savedDays = state.savedEtDays != null ? state.savedEtDays as double : 1.0d
        double w = liveDays / (liveDays + savedDays)
        dropPerDay = w * live.rate + (1.0d - w) * saved
        etMath = "live ${fmt1(live.rate)} over ${fmt1(live.spanMs / 3600000.0d)}h, saved ${fmt1(saved)} over ${fmt1(savedDays)} days, live weight ${Math.round(w * 100)}%"
    } else if (live != null) {
        dropPerDay = live.rate
        etMath = "live ${fmt1(live.rate)} over ${fmt1(live.spanMs / 3600000.0d)}h, no saved rate yet"
    } else if (saved != null) {
        dropPerDay = saved
        etMath = "${windowPoints.size()}/${ET.minPoints} scans since the last gap or watering, using saved rate"
    } else {
        etMath = "${windowPoints.size()}/${ET.minPoints} scans since the last gap or watering, gathering data"
    }

    if (isHourlyCron) {
        logDebug "et: ${fmt1(dropPerDay)}%/day (${etMath})"
    }

    // Calculate estimated days remaining before hitting the Watering Threshold (Gas Light)
    if (!hasReading) {
        daysLeft = "--"
    } else if (dropPerDay > 0 && rawMoist > waterTarget) {
        // Water above field capacity is still draining away, so it doesn't buy the plant extra days
        double from = settling ? Math.min(rawMoist, capacity as double) : rawMoist
        daysLeft = formatDays(Math.max(0.0d, from - (waterTarget as double)) / dropPerDay)
    } else if (rawMoist <= waterTarget) {
        daysLeft = "0.0"
    } else if (live != null) {
        // No drop of even one sensor step over the window caps the rate, which gives a guaranteed minimum
        double maxRate = ET.stepPct / (live.spanMs / 86400000.0d)
        int minDays = Math.floor((rawMoist - waterTarget) / maxRate).toInteger()
        daysLeft = "${Math.min(minDays, ET.maxDaysShown as int)}+"
    }

    BigDecimal etRounded = new BigDecimal(dropPerDay).setScale(1, RoundingMode.HALF_UP)
    sendEvent(name: "etRate", value: etRounded, unit: "%/day", descriptionText: "${device.displayName} ET rate is ${etRounded}%/day")
    sendEvent(name: "daysRemaining", value: daysLeft, unit: "days", descriptionText: "${device.displayName} days remaining is ${daysLeft}")

    // UI Variables required by the HTML string...
    // Styling lives in dashboard CSS to keep plantCard under the 1024-char tile limit
    String cleanName = device.displayName.replace(" Soil Sensor", "").replace(" Moisture Sensor", "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    String batt = battVal.toString()
    String battClass = battVal <= 20 ? "batt-low" : ""
    int battWidth = battVal
    boolean isDry = hasReading && rawMoist <= waterTarget
    if (hasReading) {
        String contact = isDry ? "open" : "closed"
        String needs = isDry ? "needs water" : "has enough water"
        if (device.currentValue("contact") != contact) {
            logInfo(isDry ? "needs water, moisture ${fmt1(rawMoist)} is at or below the watering threshold (${waterTarget})" : needs)
        }
        sendEvent(name: "contact", value: contact, descriptionText: "${device.displayName} ${needs}")
    }

    if (isHourlyCron && hasReading) {
        String outlook = isDry ? "needs water" : (daysLeft == "--" || daysLeft == "0.0" ? "gathering data for days left" : "${daysLeft} days left")
        String et = dropPerDay > 0 ? "ET ${fmt1(dropPerDay)}%/day, " : ""
        String draining = settling ? "settling, " : ""
        logInfo "${draining}${fmt1(rawMoist)}% moisture, ${temp}°${location.temperatureScale}, battery ${battVal}%. ${et}${outlook}"
    }
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
        logAlways "history is empty"
        return
    }
    String rows = history.collect { entry ->
        String color = (entry.isSpeedOutlier ?: false) ? "#d9534f" : ((entry.isDraining ?: false) ? "#888" : null)
        String readableDate = new Date(entry.time as long).format("MM/dd h:mm a")
        "<tr${color ? " style='color:${color}'" : ""}><td>${readableDate}</td><td>${entry.rawMoisture}</td><td>${entry.statusText ?: 'Success'}</td></tr>"
    }.join()
    logAlways "history, oldest first:<table style='border-spacing:12px 0'><tr><th>Time</th><th>Moisture</th><th>Status</th></tr>${rows}</table>"
}

def updated() {
    logAlways "preferences updated"
    BigDecimal fc = fcInUse()
    BigDecimal wt = decPref("wateringThreshold")
    if (wt >= fc) {
        logWarn "settings problem: Watering Threshold (${wt}) must be below Field Capacity (${fc}). The ring gauge will only show full or empty until this is fixed."
    }
    if (state.moisture == null && device.currentValue("rawMoisture") != null) {
        state.moisture = device.currentValue("rawMoisture")
    }
    ["historyTable", "soilMoisture", "rawMoisture"].each { device.deleteCurrentState(it) }
    ["moistOffset", "maxDaysLimit", "permanentWiltingPoint"].each { device.removeSetting(it) }
    scheduleHistory()
    if (logEnable) runIn(86400, "logsOff") else unschedule("logsOff")
    updatePlantCard()
}

def resetSensor(String resetCalibration = "No") {
    logAlways "reset: clearing history, learned ET rate, and learned field capacity"
    ["history", "savedEtRate", "savedEtDays", "wateredAt", "drainPeak", "settling", "settleWarned", "skipFcSample", "wateredFrom", "wateredNote"].each { state.remove(it) }
    unschedule("announceWatering")
    clearLearnedFc()

    if (resetCalibration == "Yes") {
        logAlways "reset: restoring calibration settings to defaults"
        device.removeSetting("fieldCapacity")
        ["wateringThreshold", "tempOffset"].each {
            device.updateSetting(it, [value: DEFAULTS[it], type: "decimal"])
        }
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
