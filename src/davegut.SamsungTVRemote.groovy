/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2024 Version 2.3.9 ====================================================================
a.  Created preset functions (create, execute, trigger). Function work with and without
	SmartThings.  SEE DOCUMENTATION.
b.	Added buttons to support preset functions in dashboards.
c.	Added app codes to built-in app search list.
d.  Created methods to support adding running app automatically to state.appData
	if the SmartThings interface is enabled.
===========================================================================================*/
def driverVer() { return version() }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				singleThreaded: true,
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvRemote/SamsungTVRemote.groovy"
			   ){
		capability "Refresh"
		capability "Configuration"
		capability "SamsungTV"
		command "showMessage", [[name: "Not Implemented"]]
		capability "Switch"
		capability "PushableButton"
		capability "Variable"
	}
	preferences {
		input ("deviceIp", "text", title: "Samsung TV Ip", defaultValue: "")
		if (deviceIp) {
			List modeOptions = ["ART_MODE", "Ambient", "none"]
			if (getDataValue("frameTv") == "false") {
				modeOptions = ["Ambient", "none"]
			}
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: modeOptions, defaultValue: "none")
			input ("logEnable", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging " + helpLogo(),
				   defaultValue: true)
			stPreferences()
		}
		input ("pollInterval","enum", title: "Power Polling Interval (seconds)",
			   options: ["off", "10", "20", "30", "60"], defaultValue: "60")
		input ("wsIdleClose", "enum", title: "Close idle websocket after (minutes)",
			   options: ["never", "10", "30", "60"], defaultValue: "never")
		tvAppsPreferences()
	}
}

String helpLogo() { // library marker davegut.kasaCommon, line 11
	return """<a href="https://github.com/DaveGut/HubitatActive/blob/master/SamsungTvRemote/README.md">""" +
		"""<div style="position: absolute; top: 10px; right: 10px; height: 80px; font-size: 20px;">Samsung TV Remote Help</div></a>"""
}

//	===== Installation, setup and update =====
def installed() {
	state.token = "12345678"
	def tokenSupport = "false"
	sendEvent(name: "wsStatus", value: "closed")
//	sendEvent(name: "numberOfButtons", value: "60")
	runIn(1, updated)
}

def updated() {
	sendEvent(name: "numberOfButtons", value: "60")
	unschedule()
	close()
	state.wsData = ""
	def updStatus = [:]
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
		}
		if (logEnable) { runIn(1800, debugLogOff) }
		updStatus << [logEnable: logEnable, infoLog: infoLog]
		updStatus << [setOnPollInterval: setOnPollInterval()]
		sendEvent(name: "numberOfButtons", value: 60)
		sendEvent(name: "wsStatus", value: "closed")
		def action = configure()
		if (!state.appData) { state.appData = [:] }
		updStatus << [updApps: updateAppCodes()]
	}
	logInfo("updated: ${updStatus}")
}

def setOnPollInterval() {
	if (pollInterval == null) {
		pollInterval = "60"
		device.updateSetting("pollInterval", [type:"enum", value: "60"])
	}
	if (pollInterval == "60") {
		runEvery1Minute(onPoll)
	} else if (pollInterval != "off") {
		schedule("0/${pollInterval} * * * * ?",  onPoll)
	}
	return pollInterval
}

def configure() {
	def respData = [:]
	def tvData = [:]
	try{
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			tvData = resp.data
			runIn(1, getArtModeStatus)
		}
	} catch (error) {
		tvData << [status: "error", data: error]
		logError("configure: TV Off during setup or Invalid IP address.\n\t\tTurn TV On and Run CONFIGURE or Save Preferences!")

	}
	if (!tvData.status) {
		def wifiMac = tvData.device.wifiMac
		updateDataValue("deviceMac", wifiMac)
		def alternateWolMac = wifiMac.replaceAll(":", "").toUpperCase()
		updateDataValue("alternateWolMac", alternateWolMac)
		device.setDeviceNetworkId(alternateWolMac)
		def modelYear = "20" + tvData.device.model[0..1]
		updateDataValue("modelYear", modelYear)
		def frameTv = "false"
		if (tvData.device.FrameTVSupport) {
			frameTv = tvData.device.FrameTVSupport
		}
		updateDataValue("frameTv", frameTv)
		if (tvData.device.TokenAuthSupport) {
			tokenSupport = tvData.device.TokenAuthSupport
			updateDataValue("tokenSupport", tokenSupport)
		}
		def uuid = tvData.device.duid.substring(5)
		updateDataValue("uuid", uuid)
		respData << [status: "OK", dni: alternateWolMac, modelYear: modelYear,
					 frameTv: frameTv, tokenSupport: tokenSupport]
		sendEvent(name: "artModeStatus", value: "none")
		def data = [request:"get_artmode_status",
					id: "${getDataValue("uuid")}"]
		data = JsonOutput.toJson(data)
		artModeCmd(data)
	} else {
		respData << tvData
	}
	runIn(1, stUpdate)
	logInfo("configure: ${respData}")
	return respData
}

//	===== Polling/Refresh Capability =====
def onPoll() {
	def sendCmdParams = [
		uri: "http://${deviceIp}:8001/api/v2/",
		timeout: 6
	]
	asynchttpGet("onPollParse", sendCmdParams)
	if (getDataValue("driverVersion") != driverVer()) {
		logInfo("Auto Configuring changes to this TV.")
		updateDriver()
		pauseExecution(3000)
	}
}

def updateDriver() {
}

def onPollParse(resp, data) {
	def powerState
	if (resp.status == 200) {
		powerState = new JsonSlurper().parseText(resp.data).device.PowerState
	} else {
		powerState = "NC"
	}
	def onOff = "off"
	if (powerState == "on") { onOff = "on" }
	//	recent websocket failure overrides a stale REST "on"
	if (onOff == "on" && state.lastWsFailure &&
		now() - state.lastWsFailure < 35000) {
		onOff = "off"
		powerState = "${powerState}/wsFailed"
	}
	//	hold off through the post-off cooldown
	if (state.powerOffAt && now() - state.powerOffAt < 6000) {
		onOff = "off"
	}
	Map logData = [method: "onPollParse", httpStatus: resp.status,
				   powerState: powerState, onOff: onOff]
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		logData << [switch: onOff]
		if (onOff == "on") {
			runIn(5, setPowerOnMode)
		} else {
			setPowerOffMode()
		}
	}
	logDebug(logData)
}

//	===== Capability Switch =====
def on() {
	logInfo("on: [frameTv: ${getDataValue("frameTv")}]")
	//	WoL only: KEY_POWER is a toggle and can switch an on TV off
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	//	send to both WoL ports (9 and 7)
	["255.255.255.255:9", "255.255.255.255:7"].each { dest ->
		sendHubCommand(new hubitat.device.HubAction(cmd,
			hubitat.device.Protocol.LAN,
			[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
			 destinationAddress: dest,
			 encoding: hubitat.device.HubAction.Encoding.HEX_STRING]))
	}
	runIn(1, onPoll)
}

def setPowerOnMode() {
	logInfo("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	if(tvPwrOnMode == "ART_MODE") {
		getArtModeStatus()
		pauseExecution(1000)
		artMode()
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
	runIn(5, refresh)
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	state.powerOffAt = now()	//	post-off cooldown for onPollParse
	if (getDataValue("frameTv") == "true") {
		//	Frame TV: short press = art mode, power-off needs a held press
		if (device.currentValue("wsStatus") == "open") {
			powerHold()
		} else {
			state.pendingPowerHold = true
			connect("remote")
		}
	} else {
		sendKey("POWER")
		runIn(1, onPoll)
	}
}

def powerHold() {
	sendKey("POWER", "Press")
	runInMillis(3000, powerHoldRelease)
}

def powerHoldRelease() {
	sendKey("POWER", "Release")
	runIn(2, onPoll)
}

def setPowerOffMode() {
	logInfo("setPowerOfMode")
	sendEvent(name: "appId", value: " ")
	sendEvent(name: "appName", value: " ")
	sendEvent(name: "tvChannel", value: " ")
	sendEvent(name: "tvChannelName", value: " ")
	runIn(5, refresh)
}

def setVariable(valueString) {
	sendEvent(name: "variable", value: valueString)
}

def refresh() {
	if (connectST) {
		deviceRefresh()
	}
	runIn(4, updateTitle)
}

//	===== BUTTON INTERFACE =====
def push(pushed) {
	logDebug("push: button = ${pushed}, trigger = ${state.triggered}")
	if (pushed == null) {
		logWarn("push: pushed is null.  Input ignored")
		return
	}
	pushed = pushed.toInteger()
	switch(pushed) {
		//	===== Physical Remote Commands =====
		case 2 : mute(); break			// toggles mute/unmute
		case 3 : numericKeyPad(); break
		case 4 : Return(); break		//	Goes back one level on apps. I.e., if playing, goes to
										//	app main screen
		case 6 : artMode(); break
		case 7 : ambientMode(); break
		case 8 : arrowLeft(); break
		case 9 : arrowRight(); break
		case 10: arrowUp(); break
		case 11: arrowDown(); break
		case 12: enter(); break			//	Executes selected OSD item (both apps and tv menus.
		case 13: exit(); break			//	Exits last selected item (if playing in app, will exit
										//	and return to app main screen.  If on main screen, exits app.
		case 14: home(); break			//	toggles on/off the TV's smart control page.
		case 18: channelUp(); break
		case 19: channelDown(); break
		case 20: guide(); break
		case 21: volumeUp(); break
		case 22: volumeDown(); break
		//	===== Direct Access Functions
		case 23: menu(); break			//	Calls upd OSD setting menu for TV.
		case 26: channelList(); break
		case 27: play(); break			//	Player control (playing app media that is not streaming
		case 28: pause(); break			//	(i.e., a recording in app).
		case 29: stop(); break			//	Exits the playing recording, returns to menu
		//	===== Other Commands =====
		case 34: previousChannel(); break	//	aka back
		case 35: sourceToggle(); break		//	Changed from hdmi().  Goes to next active source.
		case 36: fastBack(); break		//	player control
		case 37: fastForward(); break	//	player control
		//	===== Application Interface =====
		case 42: toggleSoundMode(); break		//	ST function
		case 43: togglePictureMode(); break		//	ST function
		case 44: 
			// Allows opening an app by name.  Enter using dashboard tile variable
			//	which creates the attribute variable.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button44: error: null variable, action: use setVariable to enter the variable]")
			} else {
				appOpenByName(variable)
			}
			sendEvent(name: "variable", value: " ")
			break
		
		case 45:
			//	TV Channel Preset Function.  Allows adding TV channels without the
			//	SmartThing interface.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button45: error: null variable, action: use setVariable to enter the variable]")
				logWarn("{button45: variable must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
			} else {
				def arr = variable.split(",")
				if (arr.size() == 3) {
					try {
						arr[0].trim().toInteger()
						arr[1].trim().toInteger()
						presetCreateTv(arr[0].trim(), arr[1].trim(), arr[2].trim())
					} catch (err) {
						logWarn("{button45: [variable: ${variable}, error: must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
					}
				} else {
					logWarn("{button45: [variable: ${variable}, error: must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
				}
			}
			sendEvent(name: "variable", value: " ")
			break
		case 46:
			//	TV Channel set.  Must first enter variable.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button46: error: null variable, action: use setVariable to enter the variable]")
			} else {
				channelSet(variable)
			}
			sendEvent(name: "variable", value: " ")
			break
		//	Trigger function.  Once pressed, the preset buttons become ADD for 5 seconds.
		case 50: presetUpdateNext(); break
		case 51: presetExecute("1"); break
		case 52: presetExecute("2"); break
		case 53: presetExecute("3"); break
		case 54: presetExecute("4"); break
		case 55: presetExecute("5"); break
		case 56: presetExecute("6"); break
		case 57: presetExecute("7"); break
		case 58: presetExecute("8"); break
		case 59: presetExecute("9"); break
		case 60: presetExecute("10"); break
		default:
			logDebug("push: Invalid Button Number!")
			break
	}
}

//	===== Libraries =====
//#include davegut.samsungTvTEST

#include davegut.samsungTvWebsocket
#include davegut.samsungTvApps
#include davegut.samsungTvPresets
#include davegut.SmartThingsInterface
#include davegut.samsungTvST
#include davegut.Logging
