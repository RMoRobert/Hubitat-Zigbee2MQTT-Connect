/**
 * ===================  Generic Component Motion (w/ Battery) Driver ========================
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
 *  Changelog:
 *  2021-11-18 Initial release
 */

import groovy.transform.Field

@Field static final List<String> parsableAttributes = ["battery", "motion"]
@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "Generic Component Motion (with Battery) Sensor", namespace: "RMoRobert", author: "Robert Morris", component: true) {
      capability "Sensor"
      capability "Battery"
      capability "MotionSensor"
      capability "TemperatureMeasurement"
      capability "IlluminanceMeasurement"
      capability "Refresh"
   }

   preferences {
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

#include RMoRobert.zigbee2MQTTComponentDriverLibrary_Common
#include RMoRobert.zigbee2MQTTComponentDriverLibrary_Parse