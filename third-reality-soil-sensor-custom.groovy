/**
 *  Third Reality Soil Moisture Sensor (with Smart Plant Card & ET Prediction)
 */

metadata {
    definition(name: "Third Reality Soil Moisture Sensor Custom", namespace: "custom", author: "Kyle", importUrl: "https://raw.githubusercontent.com/Kyleinator03/hubitat-third-reality-soil-sensor/main/third-reality-soil-sensor-custom.groovy") {
        capability "Configuration"
        capability "Refresh"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Contact Sensor"
        capability "Sensor"

        attribute "soilMoisture", "number"
        attribute "rawMoisture", "number"
        attribute "waterRemaining", "number"
        attribute "daysRemaining", "string"
        attribute "plantCard", "string"
        attribute "etRate", "number"
        attribute "historyTable", "string"

        command "resetSensor", [[name: "Also reset calibration settings?", type: "ENUM", constraints: ["No", "Yes"], description: "Yes also restores Field Capacity, Watering Threshold, Wilting Point, and both offsets to their defaults"]]

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0147Z", deviceJoinName: "Third Reality Soil Sensor"
        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0402,0405", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSM0347Z", deviceJoinName: "Third Reality Soil Sensor Gen2"
    }

    preferences {
        input name: "fieldCapacity", type: "number", title: "Field Capacity (Raw)", description: "The maximum amount of water the soil holds against gravity. Set this to the sensor's RAW moisture reading about an hour after a full soaking drain. (e.g., 72)", defaultValue: 75, required: true
        input name: "wateringThreshold", type: "number", title: "Watering Threshold (%)", description: "The raw moisture level where the plant actually needs to be watered. This triggers the 'WATER NOW' alert and is the target for the Days Left countdown. (e.g., 50)", defaultValue: 50, required: true
        input name: "permanentWiltingPoint", type: "number", title: "Permanent Wilting Point (%)", description: "The absolute zero limit where the plant can no longer extract water. This sets the 0% visual empty state of the blue ring gauge. (e.g., 30)", defaultValue: 30, required: true
        input name: "maxDaysLimit", type: "number", title: "Max Days Limit", description: "The maximum days left to display when the plant is stable or drying extremely slowly. (e.g., 21)", defaultValue: 14, required: true
        input name: "tempOffset", type: "decimal", title: "Temperature Offset (°F)", defaultValue: 0.0, required: false
        input name: "moistOffset", type: "number", title: "Moisture Offset (%)", description: "Flat offset to adjust your raw moisture reading (e.g., -10).", defaultValue: 0, required: false
    }
}

def installed() {
    runEvery1Hour(updateHistory)
}

def parse(String description) {
    state.lastReportAt = now()
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.value == null) return

    if (descMap.clusterInt == 0x0405 && descMap.attrInt == 0x0000) {
        def rawVal = Integer.parseInt(descMap.value, 16) / 100.0
        
        // Simple Flat Offset Calibration
        int offset = settings.moistOffset != null ? settings.moistOffset.toInteger() : 0
        int calibratedMoisture = Math.min(100, Math.max(0, Math.round(rawVal + offset).toInteger()))
        
        // Send the raw value so you can see it for future calibration
        sendEvent(name: "rawMoisture", value: rawVal)
        
        // Send the final calibrated percentages
        sendEvent(name: "humidity", value: calibratedMoisture, unit: "%")
        sendEvent(name: "soilMoisture", value: calibratedMoisture, unit: "%")
        updatePlantCard()
    } else if (descMap.clusterInt == 0x0402 && descMap.attrInt == 0x0000) {
        int rawInt = Integer.parseInt(descMap.value, 16)
        if (rawInt == 0x8000) return
        if (rawInt > 0x7FFF) rawInt -= 0x10000
        def rawTemp = rawInt / 100.0
        def fahrenheit = (rawTemp * 1.8) + 32 + (settings.tempOffset != null ? settings.tempOffset.toDouble() : 0.0)
        BigDecimal roundedF = new BigDecimal(fahrenheit).setScale(1, BigDecimal.ROUND_HALF_UP)
        sendEvent(name: "temperature", value: roundedF, unit: "°F")
        updatePlantCard()
    } else if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0021) {
        int rawPct = Integer.parseInt(descMap.value, 16)
        if (rawPct == 0xFF) return
        state.reportsBatteryPct = true
        int pct = Math.min(100, Math.max(0, Math.round(rawPct / 2.0).toInteger()))
        sendEvent(name: "battery", value: pct, unit: "%")
        updatePlantCard()
    } else if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020) {
        if (state.reportsBatteryPct) return
        def rawVolts = Integer.parseInt(descMap.value, 16) / 10.0
        int pct = Math.min(100, Math.max(0, Math.round((rawVolts - 1.0) / 0.5 * 100)))
        sendEvent(name: "battery", value: pct, unit: "%")
        updatePlantCard()
    }
}

def updateHistory() {
    long now = new Date().getTime()
    if (device.currentValue("rawMoisture") == null) {
        log.info "${device.displayName} Hourly Scan -> Skipped, no moisture reading yet"
        return
    }
    if (state.lastReportAt != null && now - (state.lastReportAt as long) > 7200000) {
        log.warn "${device.displayName} Hourly Scan -> Skipped, nothing heard from the sensor in over 2 hours. Check the sensor's battery and signal."
        return
    }
    // Read rawMoisture instead of humidity for the unchangeable history log
    def rawMoist = device.currentValue("rawMoisture").toDouble()

    if (state.history == null) state.history = []

    // Look back to find the last point that wasn't a speed outlier so math compares correctly
    def validHistory = state.history.findAll { !(it.isSpeedOutlier ?: false) }
    def lastValidPoint = validHistory.size() > 0 ? validHistory[-1] : null
    
    boolean isSpeedOutlier = false
    double drop = 0.0
    double hours = 1.0
    String statusText = "Success"

    if (lastValidPoint != null) {
        drop = lastValidPoint.rawMoisture - rawMoist
        // Floor at 1h so a rescheduled scan minutes after the last one doesn't inflate the rate
        hours = Math.max(1.0d, (now - (lastValidPoint.time as long)) / 3600000.0d)
        // Fast drops above Field Capacity are normal post-watering drainage
        boolean draining = lastValidPoint.isFcOutlier ?: false
        if (!draining && drop / hours > 2.0) {
            isSpeedOutlier = true
        }
    }

    int capacity = settings.fieldCapacity != null ? settings.fieldCapacity.toInteger() : 75
    boolean isFcOutlier = (!isSpeedOutlier && rawMoist > capacity)

    // Build the status string and log exactly once per hour
    if (isSpeedOutlier) {
        statusText = "Rejected - Speed Limit hit (Diff: ${String.format('%.1f', drop)} over ${String.format('%.1f', hours)}h)"
        log.warn "${device.displayName} Hourly Scan -> ${statusText}"
    } else if (isFcOutlier) {
        statusText = "Rejected - ${rawMoist} Above set FC (${capacity})"
        log.info "${device.displayName} Hourly Scan -> ${statusText}"
    } else {
        log.info "${device.displayName} Hourly Scan -> Success (${rawMoist})"
    }

    // Save all details into the array so the table builder doesn't have to guess
    state.history << [
        time: now, 
        rawMoisture: rawMoist, 
        statusText: statusText, 
        isSpeedOutlier: isSpeedOutlier, 
        isFcOutlier: isFcOutlier
    ]

    // A drop that holds steady for 5 scans is a real step change (sensor moved/bumped), not a glitch
    if (isSpeedOutlier && state.history.size() >= 5) {
        def recent = state.history[-5..-1]
        def levels = recent*.rawMoisture
        if (recent.every { it.isSpeedOutlier ?: false } && (levels.max() - levels.min()) <= 2.0) {
            log.warn "${device.displayName} Hourly Scan -> 5 steady readings after a drop. Starting new baseline at ${rawMoist}"
            state.history = recent.collect { entry ->
                boolean fc = entry.rawMoisture > capacity
                entry + [
                    isSpeedOutlier: false,
                    isFcOutlier: fc,
                    statusText: (fc ? "Rejected - ${entry.rawMoisture} Above set FC (${capacity})" : "Accepted - New baseline after step change").toString()
                ]
            }
        }
    }

    long cutoff = now - 259200000 // 3 Days
    state.history = state.history.findAll { it.time >= cutoff }

    // Pass 'true' to trigger the hourly ET math log
    updatePlantCard(true) 
}

def updatePlantCard(boolean isHourlyCron = false) {
    boolean hasReading = device.currentValue("rawMoisture") != null
    int moist = device.currentValue("humidity") != null ? device.currentValue("humidity").toInteger() : 0
    def rawMoist = device.currentValue("rawMoisture") != null ? device.currentValue("rawMoisture").toDouble() : 0.0
    def temp = device.currentValue("temperature") != null ? device.currentValue("temperature") : "--"
    int battVal = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
    
    // Preferences & Offset
    int offset = settings.moistOffset != null ? settings.moistOffset.toInteger() : 0
    int threshold = settings.permanentWiltingPoint != null ? settings.permanentWiltingPoint.toInteger() : 30
    int capacity = settings.fieldCapacity != null ? settings.fieldCapacity.toInteger() : 75
    int waterTarget = settings.wateringThreshold != null ? settings.wateringThreshold.toInteger() : 50
    int maxDays = settings.maxDaysLimit != null ? settings.maxDaysLimit.toInteger() : 14

    long now = new Date().getTime()
    if (state.history == null) state.history = hasReading ? [[time: now, rawMoisture: rawMoist, statusText: "Success", isSpeedOutlier: false, isFcOutlier: false]] : []

    // Find the last valid point for watering detection (so speed glitches don't reset memory)
    def validHistory = state.history.findAll { !(it.isSpeedOutlier ?: false) }
    def lastValidPoint = validHistory ? validHistory[-1] : (state.history ? state.history[-1] : null)

    if (lastValidPoint != null && rawMoist > (lastValidPoint.rawMoisture + 5)) {
        log.info "${device.displayName} watered. Calculating previous ET rate and resetting memory."

        // FILTER: Check valid raw values against the calibrated capacity 
        def etPoints = state.history.findAll { !(it.isSpeedOutlier ?: false) && !(it.isFcOutlier ?: false) }

        // GUARDRAIL 1: Need at least 5 points (5 hours) to save a valid historical rate
        if (etPoints.size() >= 5) {
            def oldest = etPoints[0]
            def newest = etPoints[-1]
            long elapsedMs = newest.time - oldest.time
            double elapsedDays = elapsedMs / 86400000.0
            double dropped = oldest.rawMoisture - newest.rawMoisture 

            if (elapsedDays > 0 && dropped > 0) {
                double newRate = dropped / elapsedDays
                
                // GUARDRAIL 3: Blended Memory (Average new rate with historical average)
                if (state.savedEtRate != null) {
                    state.savedEtRate = (state.savedEtRate + newRate) / 2.0
                    log.info "${device.displayName} Blended new rate (${String.format('%.1f', newRate)}%/day) with history. New average: ${String.format('%.1f', state.savedEtRate)}%/day"
                } else {
                    state.savedEtRate = newRate
                }
            }
        } else {
            log.info "${device.displayName} Not enough valid data points (< 5) to update ET Rate history. Keeping previous average."
        }

        // Wait to record the watering spike on the next normal interval instead of forcing an immediate zero-point
        state.history = []
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

    // Get live ET points, actively removing speed outliers and gravitational drainage
    def liveEtPoints = state.history.findAll { !(it.isSpeedOutlier ?: false) && !(it.isFcOutlier ?: false) }
    int pointCount = liveEtPoints.size()

    // GUARDRAIL 1 (Live): Calculate live ET. Fall back to history ONLY if building buffer.
    if (pointCount >= 5) {
        def oldest = liveEtPoints[0]
        def newest = liveEtPoints[-1]
        long elapsedMs = newest.time - oldest.time
        double elapsedDays = elapsedMs / 86400000.0
        double dropped = oldest.rawMoisture - newest.rawMoisture

        if (elapsedDays > 0 && dropped > 0) {
            dropPerDay = dropped / elapsedDays
        }
    } else if (state.savedEtRate != null) {
        dropPerDay = state.savedEtRate as double
    }

    // Print the ET Math log ONLY during the hourly cron trigger
    if (isHourlyCron) {
        if (pointCount >= 5) {
            log.info "${device.displayName} ET Math -> (Using live calculated ET Rate: ${String.format('%.1f', dropPerDay)}%/day)"
        } else if (state.savedEtRate != null) {
            log.info "${device.displayName} ET Math -> (${pointCount}/5 consecutive scans in history, using saved ET Rate: ${String.format('%.1f', dropPerDay)}%/day)"
        } else {
            log.info "${device.displayName} ET Math -> (${pointCount}/5 consecutive scans in history, gathering data)"
        }
    }

    // Calculate estimated days remaining before hitting the Watering Threshold (Gas Light)
    if (!hasReading) {
        daysLeft = "--"
    } else if (dropPerDay > 0 && moist > waterTarget) {
        double days = (moist - waterTarget) / dropPerDay
        if (days > maxDays) {
            daysLeft = "> ${maxDays}"
        } else {
            daysLeft = String.format("%.1f", days)
        }
    } else if (moist <= waterTarget) {
        daysLeft = "0.0"
    } else if (dropPerDay == 0.0 && moist > waterTarget && pointCount >= 5) {
        // Embrace the flatline: Plant is stable, no measurable drop
        daysLeft = "> ${maxDays}"
    }

    sendEvent(name: "etRate", value: new BigDecimal(dropPerDay).setScale(1, BigDecimal.ROUND_HALF_UP), unit: "%/day")
    sendEvent(name: "daysRemaining", value: daysLeft, unit: "days")

    // UI Variables required by the HTML string...
    // Styling lives in dashboard CSS to keep plantCard under the 1024-char tile limit
    String cleanName = device.displayName.replace(" Soil Sensor", "").replace(" Moisture Sensor", "")
    String batt = battVal.toString()
    String battClass = battVal <= 20 ? "batt-low" : ""
    int battWidth = battVal
    boolean isDry = hasReading && moist <= waterTarget
    if (hasReading) sendEvent(name: "contact", value: isDry ? "open" : "closed")
    String stateClass = ""
    String centerBig = daysLeft
    String centerSmall = "DAYS LEFT"

    if (isDry) {
        // Replaces the word "DRY" with the terracotta SVG water drop
        stateClass = "is-dry"
        centerBig = """<svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor"><path d="M12 20a6 6 0 0 1-6-6c0-4 6-10.75 6-10.75S18 10 18 14a6 6 0 0 1-6 6z"/></svg>"""
        centerSmall = "WATER NOW"
    } else if (daysLeft == "--" || daysLeft == "0.0") {
        stateClass = "is-calc"
        centerBig = "CALC"
        centerSmall = "GATHERING DATA"
    }

    String cardHtml = """<div class="pw-wrap ${stateClass}"><div class="pw-head"><a href="/device/edit/${device.id}" target="_blank" class="pw-title" title="Click to edit threshold">${cleanName}</a><div class="pw-batt ${battClass}"><div class="pw-b-icon"><div class="pw-b-fill" style="width:${battWidth}%;"></div></div><div class="pw-b-txt">${batt}%</div></div></div><div class="pw-hero"><div class="pw-ring" style="--val:${displayPct};"><div class="pw-ring-in"><div class="pw-val">${centerBig}</div><div class="pw-pct">${centerSmall}</div></div></div></div><div class="pw-foot"><div class="pw-metrics"><span class="pw-temp">💧 ${moist}%</span><span class="pw-temp">🌡 ${temp}°F</span></div></div></div>"""
    sendEvent(name: "plantCard", value: cardHtml)

    // Generate a readable HTML table for the device page (Now 3 columns)
    String tableHtml = "<table style='width:100%; border-collapse:collapse; text-align:left; font-size:13px; line-height: 1.2;'><tr style='border-bottom:2px solid #ccc;'><th style='padding:4px;'>Time</th><th style='padding:4px;'>Raw Moisture</th><th style='padding:4px;'>Status</th></tr>"
    // Reverse the history so the newest entries are at the top
    state.history.reverse().each { entry ->
        String readableDate = new Date(entry.time).format("MM/dd h:mm a")
        boolean isBad = (entry.isSpeedOutlier ?: false) || (entry.isFcOutlier ?: false)
        String rowColor = isBad ? "color: #d9534f;" : ""
        String status = entry.statusText ?: "Success"
        
        tableHtml += "<tr style='border-bottom:1px solid #eee; ${rowColor}'><td style='padding:4px;'>${readableDate}</td><td style='padding:4px;'>${entry.rawMoisture}</td><td style='padding:4px;'>${status}</td></tr>"
    }
    tableHtml += "</table>"
    sendEvent(name: "historyTable", value: tableHtml)
}

def updated() {
    log.info "Preferences updated..."
    int fc = settings.fieldCapacity != null ? settings.fieldCapacity.toInteger() : 75
    int wt = settings.wateringThreshold != null ? settings.wateringThreshold.toInteger() : 50
    int pwp = settings.permanentWiltingPoint != null ? settings.permanentWiltingPoint.toInteger() : 30
    if (pwp >= fc) {
        log.warn "${device.displayName}: Permanent Wilting Point (${pwp}) must be below Field Capacity (${fc}). The ring gauge will only show full or empty until this is fixed."
    } else if (wt <= pwp || wt >= fc) {
        log.warn "${device.displayName}: Watering Threshold (${wt}) should be between Permanent Wilting Point (${pwp}) and Field Capacity (${fc})."
    }
    runEvery1Hour(updateHistory)
    updatePlantCard() 
}

def resetSensor(String resetCalibration = "No") {
    log.info "${device.displayName} reset: clearing history and learned ET rate"
    state.remove("history")
    state.remove("savedEtRate")

    if (resetCalibration == "Yes") {
        log.info "${device.displayName} reset: restoring calibration settings to defaults"
        device.updateSetting("fieldCapacity", [value: 75, type: "number"])
        device.updateSetting("wateringThreshold", [value: 50, type: "number"])
        device.updateSetting("permanentWiltingPoint", [value: 30, type: "number"])
        device.updateSetting("moistOffset", [value: 0, type: "number"])
        device.updateSetting("tempOffset", [value: 0.0, type: "decimal"])
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
    return zigbee.configureReporting(0x0405, 0x0000, 0x21, 10, 300, 100) + 
           zigbee.configureReporting(0x0402, 0x0000, 0x29, 10, 300, 50) +  
           zigbee.batteryConfig() +
           zigbee.configureReporting(0x0001, 0x0021, 0x20, 30, 21600, 2) +
           refresh()
}
