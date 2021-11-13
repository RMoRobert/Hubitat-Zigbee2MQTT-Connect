/**
 * ============================  Zigbee2MQTT Broker (Driver) =============================
 *
 *  Copyright 2021 Robert Morris
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
 *  Last modified: 2021-11-12
 *
 *  Changelog:
 *  v0.2    - (Beta) Improved reconnection; button driver and parsing added; more driver/device matches
 *  v0.1    - (Beta) Initial Public Release
 */ 

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import com.hubitat.app.DeviceWrapper

@Field static final Integer debugAutoDisableSeconds = 1800
@Field static final ConcurrentHashMap<Long,List> devices = [:]

metadata {
   definition(
      name: "Zigbee2MQTT Broker",
      namespace: "RMoRobert",
      author: "Robert Morris",
      importUrl: "https://raw.githubusercontent.com/RMoRobert/Zigbee2MQTTDConnect/main/drivers/zig2m-broker.groovy"
   ) {
      capability "Actuator"
      capability "Initialize"

      command "connect"
      command "disconnect"

      command "logDevices" // temporary for development; output devices field to logs

      command "subscribeToTopic", [[name:"topic", type: "STRING", description: "MQTT topic"]]
      command "publish", [[name: "topic*", type: "STRING", description: "MQTT topic (will prepend with base topic)"],
                         [name: "payload", type: "STRING", description: "MQTT payload"]]

      attribute "status", "STRING"
   }
   
   preferences() {
      input name: "ipAddress", type: "string", title: "IP address", description: "Example: 192.168.0.10"
            required: true
      input name: "port", type: "number", title: "Port", description: "Default: 1883", defaultValue: 1883,
         required: true
      input name: "topic", type: "string", title: "MQTT topic", defaultValue: "zigbee2mqtt", required: true
      input name: "clientId", type: "string", title: "MQTT client ID", required: true
      //input name: "useTLS", type: "bool", title: "Use TLS/SSL", submitOnChange: true
      input name: "username", type: "string", title: "MQTT username (optional)", submitOnChange: true
      input name: "password", type: "password", title: "MQTT password (optional)", submitOnChange: true
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
   state.parentId = parent.getTheAppId()
   // TODO: See if this works or is needed:
   List<Map<String,Map>> newSettings = []
   newSettings << ["ipAddress": [value: settings.ipAddress, type: "string"]]
   newSettings << ["port": [value: settings.ipAddress, type: "number"]]
   newSettings << ["topic": [value: settings.ipAddress, type: "string"]]
   newSettings << ["clientId": [value: settings.ipAddress, type: "string"]]
   newSettings << ["useTLS": [value: settings.ipAddress, type: "bool"]]
   newSettings << ["username": [value: settings.ipAddress, type: "string"]]
   newSettings << ["password": [value: settings.ipAddress, type: "password"]]
   parent.updateSettings(newSettings)
}

void initialize(Boolean forceReconnect=true) {
   log.debug "initialize()"
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableSeconds/60} minutes"
      runIn(debugAutoDisableSeconds, "debugOff")
   }
   if (forceReconnect == true) {
      doSendEvent("status", "disconnected")
      pauseExecution(500)
      reconnect(false)
   }
   else {
      reconnect()
   }
}


void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

// Pieces together connection informatino into URI
String getConnectionUri() {
   //String uri = (settings.useTLS ? "ssl://" : "tcp://") + settings.ipAddress + ":" + settings.port
   String uri = "tcp://" + settings.ipAddress + ":" + settings.port
   return uri
}

void connect() {
   if (enableDebug) {
      log.debug "connect()"
      log.debug "URI = ${getConnectionUri()}; clientId = $settings.clientId; username = $username ($password)"
   }
   if (interfaces.mqtt.isConnected()) {
      if (enableDebug) log.debug "Is connected; disconnecting first"
      interfaces.mqtt.disconnect()
      runIn(2, "subscribeToTopic")
   }
   unschedule("reconnect")
   if (enableDebug) log.debug "connecting now..."
   interfaces.mqtt.connect(getConnectionUri(), settings.clientId, settings.username, settings.password)
   pauseExecution(1000)
}

void updateSettings(List<Map<String,Map>> newSettings) {
   newSettings.each { Map newSetting ->
      newSetting.each { String settingName, Map settingValue ->
         device.updateSetting(settingName, settingValue)
      }
   }
}

void disconnect() {
   if (enableDebug) log.debug "disconnect()"
   interfaces.mqtt.disconnect()
   unschedule("reconnect")
   doSendEvent("status", "disconnected")
}

void reconnect(Boolean notIfAlreadyConnected = true) {
   if (enableDebug) log.debug "reconnect(notIfAlreadyConnected=$notIfAlreadyConnected)"
   if (interfaces.mqtt.isConnected() && notIfAlreadyConnected) {
      if (enableDebug) log.debug "already connected; skipping reconnection"
   }
   else {
      connect()
   }
}

void parse(String message) {
   if (enableDebug) log.debug "parse(): ${interfaces.mqtt.parseMessage(message)}"
   // Use if need to see raw data instead:
   //if (enableDebug) log.debug "parse(): raw message = $message"
   Map<String,String> parsedMsg = interfaces.mqtt.parseMessage(message)
   switch (parsedMsg?.topic) {
      // Should happen when subscribing or when device added, friendly name re-named, etc.:
      case "${settings.topic}/bridge/devices":
         devices[device.idAsLong] = parseJson(parsedMsg.payload)
         //log.trace "devices = ${devices[device.idAsLong]}"
         break
      // General Bridge info, some of which we may care about but are ignoring for now...but helps filter remaining to just devices
      case { it.startsWith("${settings.topic}/bridge/") }:
         if (enableDebug) log.debug "ignoring bridge topic ${parsedMsg.topic}, payload ${parsedMsg.payload}"
         break
      // Legacy button, ignore? (use new)
      case { it.startsWith("${settings.topic}/") && (it.tokenize('/')[-1] == "click") && it.count('/') > 1 }:
         if (enableDebug) log.debug "ignoring /click (legacy button; use {'action': ...} instead)"
         break
      // Not sure if this ever gets *recevied* or just sent, but just in case...
      case { it.startsWith("${settings.topic}/") && (it.tokenize('/')[-1] == "get") }:
         if (enableDebug) log.debug "ignoring /get ${parsedMsg.topic}, payload ${parsedMsg.payload}"
         break
      // Not sure if this (also) ever gets *recevied* or just sent, but just in case...
      case { it.startsWith("${settings.topic}/") && (it.tokenize('/')[-1] == "set") }:
         if (enableDebug) log.debug "ignoring /set ${parsedMsg.topic}, payload ${parsedMsg.payload}"
         break
      // Not using now, possibly could some day..
      case { it.startsWith("${settings.topic}/") && (it.tokenize('/')[-1] == "availability") }:
         if (enableDebug) log.debug "ignoring /availability ${parsedMsg.topic}, payload ${parsedMsg.payload}"
         break
      // Anything left *should* be a friendly name (device) at this point, so attempt parsing...
      // Note: device names can have slashes, so commented-out 'case' won't work!
      case { it.startsWith("${settings.topic}/") }:
      //case { it.startsWith("${settings.topic}/") && (it.indexOf('/', "${settings.topic}/".size()) < 0) }:
         //log.trace "is device --> ${parsedMsg.topic} ---> PAYLOAD: ${parsedMsg.payload}"
         String friendlyName = parsedMsg.topic.substring("${settings.topic}/".length())
         String ieeeAddress =  devices[device.idAsLong].find { it.friendly_name == friendlyName }.ieee_address
         DeviceWrapper hubitatDevice = parent.getChildDevice("Zig2M/${state.parentId ?: parent.getTheAppId()}/${ieeeAddress}")
         if (hubitatDevice == null) break
         //log.trace "**DEV = $hubitatDevice"
         List<Map> evts = parsePayloadToEvents(friendlyName, parsedMsg.payload)
         if (evts) hubitatDevice.parse(evts)
         break
      default:
         if (enableDebug) log.debug "ignore: $parsedMsg"
   }
}

List<Map> parsePayloadToEvents(String friendlyName, String payload) {
   if (enableDebug) log.debug "parsePayloadToEvents($friendlyName, $payload)"
      List<Map> eventList = []
   if (payload.startsWith("{") || payload.trim().startsWith("{")) {
      Map payloadMap = parseJson(payload)
      log.warn payloadMap
      payloadMap.each { String key, value ->
         switch (key) {
            ///// Actuators
            case "state":
               String eventValue = (value == "ON") ? "on" : "off"
               eventList << [name: "switch", value: eventValue]
               break
            case "brightness":
               if (value == null) break
               Integer eventValue = Math.round((value as Float) / 255 * 100)
               eventList << [name: "level", value: eventValue, unit: "%"] 
               break
            case "color_temp":
            if (value == null) break
               Integer eventValue = Math.round(1000000.0 / (value as Float))
               eventList << [name: "colorTemperature", value: eventValue, unit: "K"] 
               break
            // TODO: color, color_hs, color_xy?
            ///// Sensors
            case "contact":
               String eventValue = value == true ? "closed" : "open"
               eventList << [name: "contact", value: eventValue] 
               break
            case "occupancy":
               String eventValue = value == true ? "active" : "inactive"
               eventList << [name: "motion", value: eventValue] 
               break
            case "temperature":
            if (value == null) break
               String origUnit = (devices[device.idAsLong].find { friendly_name == friendlyName }?.definition?.exposes?.find {
                     it.name == "contact"
                  }?.unit?.endsWith("F")) ? "F" : "C"
               BigDecimal eventValue
               if ((origUnit == "C" && location.temperatureScale == "C") || (origUnit == "F" && location.temperatureScale == "F")) {
                  eventValue = ((value as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
               }
               else if (origUnit == "C" && location.temperatureScale == "F") {
                  eventValue = celsiusToFahrenheit((value as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
               }
               else { // origUnit == "F" && location.temperatureScale == "C" 
                  eventValue = fahrenheitTocelsius((value as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
               }
               eventList << [name: "temperature", value: eventValue, unit: "°${location.temperatureScale}"] 
               break
            case "humidity":
            if (value == null) break
               Integer eventValue = Math.round(value as Float)
               eventList << [name: "humidity", value: eventValue, unit: "%"] 
               break
            case "illuminance_lux":
            if (value == null) break
               Integer eventValue = Math.round(value as Float)
               eventList << [name: "illuminance", value: eventValue, unit: "lux"] 
               break
            ///// Buttons
            case "action":
               // a bit different from the rest; gets converted to Hubitat-friendly events in custom driver:
               eventList << [name: "action", value: value]
               break
            default:
               if (enableDebug) log.debug "ignoring $key = $value"
         }
      }
   }
   else {
      if (enableDebug) log.debug "not parsing payload to events because probably not JSON: $payload"
   }
   return eventList
}

void mqttClientStatus(String message) {
   if (enableDebug) log.debug "mqttClientStatus($message)"
   if ((message.startsWith("Status: Connection succeeded"))) {
      doSendEvent("status", "connected")
      state.connectionRetryTime = 5
      unschedule("reconnect")
      pauseExecution(250)
      subscribeToTopic()
   }
   else if (!(interfaces.mqtt.isConnected())) {
      doSendEvent("status", "disconnected")
      if (!(state.connectionRetryTime)) {
         state.connectionRetryTime = 5
      }
      else if (state.connectionRetryTime < 60) {
         state.connectionRetryTime += 10
      }
      else if (state.connectionRetryTime < 300) {
         state.connectionRetryTime += 30
      }
      else {
         state.connectionRetryTime = 300 // cap at 5 minutes
      }
      runIn(state.connectionRetryTime, "reconnect")
   }
   else {
      log.warn "MQTT client status: $message"
   }
}

void subscribeToTopic(String toTopic = "${settings.topic}/#") {
   if (enableDebug) log.debug "subscribe($toTopic)"
   log.trace "is connected = ${interfaces.mqtt.isConnected()}"
   interfaces.mqtt.subscribe(toTopic)
}

// Note: prepends base topic to 'topic' parameter
void publish(String topic, String payload="", Integer qos=0, Boolean retained=false) {
   if (enableDebug) log.debug "publish(topic = $topic, payload = $payload, qos = $qos, retained = $retained)"
   interfaces.mqtt.publish("${settings.topic}/${topic}", payload, qos, retained)
}

// Finds device IEEE and prepends base topic and friendly name to 'topic' parameter
void publishForIEEE(String ieee, String topic, Map jsonPayload="", Integer qos=0, Boolean retained=false) {
   if (enableDebug) log.debug "publishForIEEE(ieee = $ieee, topic = $topic, jsonPayload = $jsonPayload, qos = $qos, retained = $retained)"
   String friendlyName = devices[device.idAsLong].find { it.ieee_address == ieee }?.friendly_name
   if (friendlyName != null) {
      String json = JsonOutput.toJson(jsonPayload)
      if (enableDebug) log.debug "publishing: topic = ${settings.topic}/${friendlyName}/${topic}, payload = ${json}"
      interfaces.mqtt.publish("${settings.topic}/${friendlyName}/${topic}", json, qos, retained)
   }
   else {
      if (enableDebug) log.debug "not publishing; no device found for IEEE $ieee"
   }
}

// Returns devices in "raw" Z2M format (except parsed into List); used by parent app
List getDeviceList() {
   return devices[device.idAsLong] ?: []
}

void logDevices(Boolean prettyPrint=true) {
   if (!prettyPrint) log.trace devices[device.idAsLong]
   else log.trace groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(devices[device.idAsLong]))

   devices[device.idAsLong].find { it.friendly_name == "Sengled Z2M" }.definition.exposes.each {
      log.warn it
   }
}

private void doSendEvent(String eventName, eventValue) {
   //if (enableDebug) log.debug ("Creating event for $eventName...")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   if (settings.enableDesc) log.info descriptionText
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)
}