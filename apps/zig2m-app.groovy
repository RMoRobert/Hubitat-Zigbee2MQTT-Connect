/**
 * ====================  Zigbee2MQTT Connect - Zigbee2MQTT Integration ==================
 *
 *  Copyright 2021 Robert Morris
 *
 *  DESCRIPTION:
 *  Community-developed integration for importing Zigbee2MQTT devices into Hubitat via an
 *  MQTT broker.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum or README.MD file in GitHub repo
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * =======================================================================================
 *
 *  Last modified: 2021-12-12
 * 
 *  Changelog:
 *  v0.4    - (Beta) More driver matches, app to/from broker setting updates, etc.
 *  v0.1    - (Beta) Initial Public Release
 */ 

import com.hubitat.app.DeviceWrapper
import hubitat.helper.ColorUtils
import groovy.transform.Field

@Field static final Integer debugAutoDisableSeconds = 1800
@Field static final String customDriverNamespace = "RMoRobert"
@Field static final String stockDriverNamespace = "hubitat"

definition (
   name: "Zigbee2MQTT Connect",
   namespace: "RMoRobert",
   author: "Robert Morris",
   description: "Integrate devices from Zigbee2MQTT (connects to MQTT broker)",
   category: "Convenience",
   installOnOpen: true,
   documentationLink: "https://community.hubitat.com/t/COMING-SOON",
   iconUrl: "",
   iconX2Url: "",
   iconX3Url: ""
)

preferences {
   page name: "pageFirstPage"
   page name: "pageIncomplete"
   page name: "pageConnect"
   page name: "pageTestConnection"
   page name: "pageManage"
   page name: "pageSelectDevices"
}

void installed() {
   log.debug "installed()"
   initialize()
}

void uninstalled() {
   log.debug "uninstalled()"
   if (!(settings['deleteDevicesOnUninstall'] == false)) {
      logDebug("Deleting child devices...")
      List DNIs = getChildDevices().collect { DeviceWrapper it -> it.deviceNetworkId }
      logDebug("  Preparing to delete devices with DNIs: $DNIs")
      DNIs.each {
         deleteChildDevice(it)
      }
   }
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   log.debug "initialize()"
   unschedule()
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableSeconds/60} minutes"
      runIn(debugAutoDisableSeconds, "debugOff")
   }
}

void debugOff() {
   log.warn "Disabling debug logging after timeout"
   app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

Map pageFirstPage() {
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   if (brokerDev == null) {
      if (enableDebug) log.debug "Preparing to create broker device..."
      if (settings.ipAddress && settings.port) {
         if (enableDebug) log.debug "All broker information present"
         if (enableDebug) log.debug "Creating child device for broker..."
         Map devProps = [name: """Zigbee2MQTT Broker${settings.nickname ? " - ${settings.nickname} " : ""}"""]
         brokerDev = addChildDevice(customDriverNamespace, "Zigbee2MQTT Broker", "Zig2M/${app.id}", devProps)
         if (brokerDev != null) {
            updateBrokerDeviceSettings()
         }
         else {
            log.error "Broker device not found and could not be created"
         }
      }
      else {
         if (enableDebug) log.debug "Not creating broker device because some information missing. Re-run setup."
      }
   }
   else {
      if (state.wasOnConnectPage == true) {
         updateBrokerDeviceSettings()
         state.remove("wasOnConnectPage")
      }
   }
   if (app.getInstallationState() == "INCOMPLETE") {
      // Shouldn't happen with installOnOpen: true, but just in case...
      dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
         section() {
            paragraph("Please press \"Done\" to install, then re-open to configure this app.")
         }
      }
   }
   else {
      if (settings.ipAddress && settings.port && brokerDev != null) {
         return pageManage()
      }
      else {
         return pageConnect()
      }
   }
}

Map pageConnect() {
   logDebug("pageConnect()...")
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   state.wasOnConnectPage = true
   dynamicPage(name: "pageConnect", uninstall: true, install: false, nextPage: "pageFirstPage") {
      section(styleSection("Connect to MQTT Broker")) {
         if (brokerDev != null) {
            paragraph "NOTE: Broker device already added to Hubitat. Editing the below may fail; try editing the settings on the broker device device directly if any of the below fails."
         }
         input name: "nickname", type: "text", title: "\"Nickname\" for this Zigbee2MQTT instance (optional; will be used as part of app and broker device names):"
         input name: "ipAddress", type: "string", title: "IP address", description: "Example: 192.168.0.10 (hostname may also work)", submitOnChange: true,
            required: true
         input name: "port", type: "number", title: "Port", description: "Default: 1883", defaultValue: 1883, submitOnChange: true,
            required: true
         input name: "topic", type: "string", title: "MQTT topic", defaultValue: "zigbee2mqtt", required: true, submitOnChange: true
         input name: "clientId", type: "string", title: "MQTT client ID (recommended to use default; must be unique on broker)", submitOnChange: true,
            defaultValue: getDefaultClientId(), required: true
         //input name: "useTLS", type: "bool", title: "Use TLS/SSL", submitOnChange: true
         input name: "username", type: "string", title: "MQTT username (optional)", submitOnChange: true
         input name: "password", type: "password", title: "MQTT password (optional)", submitOnChange: true
         // TODO: Allow input of certificates for TLS/SSL connections
      }
      section(styleSection("Logging")) {
         input name: "enableDebug", type: "bool", title: "Enable debug logging (for app)", submitOnChange: true
      }
   }
}

// Returns default client ID based on hub location  name
String getDefaultClientId() {
   String id = location.name.replaceAll("[^a-zA-Z0-9]+","").toLowerCase() ?: "hubitat"
   if (id.size() > 16) id = id.substring(0,16)
   id += "_z2m_${app.id}"
   return id
}

/**
 * Adds new devices if any were selected on selection page (called when navigating back to main "manage" page)
 */
void createNewSelectedDevices() {
   // Add new devices if any were selected
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   if (brokerDev != null) {
      List zigDevs = brokerDev.getDeviceList()
      settings.z2mDevSelections?.each { String ieee ->
         if (enableDebug) log.debug "Creating new device for IEEE ${ieee})"
         String devDNI = "Zig2M/${app.id}/${ieee}"
         Map z2mDev = zigDevs.find { it.ieee_address == ieee }
         DeviceWrapper cd = getChildDevice(devDNI)
         if (cd != null) {
            if (enableDebug) log.debug "not creating device for $ieee; already exists"
         }
         else {
            if (z2mDev != null) {
               if (enableDebug) log.debug "Creating device for IEEE = $ieee, name = ${z2mDev.friendly_name}"
               def (String driverName, String namespace) = getBestMatchDriver(z2mDev.definition.exposes)
               try {
                  DeviceWrapper d = addChildDevice(namespace, driverName, devDNI, [name: z2mDev.friendly_name])
                  if (d != null) {
                     if (z2mDev.definition?.vendor) d.updateDataValue("vendor", z2mDev.definition.vendor)
                     if (z2mDev.definition?.model) d.updateDataValue("model", z2mDev.definition.model)
                  }
                  if (driverName == "Zigbee2MQTT Component RGBW Effects Bulb") {
                     Map effectValue = z2mDev.definition?.exposes?.find { it.name == "effect" }
                     //log.error "does expose? ${effectValue}"
                     if (effectValue?.values != null) {
                        d.setLightEffects(effectValue.values)
                     }
                  }
               }
               catch (Exception ex) {
                  log.error "Unable to create device for  IEEE = $ieee, name = ${z2mDev.friendly_name}: $ex"
               }
            }
            else {
               log.warn "Unable to find device on Zigbee2MQTT for IEEE $ieee"
            }
         }
      }
   }
   else {
      log.warn "Zigbee2MQTT broker device not found!"
   }
   app.removeSetting "z2mDevSelections"
}

// Returns ["driverName", "driverNamespace"] with best-match driver based on "exposes" from Z2M device defintiion
List<String> getBestMatchDriver(List<Map> exposes) {
   if (enableDebug) log.debug "getBestMatchDriver(${exposes})"
   if (!exposes) return []
   String namespace = stockDriverNamespace
   String driverName
   if (exposes.find { it.name == "occupancy"}) {
      if (exposes.find { it.name == "temperature"} && exposes.find { it.name == "humidity"} ) {
         driverName = "Generic Component Motion/Temperature/Humidity Sensor"
         namespace = customDriverNamespace
      }
      else if (exposes.find { it.name == "temperature"} && exposes.find { it.name == "illuminance_lux"} ) {
         driverName = "Generic Component Motion/Temperature/Lux Sensor"
         namespace = customDriverNamespace
      }
      else if (exposes.find { it.name == "temperature"} ) {
         driverName = "Generic Component Motion/Temperature Sensor"
         namespace = customDriverNamespace
      }
      else if (exposes.find { it.name == "battery"} ) {
         driverName = "Generic Component Motion (with Battery) Sensor"
         namespace = customDriverNamespace
      }
      else {
         driverName = "Generic Component Motion Sensor"
      }
   }
   else if (exposes.find {it.name == "contact" && it.name == "x_axis"}) {
      driverName = "Generic Component Acceleration/Axis/Contact Sensor"
      namespace = customDriverNamespace
   }
   else if (exposes.find {it.name == "contact" }) {
      driverName = "Generic Component Contact Sensor"
   }
   else if (exposes.find {it.name == "temperature" }) { // should break out temp-only sensor, but most seem to do both so not a huge priority...
      driverName = "Generic Component Temperature/Humidity Sensor"
      namespace = customDriverNamespace
   }
   else if (exposes.features.find { flist -> flist.find { f -> f.name == "color_xy"  || f.name == "color_hs "} &&
                                           flist.find { f-> f.name == "color_temp" } } &&
            exposes.find { it.name == "effect"}) {
      driverName = "Zigbee2MQTT Component RGBW Effects Bulb"
      namespace = customDriverNamespace
   }   
   else if (exposes.features.find { flist -> flist.find { f -> f.name == "color_xy"  || f.name == "color_hs "} &&
                                           flist.find { f-> f.name == "color_temp" } }) {
      driverName = "Generic Component RGBW"
   }
   else if (exposes.features.find { flist -> flist.find { f -> f.name == "color_xy"  || f.name == "color_hs " } } ) {
      driverName = "Generic Component RGB"
   }
   else if (exposes.features.find { flist -> flist.find { f -> f.name == "color_temp"  } } ) {
      driverName = "Generic Component CT"
   }
   else if (exposes.find { it.name == "action" }) {
      driverName = "Zigbee2MQTT Component Button"
      namespace = customDriverNamespace
   }
   else if (exposes.features.find { flist -> flist.find { f -> f.name == "state"  } } ) {
      driverName = "Generic Component Switch"
   }
   else {
      driverName = "Zigbee2MQTT Generic Device"
      namespace = customDriverNamespace
   }
   return [driverName, namespace]
}

Map pageManage() {
   createNewSelectedDevices()
   List<String> cdNames = getChildDevices().findAll { DeviceWrapper cd ->
      cd.deviceNetworkId != "Zig2M/${app.id}" }.collect {
         DeviceWrapper cd -> cd.displayName
   }
   cdNames.sort()
   dynamicPage(name: "pageManage", uninstall: true, install: true) {
      section("Choose Zigbee2MQTT devices to import") {
         href name: "hrefSelectDevices", title: "Select Zigbee2MQTT devices...",
            description: cdNames.join("<br>"), state: ((cdNames?.size() > 0) ? "complete" : null),
            page: "pageSelectDevices"
      }
      section("Other Options") {
         href name: "hrefReConnect", title: "Edit broker IP, port, or authentication",
               description: "", page: "pageConnect"
         input name: "deleteDevicesOnUninstall", type: "bool", title: "Delete devices created by app if uninstalled (if unselected, will attempt to keep; platform may still delete)", defaultValue: true
         input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      }
   }
}

Map pageSelectDevices(params) {
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   List allZig2MDevs
   List<DeviceWrapper> abandonedDevs = []
   Map unclaimedDevs = [:] // format (of Map) = [ieee: [friendly_name: 'the friendly name', exposes: [...]]]
   List<DeviceWrapper> claimedDevs = [] // format = List of DeviceWrapper objects
   if (brokerDev != null) {
      allZig2MDevs = brokerDev.getDeviceList()
      allZig2MDevs.each {
         if (it.type?.toLowerCase() != "coordinator") {
            DeviceWrapper cd = getChildDevice("Zig2M/${app.id}/${it.ieee_address}")
            if (cd != null) {
               claimedDevs << cd
            }
            else {
               unclaimedDevs[it.ieee_address] = [friendly_name: it.friendly_name, exposes: it.definition?.exposes]
            }
         }
      }
      abandonedDevs = getChildDevices()
      abandonedDevs.removeAll { it.deviceNetworkId in claimedDevs.collect { it.deviceNetworkId } }
      abandonedDevs.removeAll { it.deviceNetworkId == "Zig2M/${app.id}" }
   }
   else {
      log.warn "Zigbee2MQTT broker device not found!"
   }
   dynamicPage(name: "pageSelectDevices", uninstall: true, install: false, nextPage: "pageManage") {
      section("Import Zigbee2MQTT Devices") {
         input name: "z2mDevSelections", type: "enum", title: "Select new Zigbee2MQTT devices to import",
            options: unclaimedDevs.collect { [(it.key): it.value.friendly_name] }.sort { it[it.keySet()[0]].value },
            multiple: true
      }
      section("Existing Zigbee2MQTT Devices") {
         if (claimedDevs) {
            StringBuilder sb = new StringBuilder()
            claimedDevs.sort { DeviceWrapper dev -> dev.displayName }
            paragraph "Added devices <span style=\"font-style: italic\">(Zigbee2MQTT \"friendly name\" in parentheses)</span>:"
            sb << "<ul>"
            claimedDevs.each { DeviceWrapper cd ->
               sb << "<li><a href=\"/device/edit/${cd.id}\" target=\"_blank\">${cd.displayName}</a>"
               sb << " <span style=\"font-style: italic\">(${allZig2MDevs.find { it.ieee_address == cd.deviceNetworkId.tokenize('/')[-1] }?.friendly_name ?: '(unable to retrieve Zigbee2MQTT name)'})</span></li>"
            }
            sb << "</ul>"
            paragraph sb.toString()
            if (abandonedDevs) {
               StringBuilder sb2 = new StringBuilder()
               abandonedDevs.sort { DeviceWrapper dev -> dev.displayName }
               paragraph "Hubitat devices no longer found in Zigbee2MQTT:"
               sb2 << "<ul>"
               abandonedDevs.each { DeviceWrapper cd ->
                  sb2 << "<li><a href=\"/device/edit/${cd.id}\" target=\"_blank\">${cd.displayName}</a></li>"
               }
               sb2 << "</ul>"
               paragraph sb2.toString()
            }
         }
         else {
            paragraph "No added devices"
         }
      }
   }
}

String styleSection(String sectionTitle) {
   return """<span style="font-weight: bold; font-size: 110%">$sectionTitle</span>"""
}

void updateSettings(List<Map<String,Map>> newSettings) {
   if (enableDebug) log.debug "updateSettings($newSettings)"
   newSettings.each { Map newSetting ->
      newSetting.each { String settingName, Map settingValue ->
         app.updateSetting(settingName, settingValue)
      }
   }
}

void updateBrokerDeviceSettings() {
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   List<Map<String,Map>> newSettings = []
   newSettings << ["ipAddress": [value: settings.ipAddress, type: "string"]]
   newSettings << ["port": [value: settings.port, type: "number"]]
   newSettings << ["topic": [value: settings.topic, type: "string"]]
   newSettings << ["clientId": [value: settings.clientId, type: "string"]]
   newSettings << ["useTLS": [value: settings.useTLS, type: "bool"]]
   newSettings << ["username": [value: settings.username, type: "string"]]
   newSettings << ["password": [value: settings.password, type: "password"]]
   brokerDev.updateSettings(newSettings)
   pauseExecution(2500)
   brokerDev.initialize()
}

Long getTheAppId() {
   return app.id
}

void appButtonHandler(String btn) {
   switch(btn) {
      case "btnSaveHub":
         updateBrokerDeviceSettings()
         break
      case "btnDeviceRefresh":
         // nothing, just refrehs page
         break
      default:
         log.warn "Unhandled app button press: $btn"
   }
}

private void logDebug(String str) {
   if (settings.enableDebug != false) log.debug(str)
}

////////////////////////////////////
// Component Methods
////////////////////////////////////

void componentRefresh(DeviceWrapper device) {
   if (enableDebug) log.debug "componentRefresh(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   List<Map<String,String>> payloads = []
   if (device.hasAttribute("switch")) payloads << [state: ""]
   if (device.hasAttribute("level")) payloads << [brightness: ""]
   if (device.hasAttribute("hue")) payloads << [color: [x: "", y: ""]]
   if (device.hasAttribute("colorTemperature")) payloads << [color_temp: ""]
   if (device.hasAttribute("lock")) payloads << [state: ""]
   // probably can flesh this out more for other devices later...
   payloads.each { Map<String,String> payload ->
      brokerDev.publishForIEEE(ieee, "get", payload)
      pauseExecution(50)
   }
}

void componentOn(DeviceWrapper device) {
   if (enableDebug) log.debug "componentOn(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentOff(DeviceWrapper device) {
   if (enableDebug) log.debug "componentOn(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [state: "OFF"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetLevel(DeviceWrapper device, Number level, Number transitionTime=null) {
   if (enableDebug) log.debug "componentSetLevel(${device.displayName}, $level, $transitionTime)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Number> payload = [brightness: Math.round((level as Float) * 2.55)]
   if (transitionTime != null) payload << [transition: transitionTime]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

/* Haven't found Z2M bulb that supports yet...
void componentPresetLevel(DeviceWrapper device, Number level) {
   if (enableDebug) log.debug "componentPresetLevel(${device.displayName}, $level)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Number> payload = [brightness: Math.round((level as Float) * 2.55)]
   brokerDev.publishForIEEE(ieee, "set", payload)
}
*/

void componentStartLevelChange(DeviceWrapper device, String direction) {
   if (enableDebug) log.debug "componentStartLevelChange(${device.displayName}, $direction)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Integer mvRate = (direction.toLowerCase() == "up" ? 85 : -85)
   Map<String,Number> payload = [brightness_move: mvRate]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentStopLevelChange(DeviceWrapper device) {
   if (enableDebug) log.debug "componentStopLevelChange(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Number> payload = [brightness_move: 0]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetColorTemperature(DeviceWrapper device, Number colorTemperature, Number level=null, Number transitionTime=null) {
   if (enableDebug) log.debug "componentSetColorTemperature(${device.displayName}, $colorTemperature, $level, $transitionTime)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Number> payload = [color_temp: Math.round(1000000.0/colorTemperature)]
   if (level != null) payload << [brightness: Math.round((level as Float) * 2.55)]
   if (transitionTime != null) payload << [transition: transitionTime]
   if (device.currentValue("switch") != "on") payload << [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

// Uses RGB (most widely accepted?)
void componentSetColor(DeviceWrapper device, Map<String,Number> colorMap) {
   if (enableDebug) log.debug "componentSetColor(${device.displayName}, $colorMap)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   List rgb = hubitat.helper.ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level ?: device.currentValue('level')])
   Map<String,Map<String,String>> payload = [color: [rgb: rgb.join(',')]]
   if (colorMap.rate != null) payload << [transition: colorMap.rate]
   if (device.currentValue("switch") != "on") payload << [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

// Uses HS (doesn't work for all?)
void componentSetColorHS(DeviceWrapper device, Map<String,Number> colorMap) {
   if (enableDebug) log.debug "componentSetColor(${device.displayName}, $colorMap)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Map<String,String>> payload = [color: [h: Math.round((colorMap.hue as float) / 3.60), s: colorMap.saturation, v: colorMap.level ?: device.currentValue('level')]]
   if (colorMap.rate != null) payload << [transition: colorMap.rate]
   if (device.currentValue("switch") != "on") payload << [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetHue(DeviceWrapper device, Number hue) {
   if (enableDebug) log.debug "componentSetHue(${device.displayName}, $hue)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   List rgb = hubitat.helper.ColorUtils.hsvToRGB([hue, device.currentValue('saturation'), device.currentValue('level')])
   Map<String,Map<String,String>> payload = [color: [rgb: rgb.join(',')]]
   if (device.currentValue("switch") != "on") payload << [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetSaturation(DeviceWrapper device, Number sat) {
   if (enableDebug) log.debug "componentSetSaturation(${device.displayName}, $sat)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   List rgb = hubitat.helper.ColorUtils.hsvToRGB([device.currentValue('hue'), sat, device.currentValue('level')])
   Map<String,Map<String,String>> payload = [color: [rgb: rgb.join(',')]]
   if (device.currentValue("switch") != "on") payload << [state: "ON"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

// Hubitat uses number, but String is easier to work with in Z2M, so custom
// bulb driver implements both. Use the String variant only when calling parent for now!
void componentSetEffect(DeviceWrapper device, String effectName) {
   if (enableDebug) log.debug "componentSetEffect(${device.displayName}, String $effectName)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,Number> payload = [effect: effectName]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetEffect(DeviceWrapper device, Number effectNumber) {
   if (enableDebug) log.debug "componentSetEffect(${device.displayName}, Number $effectNumber)"
   log.warn "Not yet implemented; use String effecet name instead of number for now."
}
 
void componentPublish(DeviceWrapper device, String topic=null, String payload=null) {
   if (enableDebug) log.debug "componentPublish(${device.displayName}, $topic, $payload)"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   brokerDev.publishForIEEE(ieee, topic, payload)
} 

void componentLock(DeviceWrapper device) {
   if (enableDebug) log.debug "componentLock(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [state: "LOCK"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentUnlock(DeviceWrapper device) {
   if (enableDebug) log.debug "componentUnlock(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [state: "UNLOCK"]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentDeleteCode(DeviceWrapper device, Integer codePosition) {
   if (enableDebug) log.debug "componentDeleteCode(${device.displayName}, ${codePosition})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [pin_code: [user: codePosition, user_enabled: false, pin_code: null]]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentGetCodes(DeviceWrapper device) {
   if (enableDebug) log.debug "componentDeleteCode(${device.displayName}, ${codePosition})"
   log.warn "getCodes() not implemented"
}

void componentSetCode(DeviceWrapper device, Integer codePosition, String pincode, String name=null) {
   if (enableDebug) log.debug "componentSetCode(${device.displayName}, ${codePosition})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   Map<String,String> payload = [pin_code: [user: codePosition, user_enabled: true, pin_code: Integer.parseInt(pincode)]]
   brokerDev.publishForIEEE(ieee, "set", payload)
}

void componentSetCodeLength(DeviceWrapper device, Integer codeLength) {
   if (enableDebug) log.debug "componentSetCodeLength(${device.displayName}, ${codeLength})"
   List<Map> evts = [[name: "codeLength", value: codeLength]]
   device.parse(evts)
}

String getDefinitionForDevice(DeviceWrapper device) {
   if (enableDebug) log.debug "getDefinitionForDevice(${device.displayName})"
   DeviceWrapper brokerDev = getChildDevice("Zig2M/${app.id}")
   String ieee = device.getDeviceNetworkId().tokenize('/')[-1]
   if (brokerDev != null) {
      List zigDevs = brokerDev.getDeviceList()
      Map z2mDev = zigDevs.find { it.ieee_address == ieee }
      if (z2mDev != null) {
         // Can use if want Groovy toString output instead:
         //log.debug "DEFINITION: ${z2mDev.definition}"
         log.debug "DEFINITION: " + groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(z2mDev.definition))
      }
      else {
         log.debug "No device found on Zigbee2MQTT broker for ${device.displayName}"
      }
   }
   else {
      log.warn "Zigbee2MQTT Broker device not found!"
   }
   return 
}