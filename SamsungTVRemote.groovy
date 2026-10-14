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
===== Modifications =======================================================================
This file was modified by hubitrep in 2026 (versions 2.3.10 and later).
Changes: https://github.com/hubitrep/SamsungTvRemote#changes
===========================================================================================*/
def driverVer() { return version() }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				singleThreaded: true,
				importUrl: "https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/main/SamsungTVRemote.groovy"
			   ){
		capability "Refresh"
		capability "Configuration"
		capability "Initialize"
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

//	called on hub start: the socket did not survive the reboot
def initialize() {
	close()
}

def updated() {
	sendEvent(name: "numberOfButtons", value: "60")
	unschedule()
	close()
	["wsQueue", "currentFunction", "pendingPowerHold", "powerProbeAt"].each { state.remove(it) }
	sendEvent(name: "presetUpdateNext", value: "false")
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
		if (getDataValue("alternateWolMac")) {
			tvData << [action: "kept saved TV settings"]
		} else {
			logError("configure: TV Off during setup or Invalid IP address.\n\t\tTurn TV On and Run CONFIGURE or Save Preferences!")
		}

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
		sendEvent(name: "artModeStatus", value: "none")		//	getArtModeStatus (scheduled above) asks a Frame
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
	//	after a reboot the attribute still says open; the in-memory socket state does not
	if (ws().phase == "closed" && device.currentValue("wsStatus") == "open") {
		sendEvent(name: "wsStatus", value: "closed")
	}
	if (getDataValue("driverVersion") != driverVer()) {
		updateDriver()
	}
}

def updateDriver() {
	logWarn("driver updated to ${driverVer()}")
	//	version-specific state migrations go here (old version still in getDataValue("driverVersion"))
	updateDataValue("driverVersion", driverVer())
	runIn(1, updated)
}

def onPollParse(resp, data) {
	def powerState = "NC"
	def onOff = "off"
	Map info = null
	if (resp.status == 200) {
		try {
			info = new JsonSlurper().parseText(resp.data)?.device
		} catch (e) {
			logDebug([method: "onPollParse", error: "unparsable reply"])
		}
	}
	if (info != null) {
		powerState = info.PowerState
		if (powerState == null) {
			//	newer firmware omits PowerState; fall back to remote-socket liveness
			powerState = "absent"
			Map s = ws()
			if (s.funct == "remote" && s.phase == "ready") {
				onOff = "on"
			} else if (s.funct == "frameArt" && s.phase != "closed") {
				return		//	the art channel is in use; don't replace it to probe
			} else if (!powerOffHeld()) {		//	a TV shutting down still accepts the socket
				probePower()
				return
			}
		}
	}
	if (powerState == "on") { onOff = "on" }
	setPowerState(onOff, [method: "onPollParse", httpStatus: resp.status, powerState: powerState])
}

//	open the remote socket; wsReady or a failure reports the result to setPowerState
def probePower() {
	Map s = ws()
	if (s.probeAt && now() - s.probeAt < 20000) { return }
	if (s.funct == "remote" && s.phase in ["connecting", "open"]) { return }
	s.probeAt = now()
	logDebug([method: "probePower"])
	connect("remote")
}

//	the TV answers for a few seconds after power-off: skip the next poll, plus 5 s
def powerOffHeld() {
	def secs = (pollInterval && pollInterval != "off") ? pollInterval.toInteger() : 30
	return state.powerOffAt && now() - state.powerOffAt < (secs + 5) * 1000
}

def setPowerState(onOff, Map logData) {
	//	recent websocket failure overrides a stale REST "on"
	if (onOff == "on" && state.lastWsFailure &&
		now() - state.lastWsFailure < 35000) {
		onOff = "off"
		logData << [wsFailed: true]
	}
	//	hold off through the post-off cooldown
	if (powerOffHeld()) {
		onOff = "off"
	}
	logData << [onOff: onOff]
	String prev = device.currentValue("switch")
	sendEvent(name: "switch", value: onOff)
	if (prev != onOff) {
		logData << [switch: onOff]
		if (onOff == "on") {
			//	startup display only after on(), not when a poll blip recovers
			if (state.powerOnAt && now() - state.powerOnAt < 120000) {
				runIn(5, setPowerOnMode)
			}
			runIn(5, refresh)
		} else {
			setPowerOffMode()
		}
	}
	if (onOff == "on") { state.powerOnAt = null }
	logDebug(logData)
}

//	===== Capability Switch =====
def on() {
	logInfo("on: [frameTv: ${getDataValue("frameTv")}]")
	state.powerOnAt = now()
	state.powerOffAt = null
	state.lastWsFailure = null
	//	WoL only: KEY_POWER is a toggle and can switch an on TV off
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	//	send to both WoL ports (9 and 7)
	["255.255.255.255:9", "255.255.255.255:7"].each { dest ->
		sendHubCommand(new hubitat.device.HubAction(cmd,
			hubitat.device.Protocol.LAN,
			[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
			 destinationAddress: dest,
			 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
			 ignoreResponse: true]))
	}
	//	the TV takes several seconds to boot
	runIn(10, onPoll)
	runIn(20, onPollAgain)
}

def onPollAgain() { onPoll() }

def setPowerOnMode() {
	logInfo("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	if(tvPwrOnMode == "ART_MODE") {
		getArtModeStatus()
		runIn(2, artMode)		//	after the status reply
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	state.powerOffAt = now()	//	post-off cooldown for onPollParse
	state.powerOnAt = null
	Map s = ws()
	boolean ready = s.phase == "ready" && s.funct == "remote"
	if (getDataValue("frameTv") == "true") {
		//	Frame TV: short press = art mode, power-off needs a held press
		if (ready) {
			powerHold()
		} else {
			if (s.funct != "remote" && s.phase != "closed") { close() }
			s.holdAt = now()		//	wsReady starts the hold
			if (s.phase == "closed") { connect("remote") }
		}
	} else {
		sendKey("POWER")
		//	an open socket outlives the TV and would read as on: close once the key is out
		if (ready) { runIn(2, close) } else { ws().closeAfterSend = true }
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
	runIn(3, close)
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
	if (stConnected()) {
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

// ~~~~~ start include (1) davegut.samsungTvWebsocket ~~~~~
library ( // library marker davegut.samsungTvWebsocket, line 1
	name: "samsungTvWebsocket", // library marker davegut.samsungTvWebsocket, line 2
	namespace: "davegut", // library marker davegut.samsungTvWebsocket, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvWebsocket, line 4
	description: "Common Samsung TV Websocket Commands", // library marker davegut.samsungTvWebsocket, line 5
	category: "utilities", // library marker davegut.samsungTvWebsocket, line 6
	documentationLink: "" // library marker davegut.samsungTvWebsocket, line 7
) // library marker davegut.samsungTvWebsocket, line 8
//	Modified by hubitrep in 2026. Changes: https://github.com/hubitrep/SamsungTvRemote#changes // library marker davegut.samsungTvWebsocket, line 9
 // library marker davegut.samsungTvWebsocket, line 10
import groovy.json.JsonOutput // library marker davegut.samsungTvWebsocket, line 11
import groovy.transform.Field // library marker davegut.samsungTvWebsocket, line 12
import java.util.concurrent.ConcurrentHashMap // library marker davegut.samsungTvWebsocket, line 13
 // library marker davegut.samsungTvWebsocket, line 14
//	socket state is kept in memory, so a hub reboot or code push starts from "closed" // library marker davegut.samsungTvWebsocket, line 15
@Field static ConcurrentHashMap<String, Map> wsMem = new ConcurrentHashMap<String, Map>() // library marker davegut.samsungTvWebsocket, line 16
 // library marker davegut.samsungTvWebsocket, line 17
command "webSocketClose" // library marker davegut.samsungTvWebsocket, line 18
command "webSocketOpen" // library marker davegut.samsungTvWebsocket, line 19
command "close" // library marker davegut.samsungTvWebsocket, line 20
attribute "wsStatus", "string" // library marker davegut.samsungTvWebsocket, line 21
command "artMode" // library marker davegut.samsungTvWebsocket, line 22
attribute "artModeStatus", "string" // library marker davegut.samsungTvWebsocket, line 23
command "ambientMode" // library marker davegut.samsungTvWebsocket, line 24
//	Remote Control Keys (samsungTV-Keys) // library marker davegut.samsungTvWebsocket, line 25
command "pause" // library marker davegut.samsungTvWebsocket, line 26
command "play" // library marker davegut.samsungTvWebsocket, line 27
command "stop" // library marker davegut.samsungTvWebsocket, line 28
command "sendKey", ["string"] // library marker davegut.samsungTvWebsocket, line 29
command "getInstalledApps"		//	diagnostic: query the TV's installed-app list (WS) // library marker davegut.samsungTvWebsocket, line 30
//	Cursor and Entry Control // library marker davegut.samsungTvWebsocket, line 31
command "arrowLeft" // library marker davegut.samsungTvWebsocket, line 32
command "arrowRight" // library marker davegut.samsungTvWebsocket, line 33
command "arrowUp" // library marker davegut.samsungTvWebsocket, line 34
command "arrowDown" // library marker davegut.samsungTvWebsocket, line 35
command "enter" // library marker davegut.samsungTvWebsocket, line 36
command "numericKeyPad" // library marker davegut.samsungTvWebsocket, line 37
//	Menu Access // library marker davegut.samsungTvWebsocket, line 38
command "home" // library marker davegut.samsungTvWebsocket, line 39
command "menu" // library marker davegut.samsungTvWebsocket, line 40
command "guide" // library marker davegut.samsungTvWebsocket, line 41
//command "info"	//  enter // library marker davegut.samsungTvWebsocket, line 42
//	Source Commands // library marker davegut.samsungTvWebsocket, line 43
command "sourceSetOSD" // library marker davegut.samsungTvWebsocket, line 44
command "sourceToggle" // library marker davegut.samsungTvWebsocket, line 45
//command "hdmi" // library marker davegut.samsungTvWebsocket, line 46
//	TV Channel // library marker davegut.samsungTvWebsocket, line 47
command "channelList" // library marker davegut.samsungTvWebsocket, line 48
command "channelUp" // library marker davegut.samsungTvWebsocket, line 49
command "channelDown" // library marker davegut.samsungTvWebsocket, line 50
command "channelSet", ["string"] // library marker davegut.samsungTvWebsocket, line 51
command "previousChannel" // library marker davegut.samsungTvWebsocket, line 52
//	Playing Navigation Commands // library marker davegut.samsungTvWebsocket, line 53
command "exit" // library marker davegut.samsungTvWebsocket, line 54
command "Return" // library marker davegut.samsungTvWebsocket, line 55
command "fastBack" // library marker davegut.samsungTvWebsocket, line 56
command "fastForward" // library marker davegut.samsungTvWebsocket, line 57
 // library marker davegut.samsungTvWebsocket, line 58
//	== ART/Ambient Mode // library marker davegut.samsungTvWebsocket, line 59
def artMode() { // library marker davegut.samsungTvWebsocket, line 60
	def artModeStatus = device.currentValue("artModeStatus") // library marker davegut.samsungTvWebsocket, line 61
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs] // library marker davegut.samsungTvWebsocket, line 62
	if (getDataValue("frameTv") != "true") { // library marker davegut.samsungTvWebsocket, line 63
		logData << [status: "Not a Frame TV"] // library marker davegut.samsungTvWebsocket, line 64
	} else if (artModeStatus == "on") { // library marker davegut.samsungTvWebsocket, line 65
		logData << [status: "artMode already set"] // library marker davegut.samsungTvWebsocket, line 66
	} else { // library marker davegut.samsungTvWebsocket, line 67
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 68
			def data = [value:"on", // library marker davegut.samsungTvWebsocket, line 69
						request:"set_artmode_status", // library marker davegut.samsungTvWebsocket, line 70
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 71
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 72
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 73
			logData << [status: "Sending artMode WS Command"] // library marker davegut.samsungTvWebsocket, line 74
		} else { // library marker davegut.samsungTvWebsocket, line 75
			sendKey("POWER") // library marker davegut.samsungTvWebsocket, line 76
			logData << [status: "Sending Power WS Command"] // library marker davegut.samsungTvWebsocket, line 77
			if (artModeStatus == "none") { // library marker davegut.samsungTvWebsocket, line 78
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"] // library marker davegut.samsungTvWebsocket, line 79
			} // library marker davegut.samsungTvWebsocket, line 80
		} // library marker davegut.samsungTvWebsocket, line 81
		runIn(10, getArtModeStatus) // library marker davegut.samsungTvWebsocket, line 82
	} // library marker davegut.samsungTvWebsocket, line 83
	logInfo("artMode: ${logData}") // library marker davegut.samsungTvWebsocket, line 84
} // library marker davegut.samsungTvWebsocket, line 85
 // library marker davegut.samsungTvWebsocket, line 86
def getArtModeStatus() { // library marker davegut.samsungTvWebsocket, line 87
	if (getDataValue("frameTv") == "true") { // library marker davegut.samsungTvWebsocket, line 88
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 89
			def data = [request:"get_artmode_status", // library marker davegut.samsungTvWebsocket, line 90
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 91
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 92
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 93
		} else { // library marker davegut.samsungTvWebsocket, line 94
			refresh() // library marker davegut.samsungTvWebsocket, line 95
		} // library marker davegut.samsungTvWebsocket, line 96
	} // library marker davegut.samsungTvWebsocket, line 97
} // library marker davegut.samsungTvWebsocket, line 98
 // library marker davegut.samsungTvWebsocket, line 99
def artModeCmd(data) { // library marker davegut.samsungTvWebsocket, line 100
	def cmdData = [method:"ms.channel.emit", // library marker davegut.samsungTvWebsocket, line 101
				   params:[data:"${data}", // library marker davegut.samsungTvWebsocket, line 102
						   to:"host", // library marker davegut.samsungTvWebsocket, line 103
						   event:"art_app_request"]] // library marker davegut.samsungTvWebsocket, line 104
	cmdData = JsonOutput.toJson(cmdData) // library marker davegut.samsungTvWebsocket, line 105
	sendMessage("frameArt", cmdData) // library marker davegut.samsungTvWebsocket, line 106
} // library marker davegut.samsungTvWebsocket, line 107
 // library marker davegut.samsungTvWebsocket, line 108
def ambientMode() { // library marker davegut.samsungTvWebsocket, line 109
	sendKey("AMBIENT") // library marker davegut.samsungTvWebsocket, line 110
	runIn(10, refresh) // library marker davegut.samsungTvWebsocket, line 111
} // library marker davegut.samsungTvWebsocket, line 112
 // library marker davegut.samsungTvWebsocket, line 113
//	== Remote Commands // library marker davegut.samsungTvWebsocket, line 114
//	KEY_MUTE toggles; SmartThings sets the state directly // library marker davegut.samsungTvWebsocket, line 115
def mute() { // library marker davegut.samsungTvWebsocket, line 116
	if (stConnected()) { setMuteST("mute") } // library marker davegut.samsungTvWebsocket, line 117
	else if (device.currentValue("mute") != "muted") { sendKey("MUTE") } // library marker davegut.samsungTvWebsocket, line 118
} // library marker davegut.samsungTvWebsocket, line 119
 // library marker davegut.samsungTvWebsocket, line 120
def unmute() { // library marker davegut.samsungTvWebsocket, line 121
	if (stConnected()) { setMuteST("unmute") } // library marker davegut.samsungTvWebsocket, line 122
	else if (device.currentValue("mute") != "unmuted") { sendKey("MUTE") } // library marker davegut.samsungTvWebsocket, line 123
} // library marker davegut.samsungTvWebsocket, line 124
 // library marker davegut.samsungTvWebsocket, line 125
def volumeUp() { sendKeyThenRefresh("VOLUP") } // library marker davegut.samsungTvWebsocket, line 126
 // library marker davegut.samsungTvWebsocket, line 127
def volumeDown() { sendKeyThenRefresh("VOLDOWN") } // library marker davegut.samsungTvWebsocket, line 128
 // library marker davegut.samsungTvWebsocket, line 129
def play() { sendKeyThenRefresh("PLAY") } // library marker davegut.samsungTvWebsocket, line 130
 // library marker davegut.samsungTvWebsocket, line 131
def pause() { sendKeyThenRefresh("PAUSE") } // library marker davegut.samsungTvWebsocket, line 132
 // library marker davegut.samsungTvWebsocket, line 133
def stop() { sendKeyThenRefresh("STOP") } // library marker davegut.samsungTvWebsocket, line 134
 // library marker davegut.samsungTvWebsocket, line 135
def exit() { sendKeyThenRefresh("EXIT") } // library marker davegut.samsungTvWebsocket, line 136
 // library marker davegut.samsungTvWebsocket, line 137
def Return() { sendKeyThenRefresh("RETURN") } // library marker davegut.samsungTvWebsocket, line 138
 // library marker davegut.samsungTvWebsocket, line 139
def fastBack() { // library marker davegut.samsungTvWebsocket, line 140
	sendKey("LEFT", "Press") // library marker davegut.samsungTvWebsocket, line 141
	runInMillis(1000, keyRelease, [data: [key: "LEFT"]]) // library marker davegut.samsungTvWebsocket, line 142
} // library marker davegut.samsungTvWebsocket, line 143
 // library marker davegut.samsungTvWebsocket, line 144
def fastForward() { // library marker davegut.samsungTvWebsocket, line 145
	sendKey("RIGHT", "Press") // library marker davegut.samsungTvWebsocket, line 146
	runInMillis(1000, keyRelease, [data: [key: "RIGHT"]]) // library marker davegut.samsungTvWebsocket, line 147
} // library marker davegut.samsungTvWebsocket, line 148
 // library marker davegut.samsungTvWebsocket, line 149
def keyRelease(data) { sendKey(data.key, "Release") } // library marker davegut.samsungTvWebsocket, line 150
 // library marker davegut.samsungTvWebsocket, line 151
def arrowLeft() { sendKey("LEFT") } // library marker davegut.samsungTvWebsocket, line 152
 // library marker davegut.samsungTvWebsocket, line 153
def arrowRight() { sendKey("RIGHT") } // library marker davegut.samsungTvWebsocket, line 154
 // library marker davegut.samsungTvWebsocket, line 155
def arrowUp() { sendKey("UP") } // library marker davegut.samsungTvWebsocket, line 156
 // library marker davegut.samsungTvWebsocket, line 157
def arrowDown() { sendKey("DOWN") } // library marker davegut.samsungTvWebsocket, line 158
 // library marker davegut.samsungTvWebsocket, line 159
def enter() { sendKeyThenRefresh("ENTER") } // library marker davegut.samsungTvWebsocket, line 160
 // library marker davegut.samsungTvWebsocket, line 161
def numericKeyPad() { sendKey("MORE") } // library marker davegut.samsungTvWebsocket, line 162
 // library marker davegut.samsungTvWebsocket, line 163
def home() { sendKey("HOME") } // library marker davegut.samsungTvWebsocket, line 164
 // library marker davegut.samsungTvWebsocket, line 165
def menu() { sendKey("MENU") } // library marker davegut.samsungTvWebsocket, line 166
 // library marker davegut.samsungTvWebsocket, line 167
def guide() { sendKey("GUIDE") } // library marker davegut.samsungTvWebsocket, line 168
 // library marker davegut.samsungTvWebsocket, line 169
def info() { enter() } // library marker davegut.samsungTvWebsocket, line 170
 // library marker davegut.samsungTvWebsocket, line 171
def source() { sourceSetOSD() } // library marker davegut.samsungTvWebsocket, line 172
def sourceSetOSD() { sendKey("SOURCE") } // library marker davegut.samsungTvWebsocket, line 173
 // library marker davegut.samsungTvWebsocket, line 174
def hdmi() { sourceToggle() } // library marker davegut.samsungTvWebsocket, line 175
def sourceToggle() { sendKeyThenRefresh("HDMI") } // library marker davegut.samsungTvWebsocket, line 176
 // library marker davegut.samsungTvWebsocket, line 177
def channelList() { sendKey("CH_LIST") } // library marker davegut.samsungTvWebsocket, line 178
 // library marker davegut.samsungTvWebsocket, line 179
def channelUp() { sendKeyThenRefresh("CHUP") } // library marker davegut.samsungTvWebsocket, line 180
def nextTrack() { channelUp() } // library marker davegut.samsungTvWebsocket, line 181
 // library marker davegut.samsungTvWebsocket, line 182
def channelDown() { sendKeyThenRefresh("CHDOWN") } // library marker davegut.samsungTvWebsocket, line 183
def previousTrack() { channelDown() } // library marker davegut.samsungTvWebsocket, line 184
 // library marker davegut.samsungTvWebsocket, line 185
//	Uses ST interface if available. // library marker davegut.samsungTvWebsocket, line 186
def channelSet(channel) { // library marker davegut.samsungTvWebsocket, line 187
	if (stConnected()) { // library marker davegut.samsungTvWebsocket, line 188
		setTvChannel(channel) // library marker davegut.samsungTvWebsocket, line 189
	} else { // library marker davegut.samsungTvWebsocket, line 190
		for (int i = 0; i < channel.length(); i++) { // library marker davegut.samsungTvWebsocket, line 191
			sendKey(channel[i]) // library marker davegut.samsungTvWebsocket, line 192
		} // library marker davegut.samsungTvWebsocket, line 193
		enter() // library marker davegut.samsungTvWebsocket, line 194
		sendEvent(name: "tvChannel", value: channel) // library marker davegut.samsungTvWebsocket, line 195
	} // library marker davegut.samsungTvWebsocket, line 196
} // library marker davegut.samsungTvWebsocket, line 197
 // library marker davegut.samsungTvWebsocket, line 198
def previousChannel() { sendKeyThenRefresh("PRECH") } // library marker davegut.samsungTvWebsocket, line 199
 // library marker davegut.samsungTvWebsocket, line 200
def showMessage() { logWarn("showMessage: not implemented") } // library marker davegut.samsungTvWebsocket, line 201
 // library marker davegut.samsungTvWebsocket, line 202
//	== WebSocket Communications / Parse // library marker davegut.samsungTvWebsocket, line 203
def sendKeyThenRefresh(key) { // library marker davegut.samsungTvWebsocket, line 204
	sendKey(key) // library marker davegut.samsungTvWebsocket, line 205
	if (stConnected()) { runIn(3, deviceRefresh) } // library marker davegut.samsungTvWebsocket, line 206
} // library marker davegut.samsungTvWebsocket, line 207
 // library marker davegut.samsungTvWebsocket, line 208
def sendKey(key, cmd = "Click") { // library marker davegut.samsungTvWebsocket, line 209
	key = "KEY_${key.toUpperCase()}" // library marker davegut.samsungTvWebsocket, line 210
	def data = [method:"ms.remote.control", // library marker davegut.samsungTvWebsocket, line 211
				params:[Cmd:"${cmd}", // library marker davegut.samsungTvWebsocket, line 212
						DataOfCmd:"${key}", // library marker davegut.samsungTvWebsocket, line 213
						Option:"false", // library marker davegut.samsungTvWebsocket, line 214
						TypeOfRemote:"SendRemoteKey"]] // library marker davegut.samsungTvWebsocket, line 215
	sendMessage("remote", JsonOutput.toJson(data).toString() ) // library marker davegut.samsungTvWebsocket, line 216
} // library marker davegut.samsungTvWebsocket, line 217
 // library marker davegut.samsungTvWebsocket, line 218
def getInstalledApps() { // library marker davegut.samsungTvWebsocket, line 219
	//	Diagnostic: ask the TV for its installed-app list over the remote channel. // library marker davegut.samsungTvWebsocket, line 220
	//	2020+ Tizen often refuses this; parse() "ed.installedApp.get" logs any result. // library marker davegut.samsungTvWebsocket, line 221
	def data = [method:"ms.channel.emit", // library marker davegut.samsungTvWebsocket, line 222
				params:[event:"ed.installedApp.get", to:"host"]] // library marker davegut.samsungTvWebsocket, line 223
	sendMessage("remote", JsonOutput.toJson(data).toString()) // library marker davegut.samsungTvWebsocket, line 224
	logInfo("getInstalledApps: request sent (watch for 'ed.installedApp.get' in logs)") // library marker davegut.samsungTvWebsocket, line 225
} // library marker davegut.samsungTvWebsocket, line 226
 // library marker davegut.samsungTvWebsocket, line 227
Map ws() { // library marker davegut.samsungTvWebsocket, line 228
	String key = device.id.toString() // library marker davegut.samsungTvWebsocket, line 229
	Map m = wsMem[key] // library marker davegut.samsungTvWebsocket, line 230
	if (m == null) { // library marker davegut.samsungTvWebsocket, line 231
		m = [phase: "closed", funct: null, queue: [], queuedAt: null, connectingAt: null, // library marker davegut.samsungTvWebsocket, line 232
			 holdAt: null, probeAt: null, closeAfterSend: false] // library marker davegut.samsungTvWebsocket, line 233
		wsMem[key] = m // library marker davegut.samsungTvWebsocket, line 234
	} // library marker davegut.samsungTvWebsocket, line 235
	return m // library marker davegut.samsungTvWebsocket, line 236
} // library marker davegut.samsungTvWebsocket, line 237
 // library marker davegut.samsungTvWebsocket, line 238
//	phases: closed -> connecting -> open (transport up) -> ready (TV sent ms.channel.connect) // library marker davegut.samsungTvWebsocket, line 239
def sendMessage(funct, data) { // library marker davegut.samsungTvWebsocket, line 240
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 241
	Map logData = [method: "sendMessage", phase: s.phase, funct: funct, socketFunct: s.funct] // library marker davegut.samsungTvWebsocket, line 242
	if (s.phase == "ready" && s.funct == funct) { // library marker davegut.samsungTvWebsocket, line 243
		execMessage(data) // library marker davegut.samsungTvWebsocket, line 244
		logData << [action: "sent"] // library marker davegut.samsungTvWebsocket, line 245
	} else { // library marker davegut.samsungTvWebsocket, line 246
		if (s.funct != funct && s.phase != "closed") { close() } // library marker davegut.samsungTvWebsocket, line 247
		if (s.funct != funct) { s.queue = [] } // library marker davegut.samsungTvWebsocket, line 248
		if (!s.queue) { s.queuedAt = now() } // library marker davegut.samsungTvWebsocket, line 249
		if (s.queue.size() >= 10) { s.queue.remove(0) } // library marker davegut.samsungTvWebsocket, line 250
		s.queue << data // library marker davegut.samsungTvWebsocket, line 251
		boolean stale = s.connectingAt && now() - s.connectingAt > 10000 // library marker davegut.samsungTvWebsocket, line 252
		if (s.phase == "closed" || s.funct != funct || stale) { // library marker davegut.samsungTvWebsocket, line 253
			connect(funct) // library marker davegut.samsungTvWebsocket, line 254
			logData << [action: "connect"] // library marker davegut.samsungTvWebsocket, line 255
		} // library marker davegut.samsungTvWebsocket, line 256
		logData << [queued: s.queue.size()] // library marker davegut.samsungTvWebsocket, line 257
	} // library marker davegut.samsungTvWebsocket, line 258
	armIdleClose() // library marker davegut.samsungTvWebsocket, line 259
	logDebug(logData) // library marker davegut.samsungTvWebsocket, line 260
} // library marker davegut.samsungTvWebsocket, line 261
 // library marker davegut.samsungTvWebsocket, line 262
def armIdleClose() { // library marker davegut.samsungTvWebsocket, line 263
	if (settings.wsIdleClose && settings.wsIdleClose != "never") { // library marker davegut.samsungTvWebsocket, line 264
		runIn(settings.wsIdleClose.toInteger() * 60, close) // library marker davegut.samsungTvWebsocket, line 265
	} // library marker davegut.samsungTvWebsocket, line 266
} // library marker davegut.samsungTvWebsocket, line 267
 // library marker davegut.samsungTvWebsocket, line 268
def execMessage(data) { // library marker davegut.samsungTvWebsocket, line 269
	interfaces.webSocket.sendMessage(data) // library marker davegut.samsungTvWebsocket, line 270
} // library marker davegut.samsungTvWebsocket, line 271
 // library marker davegut.samsungTvWebsocket, line 272
def webSocketOpen() { connect("remote") } // library marker davegut.samsungTvWebsocket, line 273
def webSocketClose() { close() } // library marker davegut.samsungTvWebsocket, line 274
 // library marker davegut.samsungTvWebsocket, line 275
def connect(funct) { // library marker davegut.samsungTvWebsocket, line 276
	logDebug("connect: function = ${funct}") // library marker davegut.samsungTvWebsocket, line 277
	def url // library marker davegut.samsungTvWebsocket, line 278
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ==" // library marker davegut.samsungTvWebsocket, line 279
	//	the art channel takes no token; 2024+ Frames stall the handshake when given one // library marker davegut.samsungTvWebsocket, line 280
	if (getDataValue("tokenSupport") == "true") { // library marker davegut.samsungTvWebsocket, line 281
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 282
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 283
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 284
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}" // library marker davegut.samsungTvWebsocket, line 285
		} // library marker davegut.samsungTvWebsocket, line 286
	} else { // library marker davegut.samsungTvWebsocket, line 287
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 288
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}" // library marker davegut.samsungTvWebsocket, line 289
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 290
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}" // library marker davegut.samsungTvWebsocket, line 291
		} // library marker davegut.samsungTvWebsocket, line 292
	} // library marker davegut.samsungTvWebsocket, line 293
	if (url == null) { // library marker davegut.samsungTvWebsocket, line 294
		logWarn("connect: invalid function ${funct}") // library marker davegut.samsungTvWebsocket, line 295
		return // library marker davegut.samsungTvWebsocket, line 296
	} // library marker davegut.samsungTvWebsocket, line 297
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 298
	s.phase = "connecting" // library marker davegut.samsungTvWebsocket, line 299
	s.funct = funct // library marker davegut.samsungTvWebsocket, line 300
	s.connectingAt = now() // library marker davegut.samsungTvWebsocket, line 301
	interfaces.webSocket.connect(url, ignoreSSLIssues: true) // library marker davegut.samsungTvWebsocket, line 302
} // library marker davegut.samsungTvWebsocket, line 303
 // library marker davegut.samsungTvWebsocket, line 304
def close() { // library marker davegut.samsungTvWebsocket, line 305
	logDebug("close") // library marker davegut.samsungTvWebsocket, line 306
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 307
	s.phase = "closed" // library marker davegut.samsungTvWebsocket, line 308
	s.queue = [] // library marker davegut.samsungTvWebsocket, line 309
	s.holdAt = null // library marker davegut.samsungTvWebsocket, line 310
	s.closeAfterSend = false // library marker davegut.samsungTvWebsocket, line 311
	s.connectingAt = null // library marker davegut.samsungTvWebsocket, line 312
	interfaces.webSocket.close() // library marker davegut.samsungTvWebsocket, line 313
	sendEvent(name: "wsStatus", value: "closed") // library marker davegut.samsungTvWebsocket, line 314
} // library marker davegut.samsungTvWebsocket, line 315
 // library marker davegut.samsungTvWebsocket, line 316
def webSocketStatus(message) { // library marker davegut.samsungTvWebsocket, line 317
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 318
	Map logData = [method: "webSocketStatus", message: message, funct: s.funct] // library marker davegut.samsungTvWebsocket, line 319
	if (message.startsWith("status: open")) { // library marker davegut.samsungTvWebsocket, line 320
		s.phase = "open" // library marker davegut.samsungTvWebsocket, line 321
		s.connectingAt = null // library marker davegut.samsungTvWebsocket, line 322
		sendEvent(name: "wsStatus", value: "open") // library marker davegut.samsungTvWebsocket, line 323
		//	the TV normally confirms at once with ms.channel.connect // library marker davegut.samsungTvWebsocket, line 324
		runIn(3, wsReadyTimeout) // library marker davegut.samsungTvWebsocket, line 325
	} else if (message.startsWith("status: closing")) { // library marker davegut.samsungTvWebsocket, line 326
		//	a socket being replaced reports closing after the new connect started // library marker davegut.samsungTvWebsocket, line 327
		if (s.phase != "connecting") { // library marker davegut.samsungTvWebsocket, line 328
			s.phase = "closed" // library marker davegut.samsungTvWebsocket, line 329
			s.queue = [] // library marker davegut.samsungTvWebsocket, line 330
			s.holdAt = null // library marker davegut.samsungTvWebsocket, line 331
			s.closeAfterSend = false // library marker davegut.samsungTvWebsocket, line 332
			sendEvent(name: "wsStatus", value: "closed") // library marker davegut.samsungTvWebsocket, line 333
		} // library marker davegut.samsungTvWebsocket, line 334
	} else if (message.startsWith("failure")) { // library marker davegut.samsungTvWebsocket, line 335
		wsFailed() // library marker davegut.samsungTvWebsocket, line 336
		sendEvent(name: "wsStatus", value: "closed-failure") // library marker davegut.samsungTvWebsocket, line 337
	} else { // library marker davegut.samsungTvWebsocket, line 338
		logData << [action: "ignored"] // library marker davegut.samsungTvWebsocket, line 339
	} // library marker davegut.samsungTvWebsocket, line 340
	logDebug(logData) // library marker davegut.samsungTvWebsocket, line 341
} // library marker davegut.samsungTvWebsocket, line 342
 // library marker davegut.samsungTvWebsocket, line 343
def wsFailed() { // library marker davegut.samsungTvWebsocket, line 344
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 345
	//	only a remote-socket failure indicates TV power state // library marker davegut.samsungTvWebsocket, line 346
	if (s.funct == "remote") { // library marker davegut.samsungTvWebsocket, line 347
		state.lastWsFailure = now() // library marker davegut.samsungTvWebsocket, line 348
		if (s.probeAt) { // library marker davegut.samsungTvWebsocket, line 349
			s.probeAt = null // library marker davegut.samsungTvWebsocket, line 350
			probeFailed() // library marker davegut.samsungTvWebsocket, line 351
		} // library marker davegut.samsungTvWebsocket, line 352
	} // library marker davegut.samsungTvWebsocket, line 353
	s.phase = "closed" // library marker davegut.samsungTvWebsocket, line 354
	s.queue = []		//	undeliverable; don't fire stale keys on reconnect // library marker davegut.samsungTvWebsocket, line 355
	s.holdAt = null // library marker davegut.samsungTvWebsocket, line 356
	s.closeAfterSend = false // library marker davegut.samsungTvWebsocket, line 357
	s.connectingAt = null // library marker davegut.samsungTvWebsocket, line 358
	interfaces.webSocket.close() // library marker davegut.samsungTvWebsocket, line 359
} // library marker davegut.samsungTvWebsocket, line 360
 // library marker davegut.samsungTvWebsocket, line 361
def wsReady() { // library marker davegut.samsungTvWebsocket, line 362
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 363
	if (s.phase == "ready") { return } // library marker davegut.samsungTvWebsocket, line 364
	s.phase = "ready" // library marker davegut.samsungTvWebsocket, line 365
	unschedule("wsReadyTimeout") // library marker davegut.samsungTvWebsocket, line 366
	Map logData = [method: "wsReady", funct: s.funct] // library marker davegut.samsungTvWebsocket, line 367
	if (s.funct == "remote") { // library marker davegut.samsungTvWebsocket, line 368
		state.lastWsFailure = null // library marker davegut.samsungTvWebsocket, line 369
		if (s.probeAt) { // library marker davegut.samsungTvWebsocket, line 370
			s.probeAt = null // library marker davegut.samsungTvWebsocket, line 371
			probeOpened() // library marker davegut.samsungTvWebsocket, line 372
		} // library marker davegut.samsungTvWebsocket, line 373
	} // library marker davegut.samsungTvWebsocket, line 374
	if (s.queue) { // library marker davegut.samsungTvWebsocket, line 375
		//	keys queued long ago belong to an earlier attempt; a late KEY_POWER would toggle the TV // library marker davegut.samsungTvWebsocket, line 376
		if (s.queuedAt && now() - s.queuedAt < 15000) { // library marker davegut.samsungTvWebsocket, line 377
			s.queue.each { execMessage(it) } // library marker davegut.samsungTvWebsocket, line 378
			logData << [drained: s.queue.size()] // library marker davegut.samsungTvWebsocket, line 379
		} else { // library marker davegut.samsungTvWebsocket, line 380
			logData << [discarded: s.queue.size()] // library marker davegut.samsungTvWebsocket, line 381
		} // library marker davegut.samsungTvWebsocket, line 382
		s.queue = [] // library marker davegut.samsungTvWebsocket, line 383
	} // library marker davegut.samsungTvWebsocket, line 384
	if (s.holdAt) { // library marker davegut.samsungTvWebsocket, line 385
		boolean fresh = now() - s.holdAt < 15000 // library marker davegut.samsungTvWebsocket, line 386
		s.holdAt = null // library marker davegut.samsungTvWebsocket, line 387
		if (fresh) { // library marker davegut.samsungTvWebsocket, line 388
			logData << [action: "powerHold"] // library marker davegut.samsungTvWebsocket, line 389
			powerHold() // library marker davegut.samsungTvWebsocket, line 390
		} // library marker davegut.samsungTvWebsocket, line 391
	} // library marker davegut.samsungTvWebsocket, line 392
	if (s.closeAfterSend) { // library marker davegut.samsungTvWebsocket, line 393
		s.closeAfterSend = false // library marker davegut.samsungTvWebsocket, line 394
		runIn(2, close) // library marker davegut.samsungTvWebsocket, line 395
	} // library marker davegut.samsungTvWebsocket, line 396
	logDebug(logData) // library marker davegut.samsungTvWebsocket, line 397
} // library marker davegut.samsungTvWebsocket, line 398
 // library marker davegut.samsungTvWebsocket, line 399
def wsReadyTimeout() { // library marker davegut.samsungTvWebsocket, line 400
	Map s = ws() // library marker davegut.samsungTvWebsocket, line 401
	if (s.phase != "open") { return } // library marker davegut.samsungTvWebsocket, line 402
	if (s.probeAt) { // library marker davegut.samsungTvWebsocket, line 403
		//	accepted but never confirmed: the TV is asleep // library marker davegut.samsungTvWebsocket, line 404
		s.probeAt = null // library marker davegut.samsungTvWebsocket, line 405
		probeFailed() // library marker davegut.samsungTvWebsocket, line 406
		close() // library marker davegut.samsungTvWebsocket, line 407
	} else { // library marker davegut.samsungTvWebsocket, line 408
		wsReady() // library marker davegut.samsungTvWebsocket, line 409
	} // library marker davegut.samsungTvWebsocket, line 410
} // library marker davegut.samsungTvWebsocket, line 411
 // library marker davegut.samsungTvWebsocket, line 412
def probeOpened() { setPowerState("on", [method: "probePower", powerState: "absent/wsReady"]) } // library marker davegut.samsungTvWebsocket, line 413
def probeFailed() { setPowerState("off", [method: "probePower", powerState: "absent/wsFailed"]) } // library marker davegut.samsungTvWebsocket, line 414
 // library marker davegut.samsungTvWebsocket, line 415
def parse(resp) { // library marker davegut.samsungTvWebsocket, line 416
	def logData = [method: "parse"] // library marker davegut.samsungTvWebsocket, line 417
	try { // library marker davegut.samsungTvWebsocket, line 418
		resp = parseJson(resp) // library marker davegut.samsungTvWebsocket, line 419
		def event = resp.event // library marker davegut.samsungTvWebsocket, line 420
		logData << [EVENT: event] // library marker davegut.samsungTvWebsocket, line 421
		switch(event) { // library marker davegut.samsungTvWebsocket, line 422
			case "ms.channel.connect": // library marker davegut.samsungTvWebsocket, line 423
				def newToken = resp.data?.token // library marker davegut.samsungTvWebsocket, line 424
				if (newToken != null && newToken != state.token) { // library marker davegut.samsungTvWebsocket, line 425
					state.token = newToken // library marker davegut.samsungTvWebsocket, line 426
					logData << [TOKEN: "updated"] // library marker davegut.samsungTvWebsocket, line 427
				} // library marker davegut.samsungTvWebsocket, line 428
				wsReady() // library marker davegut.samsungTvWebsocket, line 429
				break // library marker davegut.samsungTvWebsocket, line 430
			case "ms.channel.timeOut": // library marker davegut.samsungTvWebsocket, line 431
				//	connected, but the TV did not confirm: it is asleep // library marker davegut.samsungTvWebsocket, line 432
				Map s = ws() // library marker davegut.samsungTvWebsocket, line 433
				if (s.funct == "remote") { state.lastWsFailure = now() } // library marker davegut.samsungTvWebsocket, line 434
				if (s.probeAt) { // library marker davegut.samsungTvWebsocket, line 435
					s.probeAt = null // library marker davegut.samsungTvWebsocket, line 436
					probeFailed() // library marker davegut.samsungTvWebsocket, line 437
				} // library marker davegut.samsungTvWebsocket, line 438
				close() // library marker davegut.samsungTvWebsocket, line 439
				break // library marker davegut.samsungTvWebsocket, line 440
			case "d2d_service_message": // library marker davegut.samsungTvWebsocket, line 441
				def data = parseJson(resp.data) // library marker davegut.samsungTvWebsocket, line 442
				if (data.event == "artmode_status" || // library marker davegut.samsungTvWebsocket, line 443
					data.event == "art_mode_changed") { // library marker davegut.samsungTvWebsocket, line 444
					def status = data.value // library marker davegut.samsungTvWebsocket, line 445
					if (status == null) { status = data.status } // library marker davegut.samsungTvWebsocket, line 446
					sendEvent(name: "artModeStatus", value: status) // library marker davegut.samsungTvWebsocket, line 447
					logData << [artModeStatus: status] // library marker davegut.samsungTvWebsocket, line 448
					state.artModeWs = true // library marker davegut.samsungTvWebsocket, line 449
				} // library marker davegut.samsungTvWebsocket, line 450
				break // library marker davegut.samsungTvWebsocket, line 451
			case "ed.installedApp.get": // library marker davegut.samsungTvWebsocket, line 452
				def payload = resp.data // library marker davegut.samsungTvWebsocket, line 453
				if (payload instanceof String) { payload = parseJson(payload) } // library marker davegut.samsungTvWebsocket, line 454
				def appList = payload?.data // library marker davegut.samsungTvWebsocket, line 455
				logInfo("ed.installedApp.get: [count: ${appList ? appList.size() : 0}, apps: ${appList?.collect { [name: it.name, appId: it.appId] }}]") // library marker davegut.samsungTvWebsocket, line 456
				break // library marker davegut.samsungTvWebsocket, line 457
			case "ms.channel.unauthorized": // library marker davegut.samsungTvWebsocket, line 458
				//	token rejected; reset to the placeholder so the next connect prompts // library marker davegut.samsungTvWebsocket, line 459
				//	the on-screen allow, which returns a fresh token via ms.channel.connect // library marker davegut.samsungTvWebsocket, line 460
				state.token = "12345678" // library marker davegut.samsungTvWebsocket, line 461
				Map s = ws() // library marker davegut.samsungTvWebsocket, line 462
				//	the TV answered, so it is on // library marker davegut.samsungTvWebsocket, line 463
				if (s.probeAt) { // library marker davegut.samsungTvWebsocket, line 464
					s.probeAt = null // library marker davegut.samsungTvWebsocket, line 465
					probeOpened() // library marker davegut.samsungTvWebsocket, line 466
				} // library marker davegut.samsungTvWebsocket, line 467
				logWarn(logData) // library marker davegut.samsungTvWebsocket, line 468
				break // library marker davegut.samsungTvWebsocket, line 469
			case "ms.error": // library marker davegut.samsungTvWebsocket, line 470
			case "ms.channel.ready": // library marker davegut.samsungTvWebsocket, line 471
			case "ms.channel.clientConnect": // library marker davegut.samsungTvWebsocket, line 472
			case "ms.channel.clientDisconnect": // library marker davegut.samsungTvWebsocket, line 473
			case "ms.remote.touchEnable": // library marker davegut.samsungTvWebsocket, line 474
			case "ms.remote.touchDisable": // library marker davegut.samsungTvWebsocket, line 475
				break // library marker davegut.samsungTvWebsocket, line 476
			default: // library marker davegut.samsungTvWebsocket, line 477
				logData << [STATUS: "Not Parsed", DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 478
				break // library marker davegut.samsungTvWebsocket, line 479
		} // library marker davegut.samsungTvWebsocket, line 480
		logDebug(logData) // library marker davegut.samsungTvWebsocket, line 481
	} catch (e) { // library marker davegut.samsungTvWebsocket, line 482
		logData << [STATUS: "unhandled", ERROR: e] // library marker davegut.samsungTvWebsocket, line 483
		logWarn(logData) // library marker davegut.samsungTvWebsocket, line 484
	} // library marker davegut.samsungTvWebsocket, line 485
} // library marker davegut.samsungTvWebsocket, line 486
// ~~~~~ end include (1) davegut.samsungTvWebsocket ~~~~~
// ~~~~~ start include (2) davegut.samsungTvApps ~~~~~
library ( // library marker davegut.samsungTvApps, line 1
	name: "samsungTvApps", // library marker davegut.samsungTvApps, line 2
	namespace: "davegut", // library marker davegut.samsungTvApps, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvApps, line 4
	description: "Samsung TV Applications", // library marker davegut.samsungTvApps, line 5
	category: "utilities", // library marker davegut.samsungTvApps, line 6
	documentationLink: "" // library marker davegut.samsungTvApps, line 7
) // library marker davegut.samsungTvApps, line 8
//	Modified by hubitrep in 2026. Changes: https://github.com/hubitrep/SamsungTvRemote#changes // library marker davegut.samsungTvApps, line 9
 // library marker davegut.samsungTvApps, line 10
import groovy.json.JsonSlurper // library marker davegut.samsungTvApps, line 11
 // library marker davegut.samsungTvApps, line 12
command "appOpenByName", ["string"] // library marker davegut.samsungTvApps, line 13
command "appClose" // library marker davegut.samsungTvApps, line 14
attribute "nowPlaying", "string" // library marker davegut.samsungTvApps, line 15
attribute "appName", "string" // library marker davegut.samsungTvApps, line 16
attribute "appId", "string" // library marker davegut.samsungTvApps, line 17
attribute "tvChannel", "string" // library marker davegut.samsungTvApps, line 18
attribute "tvChannelName", "string" // library marker davegut.samsungTvApps, line 19
 // library marker davegut.samsungTvApps, line 20
def tvAppsPreferences() { // library marker davegut.samsungTvApps, line 21
	input ("findAppCodes", "enum", title: "Scan for App Codes (takes 10 minutes)",  // library marker davegut.samsungTvApps, line 22
		   options: ["off", "startOver", "find"], defaultValue: "off") // library marker davegut.samsungTvApps, line 23
} // library marker davegut.samsungTvApps, line 24
 // library marker davegut.samsungTvApps, line 25
def appOpenByName(appName) { // library marker davegut.samsungTvApps, line 26
	def logData = [method: "appOpenByName"] // library marker davegut.samsungTvApps, line 27
	if (!appName || !state.appData) { // library marker davegut.samsungTvApps, line 28
		logWarn([method: "appOpenByName", error: "no app name or no app list"]) // library marker davegut.samsungTvApps, line 29
		return // library marker davegut.samsungTvApps, line 30
	} // library marker davegut.samsungTvApps, line 31
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) } // library marker davegut.samsungTvApps, line 32
	if (thisApp != null) { // library marker davegut.samsungTvApps, line 33
		def appId = thisApp.value // library marker davegut.samsungTvApps, line 34
		appName = thisApp.key // library marker davegut.samsungTvApps, line 35
		logData << [appName: appName, appId: appId] // library marker davegut.samsungTvApps, line 36
		def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}" // library marker davegut.samsungTvApps, line 37
		try { // library marker davegut.samsungTvApps, line 38
			httpPost(uri, "") { resp -> // library marker davegut.samsungTvApps, line 39
				logData << [status: resp.statusLine, data: resp.data, success: resp.success] // library marker davegut.samsungTvApps, line 40
				if (resp.status == 200) { // library marker davegut.samsungTvApps, line 41
					if (stConnected()) { runIn(10, deviceRefresh) } // library marker davegut.samsungTvApps, line 42
					sendEvent(name: "appName", value: appName) // library marker davegut.samsungTvApps, line 43
					sendEvent(name: "appId", value: appId) // library marker davegut.samsungTvApps, line 44
					logDebug(logData) // library marker davegut.samsungTvApps, line 45
				} else { // library marker davegut.samsungTvApps, line 46
					logWarn(logData) // library marker davegut.samsungTvApps, line 47
				} // library marker davegut.samsungTvApps, line 48
			} // library marker davegut.samsungTvApps, line 49
			logDebug(logData) // library marker davegut.samsungTvApps, line 50
		} catch (err) { // library marker davegut.samsungTvApps, line 51
			logData << [status: "httpPost error", data: err] // library marker davegut.samsungTvApps, line 52
			logWarn(logData) // library marker davegut.samsungTvApps, line 53
		} // library marker davegut.samsungTvApps, line 54
	} else { // library marker davegut.samsungTvApps, line 55
		logData << [error: "appId is null"] // library marker davegut.samsungTvApps, line 56
		logWarn(logData) // library marker davegut.samsungTvApps, line 57
	} // library marker davegut.samsungTvApps, line 58
} // library marker davegut.samsungTvApps, line 59
 // library marker davegut.samsungTvApps, line 60
def appClose(appId = device.currentValue("appId")) { // library marker davegut.samsungTvApps, line 61
	def logData = [method: "appClose", appId: appId] // library marker davegut.samsungTvApps, line 62
	if (appId == null || appId == " ") { // library marker davegut.samsungTvApps, line 63
		logData << [status: "appId is null", action: "try exit()"] // library marker davegut.samsungTvApps, line 64
		sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvApps, line 65
		sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvApps, line 66
		if (stConnected()) { runIn(5, deviceRefresh) } // library marker davegut.samsungTvApps, line 67
	} else { // library marker davegut.samsungTvApps, line 68
		logData << [status: "sending appClose"] // library marker davegut.samsungTvApps, line 69
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 70
					  timeout: 3] // library marker davegut.samsungTvApps, line 71
		asynchttpDelete("appCloseParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 72
	} // library marker davegut.samsungTvApps, line 73
	logDebug(logData) // library marker davegut.samsungTvApps, line 74
} // library marker davegut.samsungTvApps, line 75
 // library marker davegut.samsungTvApps, line 76
def appCloseParse(resp, data) { // library marker davegut.samsungTvApps, line 77
	Map logData = [method: "appCloseParse", data: data] // library marker davegut.samsungTvApps, line 78
	if (resp.status == 200 && resp.json == true) { // library marker davegut.samsungTvApps, line 79
		logData << [status: resp.status, success: resp.json] // library marker davegut.samsungTvApps, line 80
		logDebug(logData) // library marker davegut.samsungTvApps, line 81
	} else { // library marker davegut.samsungTvApps, line 82
		logData << [status: resp.status, success: "false", action: "exit()"] // library marker davegut.samsungTvApps, line 83
		exit() // library marker davegut.samsungTvApps, line 84
		exit() // library marker davegut.samsungTvApps, line 85
		logWarn(logData) // library marker davegut.samsungTvApps, line 86
	} // library marker davegut.samsungTvApps, line 87
	if (stConnected()) { runIn(5, deviceRefresh) } // library marker davegut.samsungTvApps, line 88
	sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvApps, line 89
	sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvApps, line 90
} // library marker davegut.samsungTvApps, line 91
 // library marker davegut.samsungTvApps, line 92
def updateAppCodes() { // library marker davegut.samsungTvApps, line 93
	Map logData = [method: "updateAppCodes", findAppCodes: findAppCodes] // library marker davegut.samsungTvApps, line 94
	def scan = findAppCodes in ["startOver", "find"] // library marker davegut.samsungTvApps, line 95
	if (scan && device.currentValue("switch") == "on") { // library marker davegut.samsungTvApps, line 96
		device.updateSetting("findAppCodes", [type:"enum", value: "off"]) // library marker davegut.samsungTvApps, line 97
		if (findAppCodes == "startOver") {  // library marker davegut.samsungTvApps, line 98
			state.appData = [:] // library marker davegut.samsungTvApps, line 99
			logData << [appData: "reset"] // library marker davegut.samsungTvApps, line 100
		} // library marker davegut.samsungTvApps, line 101
		state.appsInstalled = [] // library marker davegut.samsungTvApps, line 102
		def appIds = appIdList() // library marker davegut.samsungTvApps, line 103
		def appId = 0 // library marker davegut.samsungTvApps, line 104
		logData << [codesToCheck: appIds.size(), status: "OK"] // library marker davegut.samsungTvApps, line 105
		runIn(5, getAppData) // library marker davegut.samsungTvApps, line 106
		logInfo(logData) // library marker davegut.samsungTvApps, line 107
	} else if (scan) { // library marker davegut.samsungTvApps, line 108
		logData << [status: "FAILED", reason: "tv off"] // library marker davegut.samsungTvApps, line 109
		logWarn(logData) // library marker davegut.samsungTvApps, line 110
	} // library marker davegut.samsungTvApps, line 111
	return logData // library marker davegut.samsungTvApps, line 112
} // library marker davegut.samsungTvApps, line 113
 // library marker davegut.samsungTvApps, line 114
def getAppData(appId = 0) { // library marker davegut.samsungTvApps, line 115
	Map logData = [method: "getAppData", appId: appId] // library marker davegut.samsungTvApps, line 116
	def appIds = appIdList() // library marker davegut.samsungTvApps, line 117
	if (appId < appIds.size()) { // library marker davegut.samsungTvApps, line 118
		def appCode = appIds[appId] // library marker davegut.samsungTvApps, line 119
		logData << [appCode: appCode] // library marker davegut.samsungTvApps, line 120
		def thisDevice = state.appData.find { it.value == appCode } // library marker davegut.samsungTvApps, line 121
		if (thisDevice != null) { // library marker davegut.samsungTvApps, line 122
			logData << [thisDevice: thisDevice, status: "Already in appData"] // library marker davegut.samsungTvApps, line 123
			appId = appId + 1 // library marker davegut.samsungTvApps, line 124
			runInMillis(100, getAppData, [data: appId]) // library marker davegut.samsungTvApps, line 125
		} else { // library marker davegut.samsungTvApps, line 126
			logData << [status: "looking for App"] // library marker davegut.samsungTvApps, line 127
			Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appCode}", // library marker davegut.samsungTvApps, line 128
						  timeout: 10] // library marker davegut.samsungTvApps, line 129
			asynchttpGet("parseGetAppData", params, [appId:appId, appCode: appCode]) // library marker davegut.samsungTvApps, line 130
		} // library marker davegut.samsungTvApps, line 131
		logDebug(logData) // library marker davegut.samsungTvApps, line 132
	} else { // library marker davegut.samsungTvApps, line 133
		logData << [status: "Done finding", totalApps: state.appData.size(), appsInstalled: state.appsInstalled] // library marker davegut.samsungTvApps, line 134
		state.remove("appsInstalled") // library marker davegut.samsungTvApps, line 135
		state.remove("retry") // library marker davegut.samsungTvApps, line 136
		logInfo(logData) // library marker davegut.samsungTvApps, line 137
	} // library marker davegut.samsungTvApps, line 138
} // library marker davegut.samsungTvApps, line 139
 // library marker davegut.samsungTvApps, line 140
def parseGetAppData(resp, data) { // library marker davegut.samsungTvApps, line 141
	Map logData = [method: "parseGetAppData", data: data, status: resp.status] // library marker davegut.samsungTvApps, line 142
	def respData = null // library marker davegut.samsungTvApps, line 143
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 144
		try { respData = new JsonSlurper().parseText(resp.data) } catch (e) { } // library marker davegut.samsungTvApps, line 145
	} // library marker davegut.samsungTvApps, line 146
	if (respData?.name && respData?.id) { // library marker davegut.samsungTvApps, line 147
		String name = shortenName(respData.name) // library marker davegut.samsungTvApps, line 148
		logData << [name: name, status: "appAdded"] // library marker davegut.samsungTvApps, line 149
		state.appData << ["${name}": respData.id] // library marker davegut.samsungTvApps, line 150
		state.appsInstalled << name // library marker davegut.samsungTvApps, line 151
		logDebug(logData) // library marker davegut.samsungTvApps, line 152
		state.retry = false // library marker davegut.samsungTvApps, line 153
		runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 154
	} else if (resp.status == 404) { // library marker davegut.samsungTvApps, line 155
		logData << [status: "appNotAdded", reason: "not installed in TV"] // library marker davegut.samsungTvApps, line 156
		logDebug(logData) // library marker davegut.samsungTvApps, line 157
		state.retry = false // library marker davegut.samsungTvApps, line 158
		runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 159
	} else { // library marker davegut.samsungTvApps, line 160
		logData << [retry: state.retry, status: "appNotAdded", // library marker davegut.samsungTvApps, line 161
					reason: "invalid response from device"] // library marker davegut.samsungTvApps, line 162
		if (!state.retry) { // library marker davegut.samsungTvApps, line 163
			logData << [action: "<b>RETRYING</b>"] // library marker davegut.samsungTvApps, line 164
			state.retry = true // library marker davegut.samsungTvApps, line 165
			runIn(5, getAppData, [data: data.appId]) // library marker davegut.samsungTvApps, line 166
		} else { // library marker davegut.samsungTvApps, line 167
			runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 168
		} // library marker davegut.samsungTvApps, line 169
		logWarn(logData) // library marker davegut.samsungTvApps, line 170
	} // library marker davegut.samsungTvApps, line 171
} // library marker davegut.samsungTvApps, line 172
 // library marker davegut.samsungTvApps, line 173
def shortenName(name) { // library marker davegut.samsungTvApps, line 174
	if (name == null) { return " " } // library marker davegut.samsungTvApps, line 175
	if (name.contains(" - ")) { // library marker davegut.samsungTvApps, line 176
		name = name.substring(0, name.indexOf(" - ")) // library marker davegut.samsungTvApps, line 177
	} else if (name.contains(" by ")) { // library marker davegut.samsungTvApps, line 178
		name = name.substring(0, name.indexOf(" by ")) // library marker davegut.samsungTvApps, line 179
	} else if (name.contains(": ")) { // library marker davegut.samsungTvApps, line 180
		name = name.substring(0, name.indexOf(": ")) // library marker davegut.samsungTvApps, line 181
	} else if (name.contains(" | ")) { // library marker davegut.samsungTvApps, line 182
		name = name.substring(0, name.indexOf(" | ")) // library marker davegut.samsungTvApps, line 183
	} // library marker davegut.samsungTvApps, line 184
	return name // library marker davegut.samsungTvApps, line 185
} // library marker davegut.samsungTvApps, line 186
 // library marker davegut.samsungTvApps, line 187
def appIdList() { // library marker davegut.samsungTvApps, line 188
	def appList = [ // library marker davegut.samsungTvApps, line 189
		"Nuvyyo0002.tablo", "5b8c3eb16b.BeamCTVDev", "kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby",  // library marker davegut.samsungTvApps, line 190
		"ZmmGjO6VKO.slingtv", "PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu",  // library marker davegut.samsungTvApps, line 191
		"AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV", "cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock",  // library marker davegut.samsungTvApps, line 192
		"9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream", "3201907018807", "3201910019365",  // library marker davegut.samsungTvApps, line 193
		"3201907018784", "kIciSQlYEM.plex", "ckfgqqzvt0.dplus", "H7DIeAitkn.DisneyNOW", // library marker davegut.samsungTvApps, line 194
		"MCmYXNxgcu.DisneyPlus", "tCyZuSsCVw.Britbox", "tzo5Zi4mCPv.fuboTV", "3HYANqBDJD.DFW", // library marker davegut.samsungTvApps, line 195
		"EYm8vc1St4.Philo", "N4St7cQBPD.SiriusXM", "sNUyBbfvHf.SpectrumTV", "rJeHak5zRg.Spotify", // library marker davegut.samsungTvApps, line 196
		"3KA0pm7a7V.TubiTV", "r1mzFxGfYe.E","3201606009684", "3201910019365", "3201807016597",  // library marker davegut.samsungTvApps, line 197
		"3201601007625", "3201710015037", "3201908019041", "3201504001965", "3201907018784",  // library marker davegut.samsungTvApps, line 198
		"org.tizen.browser", "org.tizen.primevideo", "org.tizen.netflix-app",  // library marker davegut.samsungTvApps, line 199
		"com.samsung.tv.aria-video", "com.samsung.tv.gallery", "org.tizen.apple.apple-music", // library marker davegut.samsungTvApps, line 200
		"com.samsung.tv.store", // library marker davegut.samsungTvApps, line 201
		 // library marker davegut.samsungTvApps, line 202
		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421", // library marker davegut.samsungTvApps, line 203
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577", // library marker davegut.samsungTvApps, line 204
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626", // library marker davegut.samsungTvApps, line 205
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378", // library marker davegut.samsungTvApps, line 206
		"3201910019354", "3201909019271", "3201909019175", "3201908019041", "3201908019022",  // library marker davegut.samsungTvApps, line 207
		"3201907018786", "3201906018693", // library marker davegut.samsungTvApps, line 208
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074", // library marker davegut.samsungTvApps, line 209
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367", // library marker davegut.samsungTvApps, line 210
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067", // library marker davegut.samsungTvApps, line 211
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489", // library marker davegut.samsungTvApps, line 212
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079", // library marker davegut.samsungTvApps, line 213
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210", // library marker davegut.samsungTvApps, line 214
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031", // library marker davegut.samsungTvApps, line 215
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746", // library marker davegut.samsungTvApps, line 216
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230", // library marker davegut.samsungTvApps, line 217
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488", // library marker davegut.samsungTvApps, line 218
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101", // library marker davegut.samsungTvApps, line 219
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148", // library marker davegut.samsungTvApps, line 220
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407", // library marker davegut.samsungTvApps, line 221
		"11091000000" // library marker davegut.samsungTvApps, line 222
	] // library marker davegut.samsungTvApps, line 223
	return appList.unique() // library marker davegut.samsungTvApps, line 224
} // library marker davegut.samsungTvApps, line 225
 // library marker davegut.samsungTvApps, line 226
def updateAppName(tvName = device.currentValue("tvChannelName")) { // library marker davegut.samsungTvApps, line 227
	//	If the tvChannel is blank, the the name may reflect the appId // library marker davegut.samsungTvApps, line 228
	//	that is used by the device.  Thanks SmartThings. // library marker davegut.samsungTvApps, line 229
	String appId = " " // library marker davegut.samsungTvApps, line 230
	String appName = " " // library marker davegut.samsungTvApps, line 231
	Map logData = [method: "updateAppName", tvName: tvName] // library marker davegut.samsungTvApps, line 232
	//	There are some names that need translation based on known // library marker davegut.samsungTvApps, line 233
	//	idiosyncracies with the SmartThings implementation. // library marker davegut.samsungTvApps, line 234
	//	Go to translation table and if the translation exists, // library marker davegut.samsungTvApps, line 235
	//	set the appName to that value. // library marker davegut.samsungTvApps, line 236
	def tempName = transTable().find { it.key == tvName } // library marker davegut.samsungTvApps, line 237
	if (tempName != null) {  // library marker davegut.samsungTvApps, line 238
		appName = tempName.value // library marker davegut.samsungTvApps, line 239
		logData << [tempName: tempName] // library marker davegut.samsungTvApps, line 240
	} // library marker davegut.samsungTvApps, line 241
	//	See if the name is in the app list.  If so, update here // library marker davegut.samsungTvApps, line 242
	//	and in states. // library marker davegut.samsungTvApps, line 243
	def thisApp = state.appData.find { it.key == appName } // library marker davegut.samsungTvApps, line 244
	if (thisApp) { // library marker davegut.samsungTvApps, line 245
		logData << [thisApp: thisApp] // library marker davegut.samsungTvApps, line 246
		appId = thisApp.value // library marker davegut.samsungTvApps, line 247
	} else { // library marker davegut.samsungTvApps, line 248
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${tvName}", // library marker davegut.samsungTvApps, line 249
				  	timeout: 10] // library marker davegut.samsungTvApps, line 250
		try { // library marker davegut.samsungTvApps, line 251
			httpGet(params) { resp -> // library marker davegut.samsungTvApps, line 252
				appId = resp.data.id // library marker davegut.samsungTvApps, line 253
				appName = shortenName(resp.data.name) // library marker davegut.samsungTvApps, line 254
				logData << [appId: appId, appName: appName] // library marker davegut.samsungTvApps, line 255
			} // library marker davegut.samsungTvApps, line 256
		}catch (err) { // library marker davegut.samsungTvApps, line 257
			logData << [error: err] // library marker davegut.samsungTvApps, line 258
		} // library marker davegut.samsungTvApps, line 259
		if (appId?.trim() && appName?.trim()) { // library marker davegut.samsungTvApps, line 260
			state.appData << ["${appName}": appId] // library marker davegut.samsungTvApps, line 261
			logData << [appData: ["${appName}": appId]] // library marker davegut.samsungTvApps, line 262
		} // library marker davegut.samsungTvApps, line 263
	} // library marker davegut.samsungTvApps, line 264
	sendEvent(name: "appName", value: appName) // library marker davegut.samsungTvApps, line 265
	sendEvent(name: "appId", value: appId) // library marker davegut.samsungTvApps, line 266
	logDebug(logData) // library marker davegut.samsungTvApps, line 267
} // library marker davegut.samsungTvApps, line 268
 // library marker davegut.samsungTvApps, line 269
def updateTitle() { // library marker davegut.samsungTvApps, line 270
	String tvChannel = device.currentValue("tvChannel") // library marker davegut.samsungTvApps, line 271
	String title = "${tvChannel}: ${device.currentValue("tvChannelName")}" // library marker davegut.samsungTvApps, line 272
	if (tvChannel == " ") { // library marker davegut.samsungTvApps, line 273
		title = "app: ${device.currentValue("appName")}" // library marker davegut.samsungTvApps, line 274
	} // library marker davegut.samsungTvApps, line 275
	sendEvent(name: "nowPlaying", value: title) // library marker davegut.samsungTvApps, line 276
} // library marker davegut.samsungTvApps, line 277
 // library marker davegut.samsungTvApps, line 278
def transTable() { // library marker davegut.samsungTvApps, line 279
	def translations = [ // library marker davegut.samsungTvApps, line 280
		"org.tizen.primevideo": "Prime Video", // library marker davegut.samsungTvApps, line 281
		"org.tizen.netflix-app": "Netflix", // library marker davegut.samsungTvApps, line 282
		"org.tizen.browser": "Internet", // library marker davegut.samsungTvApps, line 283
		"com.samsung.tv.aria-video": "Apple TV", // library marker davegut.samsungTvApps, line 284
		"com.samsung.tv.gallery": "Gallery", // library marker davegut.samsungTvApps, line 285
		"org.tizen.apple.apple-music": "Apple Music" // library marker davegut.samsungTvApps, line 286
		] // library marker davegut.samsungTvApps, line 287
	return translations // library marker davegut.samsungTvApps, line 288
} // library marker davegut.samsungTvApps, line 289
// ~~~~~ end include (2) davegut.samsungTvApps ~~~~~
// ~~~~~ start include (3) davegut.samsungTvPresets ~~~~~
library ( // library marker davegut.samsungTvPresets, line 1
	name: "samsungTvPresets", // library marker davegut.samsungTvPresets, line 2
	namespace: "davegut", // library marker davegut.samsungTvPresets, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvPresets, line 4
	description: "Samsung TV Preset Implementation", // library marker davegut.samsungTvPresets, line 5
	category: "utilities", // library marker davegut.samsungTvPresets, line 6
	documentationLink: "" // library marker davegut.samsungTvPresets, line 7
) // library marker davegut.samsungTvPresets, line 8
//	Modified by hubitrep in 2026. Changes: https://github.com/hubitrep/SamsungTvRemote#changes // library marker davegut.samsungTvPresets, line 9
 // library marker davegut.samsungTvPresets, line 10
command "presetUpdateNext" // library marker davegut.samsungTvPresets, line 11
command "presetCreate", [ // library marker davegut.samsungTvPresets, line 12
	[name: "Preset Number", type: "ENUM",  // library marker davegut.samsungTvPresets, line 13
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]] // library marker davegut.samsungTvPresets, line 14
command "presetExecute", [ // library marker davegut.samsungTvPresets, line 15
	[name: "Preset Number", type: "ENUM", // library marker davegut.samsungTvPresets, line 16
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]] // library marker davegut.samsungTvPresets, line 17
command "presetCreateTv", [ // library marker davegut.samsungTvPresets, line 18
	[name: "Preset Number", type: "ENUM", // library marker davegut.samsungTvPresets, line 19
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]], // library marker davegut.samsungTvPresets, line 20
	[name: "tvChannel", type: "STRING"], // library marker davegut.samsungTvPresets, line 21
	[name: "tvChannelName", type: "STRING"]] // library marker davegut.samsungTvPresets, line 22
attribute "presetUpdateNext", "string" // library marker davegut.samsungTvPresets, line 23
 // library marker davegut.samsungTvPresets, line 24
def presetUpdateNext() { // library marker davegut.samsungTvPresets, line 25
	//	Sets up next presetExecute to update the preset selected // library marker davegut.samsungTvPresets, line 26
	//	Has a 10 second timer to select preset to reset.  Will then // library marker davegut.samsungTvPresets, line 27
	//	revert back to false // library marker davegut.samsungTvPresets, line 28
	sendEvent(name: "presetUpdateNext", value: "true") // library marker davegut.samsungTvPresets, line 29
	runIn(5, undoUpdate) // library marker davegut.samsungTvPresets, line 30
} // library marker davegut.samsungTvPresets, line 31
def undoUpdate() { sendEvent(name: "presetUpdateNext", value: "false") } // library marker davegut.samsungTvPresets, line 32
 // library marker davegut.samsungTvPresets, line 33
def presetCreate(presetNumber) { // library marker davegut.samsungTvPresets, line 34
	//	Called from Hubitat Device's page for TV or from presetExecute // library marker davegut.samsungTvPresets, line 35
	//	when state.updateNextPreset is true // library marker davegut.samsungTvPresets, line 36
	if (stConnected()) { // library marker davegut.samsungTvPresets, line 37
		refresh() // library marker davegut.samsungTvPresets, line 38
		runIn(3, presetCreateRead, [data: [presetNumber: presetNumber]])		//	after the status reply // library marker davegut.samsungTvPresets, line 39
	} else { // library marker davegut.samsungTvPresets, line 40
		presetCreateRead([presetNumber: presetNumber]) // library marker davegut.samsungTvPresets, line 41
	} // library marker davegut.samsungTvPresets, line 42
} // library marker davegut.samsungTvPresets, line 43
 // library marker davegut.samsungTvPresets, line 44
def presetCreateRead(data) { // library marker davegut.samsungTvPresets, line 45
	def presetNumber = data.presetNumber // library marker davegut.samsungTvPresets, line 46
	Map logData = [method: "presetCreate", presetNumber: presetNumber] // library marker davegut.samsungTvPresets, line 47
	String appName = device.currentValue("appName") // library marker davegut.samsungTvPresets, line 48
	String tvChannel = device.currentValue("tvChannel") // library marker davegut.samsungTvPresets, line 49
	if (appName != " ") { // library marker davegut.samsungTvPresets, line 50
		String appId = device.currentValue("appId") // library marker davegut.samsungTvPresets, line 51
		presetCreateApp(presetNumber, appName, appId) // library marker davegut.samsungTvPresets, line 52
		logData << [action: "appPresetCreate"] // library marker davegut.samsungTvPresets, line 53
	} else if (tvChannel != " ") { // library marker davegut.samsungTvPresets, line 54
		String tvChannelName = device.currentValue("tvChannelName") // library marker davegut.samsungTvPresets, line 55
		presetCreateTv(presetNumber, tvChannel, tvChannelName) // library marker davegut.samsungTvPresets, line 56
		logData << [action: "tvPresetCreate"] // library marker davegut.samsungTvPresets, line 57
	} // library marker davegut.samsungTvPresets, line 58
	logInfo(logData) // library marker davegut.samsungTvPresets, line 59
} // library marker davegut.samsungTvPresets, line 60
 // library marker davegut.samsungTvPresets, line 61
def presetCreateApp(presetNumber, appName, appId) { // library marker davegut.samsungTvPresets, line 62
	Map logData = [method: "appPresetCreate", presetNumber: presetNumber, // library marker davegut.samsungTvPresets, line 63
				   appName: appName, appId: appId] // library marker davegut.samsungTvPresets, line 64
	Map thisPresetData = [type: "application", execute: appName, appId: appId] // library marker davegut.samsungTvPresets, line 65
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"] // library marker davegut.samsungTvPresets, line 66
	presetDataUpdate(presetNumber, thisPresetData) // library marker davegut.samsungTvPresets, line 67
	logInfo(logData) // library marker davegut.samsungTvPresets, line 68
} // library marker davegut.samsungTvPresets, line 69
 // library marker davegut.samsungTvPresets, line 70
def presetCreateTv(presetNumber, tvChannel, tvChannelName) { // library marker davegut.samsungTvPresets, line 71
	Map logData = [method: "resetCreateTv", presetNumber: presetNumber,  // library marker davegut.samsungTvPresets, line 72
				   tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTvPresets, line 73
	Map thisPresetData = [type: "tvChannel", execute: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTvPresets, line 74
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"] // library marker davegut.samsungTvPresets, line 75
	presetDataUpdate(presetNumber, thisPresetData) // library marker davegut.samsungTvPresets, line 76
	logInfo(logData) // library marker davegut.samsungTvPresets, line 77
} // library marker davegut.samsungTvPresets, line 78
 // library marker davegut.samsungTvPresets, line 79
def presetDataUpdate(presetNumber, thisPresetData) { // library marker davegut.samsungTvPresets, line 80
	Map presetData = state.presetData // library marker davegut.samsungTvPresets, line 81
	state.remove("presetData") // library marker davegut.samsungTvPresets, line 82
	if (presetData == null) { presetData = [:] } // library marker davegut.samsungTvPresets, line 83
	if (presetData.find{it.key == presetNumber}) { // library marker davegut.samsungTvPresets, line 84
		presetData.remove(presetNumber) // library marker davegut.samsungTvPresets, line 85
	} // library marker davegut.samsungTvPresets, line 86
	presetData << ["${presetNumber}": thisPresetData] // library marker davegut.samsungTvPresets, line 87
	state.presetData = presetData // library marker davegut.samsungTvPresets, line 88
} // library marker davegut.samsungTvPresets, line 89
 // library marker davegut.samsungTvPresets, line 90
def presetChannelSet(data) { // library marker davegut.samsungTvPresets, line 91
	channelSet(data.tvChannel) // library marker davegut.samsungTvPresets, line 92
	sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvPresets, line 93
	sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvPresets, line 94
	sendEvent(name: "tvChannel", value: data.tvChannel) // library marker davegut.samsungTvPresets, line 95
	sendEvent(name: "tvChannelName", value: data.tvChannelName) // library marker davegut.samsungTvPresets, line 96
} // library marker davegut.samsungTvPresets, line 97
 // library marker davegut.samsungTvPresets, line 98
def presetExecute(presetNumber) { // library marker davegut.samsungTvPresets, line 99
	Map logData = [method: "presetExecute", presetNumber: presetNumber] // library marker davegut.samsungTvPresets, line 100
	if (device.currentValue("presetUpdateNext") == "true") { // library marker davegut.samsungTvPresets, line 101
		sendEvent(name: "presetUpdateNext", value: "false") // library marker davegut.samsungTvPresets, line 102
		logData << [action: "presetCreate"] // library marker davegut.samsungTvPresets, line 103
		presetCreate(presetNumber) // library marker davegut.samsungTvPresets, line 104
	} else { // library marker davegut.samsungTvPresets, line 105
		if (state.presetData == null) { state.presetData = [:] } // library marker davegut.samsungTvPresets, line 106
		def thisPreset = state.presetData.find { it.key == presetNumber } // library marker davegut.samsungTvPresets, line 107
		if (thisPreset == null) { // library marker davegut.samsungTvPresets, line 108
			logData << [error: "presetNotSet"] // library marker davegut.samsungTvPresets, line 109
			logWarn(logData) // library marker davegut.samsungTvPresets, line 110
		} else { // library marker davegut.samsungTvPresets, line 111
			def execute = thisPreset.value.execute // library marker davegut.samsungTvPresets, line 112
			def presetType = thisPreset.value.type // library marker davegut.samsungTvPresets, line 113
			if (presetType == "application") { // library marker davegut.samsungTvPresets, line 114
				//	Simply open the app. // library marker davegut.samsungTvPresets, line 115
				appOpenByName(execute) // library marker davegut.samsungTvPresets, line 116
				sendEvent(name: "appId", value: thisPreset.value.appId) // library marker davegut.samsungTvPresets, line 117
				sendEvent(name: "appName", value: execute) // library marker davegut.samsungTvPresets, line 118
				sendEvent(name: "tvChannel", value: " ") // library marker davegut.samsungTvPresets, line 119
				sendEvent(name: "tvChannelName", value: " ") // library marker davegut.samsungTvPresets, line 120
				logData << [appName: execute, appId: thisPreset.value.appId] // library marker davegut.samsungTvPresets, line 121
			} else if (presetType == "tvChannel") { // library marker davegut.samsungTvPresets, line 122
				//	Close running app the update channel // library marker davegut.samsungTvPresets, line 123
				Map chData = [tvChannel: execute, tvChannelName: thisPreset.value.tvChannelName] // library marker davegut.samsungTvPresets, line 124
				if (!stConnected() && device.currentValue("appId") != " ") { // library marker davegut.samsungTvPresets, line 125
					appClose() // library marker davegut.samsungTvPresets, line 126
					runIn(7, presetChannelSet, [data: chData])		//	after the app closes // library marker davegut.samsungTvPresets, line 127
				} else { // library marker davegut.samsungTvPresets, line 128
					presetChannelSet(chData) // library marker davegut.samsungTvPresets, line 129
				} // library marker davegut.samsungTvPresets, line 130
				logData << chData // library marker davegut.samsungTvPresets, line 131
			} else { // library marker davegut.samsungTvPresets, line 132
				logData << [error: "invalid preset type"] // library marker davegut.samsungTvPresets, line 133
				logWarn(logData) // library marker davegut.samsungTvPresets, line 134
			} // library marker davegut.samsungTvPresets, line 135
		} // library marker davegut.samsungTvPresets, line 136
		runIn(2, updateTitle) // library marker davegut.samsungTvPresets, line 137
	} // library marker davegut.samsungTvPresets, line 138
	logDebug(logData) // library marker davegut.samsungTvPresets, line 139
} // library marker davegut.samsungTvPresets, line 140
 // library marker davegut.samsungTvPresets, line 141
// ~~~~~ end include (3) davegut.samsungTvPresets ~~~~~
// ~~~~~ start include (4) davegut.SmartThingsInterface ~~~~~
library ( // library marker davegut.SmartThingsInterface, line 1
	name: "SmartThingsInterface", // library marker davegut.SmartThingsInterface, line 2
	namespace: "davegut", // library marker davegut.SmartThingsInterface, line 3
	author: "Dave Gutheinz", // library marker davegut.SmartThingsInterface, line 4
	description: "Samsung TV SmartThings Capabilities", // library marker davegut.SmartThingsInterface, line 5
	category: "utilities", // library marker davegut.SmartThingsInterface, line 6
	documentationLink: "" // library marker davegut.SmartThingsInterface, line 7
) // library marker davegut.SmartThingsInterface, line 8
 // library marker davegut.SmartThingsInterface, line 9
def stPreferences() { // library marker davegut.SmartThingsInterface, line 10
	input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false) // library marker davegut.SmartThingsInterface, line 11
	if (connectST) { // library marker davegut.SmartThingsInterface, line 12
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "") // library marker davegut.SmartThingsInterface, line 13
		if (stApiKey) { // library marker davegut.SmartThingsInterface, line 14
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "") // library marker davegut.SmartThingsInterface, line 15
		} // library marker davegut.SmartThingsInterface, line 16
		input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)", // library marker davegut.SmartThingsInterface, line 17
			   options: ["off", "1", "5", "15", "30"], defaultValue: "15") // library marker davegut.SmartThingsInterface, line 18
		input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false) // library marker davegut.SmartThingsInterface, line 19
	} // library marker davegut.SmartThingsInterface, line 20
} // library marker davegut.SmartThingsInterface, line 21
 // library marker davegut.SmartThingsInterface, line 22
//	usable SmartThings link: enabled, configured, and the key not rejected // library marker davegut.SmartThingsInterface, line 23
def stConnected() { // library marker davegut.SmartThingsInterface, line 24
	return connectST && stApiKey?.trim() && stDeviceId?.trim() && !state.stAuthFailed // library marker davegut.SmartThingsInterface, line 25
} // library marker davegut.SmartThingsInterface, line 26
 // library marker davegut.SmartThingsInterface, line 27
//	SmartThings personal access tokens created after 30 Dec 2024 expire after 24 hours // library marker davegut.SmartThingsInterface, line 28
def stAuthFailed() { // library marker davegut.SmartThingsInterface, line 29
	if (!state.stAuthFailed) { // library marker davegut.SmartThingsInterface, line 30
		state.stAuthFailed = true // library marker davegut.SmartThingsInterface, line 31
		unschedule("refresh") // library marker davegut.SmartThingsInterface, line 32
		logError("SmartThings rejected the API key (HTTP 401). Tokens created after 30 Dec 2024 " + // library marker davegut.SmartThingsInterface, line 33
				 "expire after 24 hours. SmartThings functions are off until a new key is saved.") // library marker davegut.SmartThingsInterface, line 34
	} // library marker davegut.SmartThingsInterface, line 35
} // library marker davegut.SmartThingsInterface, line 36
 // library marker davegut.SmartThingsInterface, line 37
def stUpdate() { // library marker davegut.SmartThingsInterface, line 38
	def stData = [:] // library marker davegut.SmartThingsInterface, line 39
	state.remove("stAuthFailed")		//	a Save retries the key // library marker davegut.SmartThingsInterface, line 40
	if (connectST) { // library marker davegut.SmartThingsInterface, line 41
		stData << [connectST: "true"] // library marker davegut.SmartThingsInterface, line 42
		stData << [connectST: connectST] // library marker davegut.SmartThingsInterface, line 43
		if (!stApiKey || stApiKey == "") { // library marker davegut.SmartThingsInterface, line 44
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 45
			stData << [status: "ERROR", date: "no stApiKey"] // library marker davegut.SmartThingsInterface, line 46
		} else if (!stDeviceId || stDeviceId == "") { // library marker davegut.SmartThingsInterface, line 47
			getDeviceList() // library marker davegut.SmartThingsInterface, line 48
			logWarn("\n\n\t\t<b>Enter the deviceId from the log List and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 49
			stData << [status: "ERROR", date: "no stDeviceId"] // library marker davegut.SmartThingsInterface, line 50
		} else { // library marker davegut.SmartThingsInterface, line 51
			def stPollInterval = stPollInterval // library marker davegut.SmartThingsInterface, line 52
			if (stPollInterval == null) {  // library marker davegut.SmartThingsInterface, line 53
				stPollInterval = "15" // library marker davegut.SmartThingsInterface, line 54
				device.updateSetting("stPollInterval", [type:"enum", value: "15"]) // library marker davegut.SmartThingsInterface, line 55
			} // library marker davegut.SmartThingsInterface, line 56
			switch(stPollInterval) { // library marker davegut.SmartThingsInterface, line 57
				case "1" : runEvery1Minute(refresh); break // library marker davegut.SmartThingsInterface, line 58
				case "5" : runEvery5Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 59
				case "15" : runEvery15Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 60
				case "30" : runEvery30Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 61
				default: unschedule("refresh") // library marker davegut.SmartThingsInterface, line 62
			} // library marker davegut.SmartThingsInterface, line 63
			deviceSetup() // library marker davegut.SmartThingsInterface, line 64
			stData << [stPollInterval: stPollInterval] // library marker davegut.SmartThingsInterface, line 65
		} // library marker davegut.SmartThingsInterface, line 66
	} else { // library marker davegut.SmartThingsInterface, line 67
		stData << [connectST: "false"] // library marker davegut.SmartThingsInterface, line 68
	} // library marker davegut.SmartThingsInterface, line 69
	logInfo("stUpdate: ${stData}") // library marker davegut.SmartThingsInterface, line 70
} // library marker davegut.SmartThingsInterface, line 71
 // library marker davegut.SmartThingsInterface, line 72
def deviceSetup() { // library marker davegut.SmartThingsInterface, line 73
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.SmartThingsInterface, line 74
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.SmartThingsInterface, line 75
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.SmartThingsInterface, line 76
	} else { // library marker davegut.SmartThingsInterface, line 77
		def sendData = [ // library marker davegut.SmartThingsInterface, line 78
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.SmartThingsInterface, line 79
			parse: "distResp" // library marker davegut.SmartThingsInterface, line 80
			] // library marker davegut.SmartThingsInterface, line 81
		asyncGet(sendData, "deviceSetup") // library marker davegut.SmartThingsInterface, line 82
	} // library marker davegut.SmartThingsInterface, line 83
} // library marker davegut.SmartThingsInterface, line 84
 // library marker davegut.SmartThingsInterface, line 85
def getDeviceList() { // library marker davegut.SmartThingsInterface, line 86
	def sendData = [ // library marker davegut.SmartThingsInterface, line 87
		path: "/devices", // library marker davegut.SmartThingsInterface, line 88
		parse: "getDeviceListParse" // library marker davegut.SmartThingsInterface, line 89
		] // library marker davegut.SmartThingsInterface, line 90
	asyncGet(sendData) // library marker davegut.SmartThingsInterface, line 91
} // library marker davegut.SmartThingsInterface, line 92
 // library marker davegut.SmartThingsInterface, line 93
def getDeviceListParse(resp, data) { // library marker davegut.SmartThingsInterface, line 94
	def respData // library marker davegut.SmartThingsInterface, line 95
	if (resp.status == 401) { stAuthFailed() } // library marker davegut.SmartThingsInterface, line 96
	if (resp.status != 200) { // library marker davegut.SmartThingsInterface, line 97
		respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 98
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 99
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 100
	} else { // library marker davegut.SmartThingsInterface, line 101
		try { // library marker davegut.SmartThingsInterface, line 102
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 103
		} catch (err) { // library marker davegut.SmartThingsInterface, line 104
			respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 105
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 106
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 107
		} // library marker davegut.SmartThingsInterface, line 108
	} // library marker davegut.SmartThingsInterface, line 109
	if (respData.status == "ERROR") { // library marker davegut.SmartThingsInterface, line 110
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.SmartThingsInterface, line 111
	} else { // library marker davegut.SmartThingsInterface, line 112
		log.info "" // library marker davegut.SmartThingsInterface, line 113
		respData.items.each { // library marker davegut.SmartThingsInterface, line 114
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.SmartThingsInterface, line 115
		} // library marker davegut.SmartThingsInterface, line 116
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.SmartThingsInterface, line 117
	} // library marker davegut.SmartThingsInterface, line 118
} // library marker davegut.SmartThingsInterface, line 119
 // library marker davegut.SmartThingsInterface, line 120
def deviceSetupParse(mainData) { // library marker davegut.SmartThingsInterface, line 121
	def setupData = [:] // library marker davegut.SmartThingsInterface, line 122
	 // library marker davegut.SmartThingsInterface, line 123
	def pictureModes = mainData["custom.picturemode"]?.supportedPictureModes?.value // library marker davegut.SmartThingsInterface, line 124
	state.pictureModes = pictureModes // library marker davegut.SmartThingsInterface, line 125
	setupData << [pictureModes: pictureModes] // library marker davegut.SmartThingsInterface, line 126
	 // library marker davegut.SmartThingsInterface, line 127
	def soundModes =  mainData["custom.soundmode"]?.supportedSoundModes?.value // library marker davegut.SmartThingsInterface, line 128
	state.soundModes = soundModes // library marker davegut.SmartThingsInterface, line 129
	setupData << [soundModes: soundModes] // library marker davegut.SmartThingsInterface, line 130
	 // library marker davegut.SmartThingsInterface, line 131
	logInfo("deviceSetupParse: ${setupData}") // library marker davegut.SmartThingsInterface, line 132
} // library marker davegut.SmartThingsInterface, line 133
 // library marker davegut.SmartThingsInterface, line 134
//	async: a slow cloud call must not hold the driver's only thread // library marker davegut.SmartThingsInterface, line 135
def deviceCommand(cmdData) { // library marker davegut.SmartThingsInterface, line 136
	logDebug("deviceCommand: $cmdData") // library marker davegut.SmartThingsInterface, line 137
	if (!stConnected()) { // library marker davegut.SmartThingsInterface, line 138
		logWarn("deviceCommand: [status: FAILED, data: SmartThings not connected, command: ${cmdData.command}]") // library marker davegut.SmartThingsInterface, line 139
		return // library marker davegut.SmartThingsInterface, line 140
	} // library marker davegut.SmartThingsInterface, line 141
	def cmdBody = [commands: [cmdData]] // library marker davegut.SmartThingsInterface, line 142
	def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 143
		uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 144
		path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.SmartThingsInterface, line 145
		headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.SmartThingsInterface, line 146
		requestContentType: "application/json", // library marker davegut.SmartThingsInterface, line 147
		contentType: "application/json", // library marker davegut.SmartThingsInterface, line 148
		body: new groovy.json.JsonBuilder(cmdBody).toString(), // library marker davegut.SmartThingsInterface, line 149
		timeout: 10 // library marker davegut.SmartThingsInterface, line 150
	] // library marker davegut.SmartThingsInterface, line 151
	asynchttpPost("deviceCommandParse", sendCmdParams, [capability: cmdData.capability]) // library marker davegut.SmartThingsInterface, line 152
} // library marker davegut.SmartThingsInterface, line 153
 // library marker davegut.SmartThingsInterface, line 154
def deviceCommandParse(resp, data) { // library marker davegut.SmartThingsInterface, line 155
	if (resp.status == 200) { // library marker davegut.SmartThingsInterface, line 156
		if (data.capability && data.capability != "refresh") { // library marker davegut.SmartThingsInterface, line 157
			deviceRefresh() // library marker davegut.SmartThingsInterface, line 158
		} else { // library marker davegut.SmartThingsInterface, line 159
			poll() // library marker davegut.SmartThingsInterface, line 160
		} // library marker davegut.SmartThingsInterface, line 161
	} else if (resp.status == 401) { // library marker davegut.SmartThingsInterface, line 162
		stAuthFailed() // library marker davegut.SmartThingsInterface, line 163
	} else { // library marker davegut.SmartThingsInterface, line 164
		logWarn("deviceCommand: [status: FAILED, httpCode: ${resp.status}, capability: ${data.capability}]") // library marker davegut.SmartThingsInterface, line 165
		if (resp.status == 409) { // library marker davegut.SmartThingsInterface, line 166
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>") // library marker davegut.SmartThingsInterface, line 167
		} // library marker davegut.SmartThingsInterface, line 168
	} // library marker davegut.SmartThingsInterface, line 169
} // library marker davegut.SmartThingsInterface, line 170
 // library marker davegut.SmartThingsInterface, line 171
def statusParse(mainData) { // library marker davegut.SmartThingsInterface, line 172
	Map logData = [method: "statusParse"] // library marker davegut.SmartThingsInterface, line 173
	if (stTestData) { // library marker davegut.SmartThingsInterface, line 174
		device.updateSetting("stTestData", [type:"bool", value: false]) // library marker davegut.SmartThingsInterface, line 175
		Map testData = [stTestData: mainData] // library marker davegut.SmartThingsInterface, line 176
	} // library marker davegut.SmartThingsInterface, line 177
	String onOff = mainData.switch.switch.value // library marker davegut.SmartThingsInterface, line 178
	Map parseResults = [:] // library marker davegut.SmartThingsInterface, line 179
	if (onOff == "on") { // library marker davegut.SmartThingsInterface, line 180
		Integer volume = mainData.audioVolume.volume.value.toInteger() // library marker davegut.SmartThingsInterface, line 181
		sendEvent(name: "volume", value: volume) // library marker davegut.SmartThingsInterface, line 182
		sendEvent(name: "level", value: volume) // library marker davegut.SmartThingsInterface, line 183
		parseResults << [volume: volume] // library marker davegut.SmartThingsInterface, line 184
 // library marker davegut.SmartThingsInterface, line 185
		String mute = mainData.audioMute.mute.value // library marker davegut.SmartThingsInterface, line 186
		sendEvent(name: "mute", value: mute) // library marker davegut.SmartThingsInterface, line 187
		parseResults << [mute: mute] // library marker davegut.SmartThingsInterface, line 188
 // library marker davegut.SmartThingsInterface, line 189
		String inputSource = mainData.mediaInputSource.inputSource.value // library marker davegut.SmartThingsInterface, line 190
		sendEvent(name: "inputSource", value: inputSource)		 // library marker davegut.SmartThingsInterface, line 191
		parseResults << [inputSource: inputSource] // library marker davegut.SmartThingsInterface, line 192
 // library marker davegut.SmartThingsInterface, line 193
		String tvChannel = mainData.tvChannel.tvChannel.value // library marker davegut.SmartThingsInterface, line 194
		if (tvChannel == null) { tvChannel = " " } // library marker davegut.SmartThingsInterface, line 195
		String tvChannelName = mainData.tvChannel.tvChannelName.value // library marker davegut.SmartThingsInterface, line 196
		parseResults << [tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.SmartThingsInterface, line 197
		if (tvChannel == " " && tvChannelName != device.currentValue("tvChannelName")) { // library marker davegut.SmartThingsInterface, line 198
			//	tvChannel indicates app, tvChannelName is thrn spp code (ST Version) // library marker davegut.SmartThingsInterface, line 199
			if (tvChannelName.contains(".")) { // library marker davegut.SmartThingsInterface, line 200
				runIn(2, updateAppName) // library marker davegut.SmartThingsInterface, line 201
			} // library marker davegut.SmartThingsInterface, line 202
		} // library marker davegut.SmartThingsInterface, line 203
		sendEvent(name: "tvChannel", value: tvChannel) // library marker davegut.SmartThingsInterface, line 204
		sendEvent(name: "tvChannelName", value: tvChannelName) // library marker davegut.SmartThingsInterface, line 205
 // library marker davegut.SmartThingsInterface, line 206
		String pictureMode = mainData["custom.picturemode"].pictureMode.value // library marker davegut.SmartThingsInterface, line 207
		sendEvent(name: "pictureMode",value: pictureMode) // library marker davegut.SmartThingsInterface, line 208
		parseResults << [pictureMode: pictureMode] // library marker davegut.SmartThingsInterface, line 209
 // library marker davegut.SmartThingsInterface, line 210
		String soundMode = mainData["custom.soundmode"].soundMode.value // library marker davegut.SmartThingsInterface, line 211
		sendEvent(name: "soundMode",value: soundMode) // library marker davegut.SmartThingsInterface, line 212
		parseResults << [soundMode: soundMode] // library marker davegut.SmartThingsInterface, line 213
	} // library marker davegut.SmartThingsInterface, line 214
	logDebug(logData) // library marker davegut.SmartThingsInterface, line 215
} // library marker davegut.SmartThingsInterface, line 216
 // library marker davegut.SmartThingsInterface, line 217
private asyncGet(sendData, passData = "none") { // library marker davegut.SmartThingsInterface, line 218
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 219
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.SmartThingsInterface, line 220
	} else { // library marker davegut.SmartThingsInterface, line 221
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.SmartThingsInterface, line 222
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 223
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 224
			path: sendData.path, // library marker davegut.SmartThingsInterface, line 225
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.SmartThingsInterface, line 226
			timeout: 10] // library marker davegut.SmartThingsInterface, line 227
		try { // library marker davegut.SmartThingsInterface, line 228
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.SmartThingsInterface, line 229
		} catch (error) { // library marker davegut.SmartThingsInterface, line 230
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.SmartThingsInterface, line 231
		} // library marker davegut.SmartThingsInterface, line 232
	} // library marker davegut.SmartThingsInterface, line 233
} // library marker davegut.SmartThingsInterface, line 234
 // library marker davegut.SmartThingsInterface, line 235
def distResp(resp, data) { // library marker davegut.SmartThingsInterface, line 236
	def resplog = [:] // library marker davegut.SmartThingsInterface, line 237
	if (resp.status == 200) { // library marker davegut.SmartThingsInterface, line 238
		try { // library marker davegut.SmartThingsInterface, line 239
			def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 240
			if (data.reason == "deviceSetup") { // library marker davegut.SmartThingsInterface, line 241
				deviceSetupParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 242
				runIn(1, statusParse, [data: respData.components.main]) // library marker davegut.SmartThingsInterface, line 243
			} else { // library marker davegut.SmartThingsInterface, line 244
				statusParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 245
			} // library marker davegut.SmartThingsInterface, line 246
		} catch (err) { // library marker davegut.SmartThingsInterface, line 247
			resplog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 248
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 249
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 250
		} // library marker davegut.SmartThingsInterface, line 251
	} else if (resp.status == 401) { // library marker davegut.SmartThingsInterface, line 252
		stAuthFailed() // library marker davegut.SmartThingsInterface, line 253
	} else { // library marker davegut.SmartThingsInterface, line 254
		resplog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 255
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 256
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 257
	} // library marker davegut.SmartThingsInterface, line 258
	if (resplog != [:]) { // library marker davegut.SmartThingsInterface, line 259
		logWarn("distResp: ${resplog}") // library marker davegut.SmartThingsInterface, line 260
	} // library marker davegut.SmartThingsInterface, line 261
} // library marker davegut.SmartThingsInterface, line 262
// ~~~~~ end include (4) davegut.SmartThingsInterface ~~~~~
// ~~~~~ start include (5) davegut.samsungTvST ~~~~~
library ( // library marker davegut.samsungTvST, line 1
	name: "samsungTvST", // library marker davegut.samsungTvST, line 2
	namespace: "davegut", // library marker davegut.samsungTvST, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvST, line 4
	description: "Samsung TV SmartThings Capabilities", // library marker davegut.samsungTvST, line 5
	category: "utilities", // library marker davegut.samsungTvST, line 6
	documentationLink: "" // library marker davegut.samsungTvST, line 7
) // library marker davegut.samsungTvST, line 8
 // library marker davegut.samsungTvST, line 9
command "toggleSoundMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 10
command "togglePictureMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 11
command "sourceSetST", ["SmartThings Function"] // library marker davegut.samsungTvST, line 12
attribute "inputSource", "string" // library marker davegut.samsungTvST, line 13
command "setVolume", ["SmartThings Function"] // library marker davegut.samsungTvST, line 14
command "setPictureMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 15
command "setSoundMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 16
command "setLevel", ["SmartThings Function"] // library marker davegut.samsungTvST, line 17
attribute "level", "NUMBER" // library marker davegut.samsungTvST, line 18
 // library marker davegut.samsungTvST, line 19
def deviceRefresh() { // library marker davegut.samsungTvST, line 20
	if (stConnected()) { // library marker davegut.samsungTvST, line 21
		def cmdData = [ // library marker davegut.samsungTvST, line 22
			component: "main", // library marker davegut.samsungTvST, line 23
			capability: "refresh", // library marker davegut.samsungTvST, line 24
			command: "refresh", // library marker davegut.samsungTvST, line 25
			arguments: []] // library marker davegut.samsungTvST, line 26
		deviceCommand(cmdData) // library marker davegut.samsungTvST, line 27
	} // library marker davegut.samsungTvST, line 28
} // library marker davegut.samsungTvST, line 29
 // library marker davegut.samsungTvST, line 30
def poll() { // library marker davegut.samsungTvST, line 31
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.samsungTvST, line 32
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.samsungTvST, line 33
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.samsungTvST, line 34
	} else { // library marker davegut.samsungTvST, line 35
		def sendData = [ // library marker davegut.samsungTvST, line 36
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.samsungTvST, line 37
			parse: "distResp" // library marker davegut.samsungTvST, line 38
			] // library marker davegut.samsungTvST, line 39
		asyncGet(sendData, "statusParse") // library marker davegut.samsungTvST, line 40
	} // library marker davegut.samsungTvST, line 41
} // library marker davegut.samsungTvST, line 42
 // library marker davegut.samsungTvST, line 43
def setLevel(level) { setVolume(level) } // library marker davegut.samsungTvST, line 44
 // library marker davegut.samsungTvST, line 45
def setVolume(volume) { // library marker davegut.samsungTvST, line 46
	def cmdData = [ // library marker davegut.samsungTvST, line 47
		component: "main", // library marker davegut.samsungTvST, line 48
		capability: "audioVolume", // library marker davegut.samsungTvST, line 49
		command: "setVolume", // library marker davegut.samsungTvST, line 50
		arguments: [volume.toInteger()]] // library marker davegut.samsungTvST, line 51
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 52
} // library marker davegut.samsungTvST, line 53
 // library marker davegut.samsungTvST, line 54
def togglePictureMode() { // library marker davegut.samsungTvST, line 55
	//	requires state.pictureModes // library marker davegut.samsungTvST, line 56
	def pictureModes = state.pictureModes // library marker davegut.samsungTvST, line 57
	if (!pictureModes) { // library marker davegut.samsungTvST, line 58
		logWarn("togglePictureMode: no picture modes from SmartThings") // library marker davegut.samsungTvST, line 59
		return // library marker davegut.samsungTvST, line 60
	} // library marker davegut.samsungTvST, line 61
	def totalModes = pictureModes.size() // library marker davegut.samsungTvST, line 62
	def currentMode = device.currentValue("pictureMode") // library marker davegut.samsungTvST, line 63
	def modeNo = pictureModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 64
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 65
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 66
	def newPictureMode = pictureModes[newModeNo] // library marker davegut.samsungTvST, line 67
	setPictureMode(newPictureMode) // library marker davegut.samsungTvST, line 68
} // library marker davegut.samsungTvST, line 69
 // library marker davegut.samsungTvST, line 70
def setPictureMode(pictureMode) { // library marker davegut.samsungTvST, line 71
	def cmdData = [ // library marker davegut.samsungTvST, line 72
		component: "main", // library marker davegut.samsungTvST, line 73
		capability: "custom.picturemode", // library marker davegut.samsungTvST, line 74
		command: "setPictureMode", // library marker davegut.samsungTvST, line 75
		arguments: [pictureMode]] // library marker davegut.samsungTvST, line 76
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 77
} // library marker davegut.samsungTvST, line 78
 // library marker davegut.samsungTvST, line 79
def toggleSoundMode() { // library marker davegut.samsungTvST, line 80
	def soundModes = state.soundModes // library marker davegut.samsungTvST, line 81
	if (!soundModes) { // library marker davegut.samsungTvST, line 82
		logWarn("toggleSoundMode: no sound modes from SmartThings") // library marker davegut.samsungTvST, line 83
		return // library marker davegut.samsungTvST, line 84
	} // library marker davegut.samsungTvST, line 85
	def totalModes = soundModes.size() // library marker davegut.samsungTvST, line 86
	def currentMode = device.currentValue("soundMode") // library marker davegut.samsungTvST, line 87
	def modeNo = soundModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 88
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 89
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 90
	def soundMode = soundModes[newModeNo] // library marker davegut.samsungTvST, line 91
	setSoundMode(soundMode) // library marker davegut.samsungTvST, line 92
} // library marker davegut.samsungTvST, line 93
 // library marker davegut.samsungTvST, line 94
def setSoundMode(soundMode) {  // library marker davegut.samsungTvST, line 95
	def cmdData = [ // library marker davegut.samsungTvST, line 96
		component: "main", // library marker davegut.samsungTvST, line 97
		capability: "custom.soundmode", // library marker davegut.samsungTvST, line 98
		command: "setSoundMode", // library marker davegut.samsungTvST, line 99
		arguments: [soundMode]] // library marker davegut.samsungTvST, line 100
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 101
} // library marker davegut.samsungTvST, line 102
 // library marker davegut.samsungTvST, line 103
def toggleInputSource() { sourceToggle() } // library marker davegut.samsungTvST, line 104
 // library marker davegut.samsungTvST, line 105
def setInputSource(inputSource) { sourceSetST(inputSource) } // library marker davegut.samsungTvST, line 106
def sourceSetST(inputSource) { // library marker davegut.samsungTvST, line 107
	def cmdData = [ // library marker davegut.samsungTvST, line 108
		component: "main", // library marker davegut.samsungTvST, line 109
		capability: "mediaInputSource", // library marker davegut.samsungTvST, line 110
		command: "setInputSource", // library marker davegut.samsungTvST, line 111
		arguments: [inputSource]] // library marker davegut.samsungTvST, line 112
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 113
} // library marker davegut.samsungTvST, line 114
 // library marker davegut.samsungTvST, line 115
def setMuteST(muteCmd) { // library marker davegut.samsungTvST, line 116
	def cmdData = [ // library marker davegut.samsungTvST, line 117
		component: "main", // library marker davegut.samsungTvST, line 118
		capability: "audioMute", // library marker davegut.samsungTvST, line 119
		command: muteCmd, // library marker davegut.samsungTvST, line 120
		arguments: []] // library marker davegut.samsungTvST, line 121
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 122
} // library marker davegut.samsungTvST, line 123
 // library marker davegut.samsungTvST, line 124
def setTvChannel(newChannel) { // library marker davegut.samsungTvST, line 125
	def cmdData = [ // library marker davegut.samsungTvST, line 126
		component: "main", // library marker davegut.samsungTvST, line 127
		capability: "tvChannel", // library marker davegut.samsungTvST, line 128
		command: "setTvChannel", // library marker davegut.samsungTvST, line 129
		arguments: [newChannel]] // library marker davegut.samsungTvST, line 130
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 131
} // library marker davegut.samsungTvST, line 132
// ~~~~~ end include (5) davegut.samsungTvST ~~~~~
// ~~~~~ start include (6) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging and info gathering Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8
//	Modified by hubitrep in 2026. Changes: https://github.com/hubitrep/SamsungTvRemote#changes // library marker davegut.Logging, line 9
 // library marker davegut.Logging, line 10
def nameSpace() { return "davegut" } // library marker davegut.Logging, line 11
 // library marker davegut.Logging, line 12
def version() { return "2.4.0" } // library marker davegut.Logging, line 13
 // library marker davegut.Logging, line 14
def label() { // library marker davegut.Logging, line 15
	if (device) {  // library marker davegut.Logging, line 16
		return device.displayName + "-${version()}" // library marker davegut.Logging, line 17
	} else {  // library marker davegut.Logging, line 18
		return app.getLabel() + "-${version()}" // library marker davegut.Logging, line 19
	} // library marker davegut.Logging, line 20
} // library marker davegut.Logging, line 21
 // library marker davegut.Logging, line 22
def listAttributes() { // library marker davegut.Logging, line 23
	def attrData = device.getCurrentStates() // library marker davegut.Logging, line 24
	Map attrs = [:] // library marker davegut.Logging, line 25
	attrData.each { // library marker davegut.Logging, line 26
		attrs << ["${it.name}": it.value] // library marker davegut.Logging, line 27
	} // library marker davegut.Logging, line 28
	return attrs // library marker davegut.Logging, line 29
} // library marker davegut.Logging, line 30
 // library marker davegut.Logging, line 31
def setLogsOff() { // library marker davegut.Logging, line 32
	def logData = [logEnable: logEnable] // library marker davegut.Logging, line 33
	if (logEnable) { // library marker davegut.Logging, line 34
		runIn(1800, debugLogOff) // library marker davegut.Logging, line 35
		logData << [debugLogOff: "scheduled"] // library marker davegut.Logging, line 36
	} // library marker davegut.Logging, line 37
	return logData // library marker davegut.Logging, line 38
} // library marker davegut.Logging, line 39
 // library marker davegut.Logging, line 40
def logTrace(msg){ log.trace "${label()}: ${msg}" } // library marker davegut.Logging, line 41
 // library marker davegut.Logging, line 42
def logInfo(msg) {  // library marker davegut.Logging, line 43
	if (infoLog != false) { log.info "${label()}: ${msg}" }		//	unset counts as on // library marker davegut.Logging, line 44
} // library marker davegut.Logging, line 45
 // library marker davegut.Logging, line 46
def debugLogOff() { // library marker davegut.Logging, line 47
	if (device) { // library marker davegut.Logging, line 48
		device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 49
	} else { // library marker davegut.Logging, line 50
		app.updateSetting("logEnable", false) // library marker davegut.Logging, line 51
	} // library marker davegut.Logging, line 52
	logInfo("debugLogOff") // library marker davegut.Logging, line 53
} // library marker davegut.Logging, line 54
 // library marker davegut.Logging, line 55
def logDebug(msg) { // library marker davegut.Logging, line 56
	if (logEnable) { log.debug "${label()}: ${msg}" } // library marker davegut.Logging, line 57
} // library marker davegut.Logging, line 58
 // library marker davegut.Logging, line 59
def logWarn(msg) { log.warn "${label()}: ${msg}" } // library marker davegut.Logging, line 60
 // library marker davegut.Logging, line 61
def logError(msg) { log.error "${label()}: ${msg}" } // library marker davegut.Logging, line 62
// ~~~~~ end include (6) davegut.Logging ~~~~~
