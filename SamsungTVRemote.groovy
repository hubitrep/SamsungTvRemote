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
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

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
	checkDriverVersion()
}

def updated() {
	unschedule()
	close()
	["wsQueue", "currentFunction", "pendingPowerHold", "powerProbeAt",
	 "wsData", "standbyTest", "configured", "onPollCount"].each { state.remove(it) }
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
		configure()
		if (!state.appData) { state.appData = [:] }
		updStatus << [updApps: updateAppCodes()]
	}
	logInfo("updated: ${updStatus}")
}

def setOnPollInterval() {
	String interval = pollInterval
	if (interval == null) {
		interval = "60"
		device.updateSetting("pollInterval", [type:"enum", value: "60"])
	}
	if (interval == "60") {
		runEvery1Minute(onPoll)
	} else if (interval != "off") {
		schedule("0/${interval} * * * * ?",  onPoll)
	}
	return interval
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
			logWarn("configure: no reply from ${deviceIp}. Kept the saved TV settings; if the TV is on, check the IP address.")
		} else {
			logError("configure: TV Off during setup or Invalid IP address.\n\t\tTurn TV On and Run CONFIGURE or Save Preferences!")
		}

	}
	if (!tvData.status) {
		def wifiMac = tvData.device.wifiMac
		updateDataValue("deviceMac", wifiMac)
		def alternateWolMac = wifiMac.replaceAll(":", "").toUpperCase()
		updateDataValue("alternateWolMac", alternateWolMac)
		try {
			device.setDeviceNetworkId(alternateWolMac)
		} catch (e) {
			logWarn("configure: [setDeviceNetworkId: ${alternateWolMac}, error: ${e}]")
		}
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
		saveTvInfo(tvData)
		updateDataValue("powerStateReported", (tvData.device.PowerState != null).toString())
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

//	for support: what the TV is and which REST API it serves
def saveTvInfo(Map reply) {
	if (reply.device?.modelName) { updateDataValue("modelName", reply.device.modelName) }
	if (reply.device?.model) { updateDataValue("platform", reply.device.model) }
	if (reply.version) { updateDataValue("apiVersion", reply.version) }
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
	checkDriverVersion()
}

//	a code push doesn't run updated(); catch it at the next poll, refresh or hub start
def checkDriverVersion() {
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
			Map reply = new JsonSlurper().parseText(resp.data)
			info = reply?.device
			//	a device set up or updated while the TV was off has none yet
			if (info && !getDataValue("modelName")) { saveTvInfo(reply) }
		} catch (e) {
			logDebug([method: "onPollParse", error: "unparsable reply"])
		}
	}
	if (info != null) {
		powerState = info.PowerState
		String reported = (powerState != null).toString()
		if (getDataValue("powerStateReported") != reported) {
			//	firmware updates have dropped PowerState; power then comes from the websocket
			logInfo("onPollParse: [powerStateReported: ${reported}]")
			updateDataValue("powerStateReported", reported)
		}
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
		logInfo("setPowerState: [switch: ${onOff}]")
		if (onOff == "on") {
			//	startup display only after on(), not when a poll blip recovers
			if (state.powerOnAt && now() - state.powerOnAt < 120000) {
				runIn(5, setPowerOnMode)
			}
			runIn(5, refresh)
		} else {
			setPowerOffMode()
		}
		//	switch and art mode are separate: 2025 Frames report PowerState standby while showing art
		if (getDataValue("frameTv") == "true") { runIn(6, getArtModeStatus) }
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
	sendWol()
	//	the TV takes several seconds to boot; WoL over Wi-Fi is often missed, so resend
	runIn(10, onPollWol)
	runIn(20, onPollAgain)
	//	after the 10 s poll has answered or timed out (6 s)
	if (stConnected()) { runIn(17, stPowerOn) }
}

def sendWol() {
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
}

def onPollWol() { resendWol(); onPoll() }
def onPollAgain() { resendWol(); onPoll() }

def resendWol() {
	if (!state.powerOnAt) { return }		//	cleared once the TV is seen on, or by off()
	logDebug([method: "resendWol", secs: (now() - state.powerOnAt) / 1000])
	sendWol()
}

//	Wake-on-LAN missed; SmartThings can often still wake the TV
def stPowerOn() {
	if (!state.powerOnAt) { return }
	logInfo("stPowerOn: [reason: no answer to Wake-on-LAN]")
	deviceCommand([component: "main", capability: "switch", command: "on", arguments: []])
	runIn(5, onPoll)
}

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
	logInfo("setPowerOffMode")
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
	checkDriverVersion()
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

// ===== Common Samsung TV Websocket Commands (samsungTvWebsocket) =====

//	socket state is kept in memory, so a hub reboot or code push starts from "closed"
@Field static ConcurrentHashMap<String, Map> wsMem = new ConcurrentHashMap<String, Map>()

command "webSocketClose"
command "webSocketOpen"
command "close"
attribute "wsStatus", "string"
command "artMode"
attribute "artModeStatus", "string"
command "ambientMode"
//	Remote Control Keys (samsungTV-Keys)
command "pause"
command "play"
command "stop"
command "sendKey", ["string"]
command "getInstalledApps"		//	diagnostic: query the TV's installed-app list (WS)
//	Cursor and Entry Control
command "arrowLeft"
command "arrowRight"
command "arrowUp"
command "arrowDown"
command "enter"
command "numericKeyPad"
//	Menu Access
command "home"
command "menu"
command "guide"
//command "info"	//  enter
//	Source Commands
command "sourceSetOSD"
command "sourceToggle"
//command "hdmi"
//	TV Channel
command "channelList"
command "channelUp"
command "channelDown"
command "channelSet", ["string"]
command "previousChannel"
//	Playing Navigation Commands
command "exit"
command "Return"
command "fastBack"
command "fastForward"

//	== ART/Ambient Mode
def artMode() {
	def artModeStatus = device.currentValue("artModeStatus")
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs]
	if (getDataValue("frameTv") != "true") {
		logData << [status: "Not a Frame TV"]
	} else if (artModeStatus == "on") {
		logData << [status: "artMode already set"]
	} else {
		if (state.artModeWs) {
			def data = [value:"on",
						request:"set_artmode_status",
						id: "${getDataValue("uuid")}"]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
			logData << [status: "Sending artMode WS Command"]
		} else {
			sendKey("POWER")
			logData << [status: "Sending Power WS Command"]
			if (artModeStatus == "none") {
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"]
			}
		}
		runIn(10, getArtModeStatus)
	}
	logInfo("artMode: ${logData}")
}

//	a reply on the art channel sets state.artModeWs; until then artMode() falls back to KEY_POWER
def getArtModeStatus(Map retry = null) {
	if (getDataValue("frameTv") == "true") {
		//	replacing a remote socket mid-handshake would drop its queued keys
		Map s = ws()
		int tries = retry?.tries ?: 0
		if (s.funct == "remote" && s.phase in ["connecting", "open"] && tries < 5) {
			runIn(3, getArtModeStatus, [data: [tries: tries + 1]])
			return
		}
		def data = [request:"get_artmode_status",
					id: "${getDataValue("uuid")}"]
		data = JsonOutput.toJson(data)
		artModeCmd(data)
	}
}

def artModeCmd(data) {
	def cmdData = [method:"ms.channel.emit",
				   params:[data:"${data}",
						   to:"host",
						   event:"art_app_request"]]
	cmdData = JsonOutput.toJson(cmdData)
	sendMessage("frameArt", cmdData)
	//	the open art socket blocks the remote channel and the power probe
	runIn(15, closeArt)
}

def closeArt() {
	Map s = ws()
	if (s.funct == "frameArt" && s.phase != "closed") { close() }
}

def ambientMode() {
	sendKey("AMBIENT")
	runIn(10, refresh)
}

//	== Remote Commands
//	KEY_MUTE toggles; SmartThings sets the state directly
def mute() {
	if (stConnected()) { setMuteST("mute") }
	else if (device.currentValue("mute") != "muted") { sendKey("MUTE") }
}

def unmute() {
	if (stConnected()) { setMuteST("unmute") }
	else if (device.currentValue("mute") != "unmuted") { sendKey("MUTE") }
}

def volumeUp() { sendKeyThenRefresh("VOLUP") }

def volumeDown() { sendKeyThenRefresh("VOLDOWN") }

def play() { sendKeyThenRefresh("PLAY") }

def pause() { sendKeyThenRefresh("PAUSE") }

def stop() { sendKeyThenRefresh("STOP") }

def exit() { sendKeyThenRefresh("EXIT") }

def Return() { sendKeyThenRefresh("RETURN") }

def fastBack() {
	sendKey("LEFT", "Press")
	runInMillis(1000, keyRelease, [data: [key: "LEFT"]])
}

def fastForward() {
	sendKey("RIGHT", "Press")
	runInMillis(1000, keyRelease, [data: [key: "RIGHT"]])
}

def keyRelease(data) { sendKey(data.key, "Release") }

def arrowLeft() { sendKey("LEFT") }

def arrowRight() { sendKey("RIGHT") }

def arrowUp() { sendKey("UP") }

def arrowDown() { sendKey("DOWN") }

def enter() { sendKeyThenRefresh("ENTER") }

def numericKeyPad() { sendKey("MORE") }

def home() { sendKey("HOME") }

def menu() { sendKey("MENU") }

def guide() { sendKey("GUIDE") }

def info() { enter() }

def source() { sourceSetOSD() }
def sourceSetOSD() { sendKey("SOURCE") }

def hdmi() { sourceToggle() }
def sourceToggle() { sendKeyThenRefresh("HDMI") }

def channelList() { sendKey("CH_LIST") }

def channelUp() { sendKeyThenRefresh("CHUP") }
def nextTrack() { channelUp() }

def channelDown() { sendKeyThenRefresh("CHDOWN") }
def previousTrack() { channelDown() }

//	Uses ST interface if available.
def channelSet(channel) {
	if (stConnected()) {
		setTvChannel(channel)
	} else {
		for (int i = 0; i < channel.length(); i++) {
			sendKey(channel[i])
		}
		enter()
		sendEvent(name: "tvChannel", value: channel)
	}
}

def previousChannel() { sendKeyThenRefresh("PRECH") }

def showMessage() { logWarn("showMessage: not implemented") }

//	== WebSocket Communications / Parse
def sendKeyThenRefresh(key) {
	sendKey(key)
	if (stConnected()) { runIn(3, deviceRefresh) }
}

def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						Option:"false",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data).toString() )
}

def getInstalledApps() {
	//	Diagnostic: ask the TV for its installed-app list over the remote channel.
	//	2020+ Tizen often refuses this; parse() "ed.installedApp.get" logs any result.
	def data = [method:"ms.channel.emit",
				params:[event:"ed.installedApp.get", to:"host"]]
	sendMessage("remote", JsonOutput.toJson(data).toString())
	logInfo("getInstalledApps: request sent (watch for 'ed.installedApp.get' in logs)")
}

Map ws() {
	String key = device.id.toString()
	Map m = wsMem[key]
	if (m == null) {
		m = [phase: "closed", funct: null, queue: [], queuedAt: null, connectingAt: null,
			 holdAt: null, probeAt: null, closeAfterSend: false]
		wsMem[key] = m
	}
	return m
}

//	phases: closed -> connecting -> open (transport up) -> ready (TV sent ms.channel.connect)
def sendMessage(funct, data) {
	Map s = ws()
	Map logData = [method: "sendMessage", phase: s.phase, funct: funct, socketFunct: s.funct]
	if (s.phase == "ready" && s.funct == funct) {
		execMessage(data)
		logData << [action: "sent"]
	} else {
		if (s.funct != funct && s.phase != "closed") { close() }
		if (s.funct != funct) { s.queue = [] }
		if (!s.queue) { s.queuedAt = now() }
		if (s.queue.size() >= 10) { s.queue.remove(0) }
		s.queue << data
		boolean stale = s.connectingAt && now() - s.connectingAt > 10000
		if (s.phase == "closed" || s.funct != funct || stale) {
			connect(funct)
			logData << [action: "connect"]
		}
		logData << [queued: s.queue.size()]
	}
	armIdleClose()
	logDebug(logData)
}

def armIdleClose() {
	if (settings.wsIdleClose && settings.wsIdleClose != "never") {
		runIn(settings.wsIdleClose.toInteger() * 60, close)
	}
}

def execMessage(data) {
	interfaces.webSocket.sendMessage(data)
}

def webSocketOpen() { connect("remote") }
def webSocketClose() { close() }

def connect(funct) {
	logDebug("connect: function = ${funct}")
	def url
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ=="
	//	the art channel takes no token; 2024+ Frames stall the handshake when given one
	if (getDataValue("tokenSupport") == "true") {
		if (funct == "remote") {
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}"
		} else if (funct == "frameArt") {
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}"
		}
	} else {
		if (funct == "remote") {
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}"
		} else if (funct == "frameArt") {
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}"
		}
	}
	if (url == null) {
		logWarn("connect: invalid function ${funct}")
		return
	}
	Map s = ws()
	s.phase = "connecting"
	s.funct = funct
	s.connectingAt = now()
	interfaces.webSocket.connect(url, ignoreSSLIssues: true)
}

def close() {
	logDebug("close")
	Map s = ws()
	//	the transport reports closing later, possibly after a replacement socket is up
	if (s.phase != "closed") { s.closedAt = now() }
	s.phase = "closed"
	s.queue = []
	s.holdAt = null
	s.closeAfterSend = false
	s.connectingAt = null
	interfaces.webSocket.close()
	sendEvent(name: "wsStatus", value: "closed")
}

def webSocketStatus(message) {
	Map s = ws()
	Map logData = [method: "webSocketStatus", message: message, funct: s.funct]
	if (message.startsWith("status: open")) {
		s.phase = "open"
		s.connectingAt = null
		sendEvent(name: "wsStatus", value: "open")
		//	the TV normally confirms at once with ms.channel.connect
		runIn(3, wsReadyTimeout)
	} else if (message.startsWith("status: closing")) {
		if (s.closedAt && now() - s.closedAt < 3000) {
			//	the socket close() shut, not the current one
			s.closedAt = null
			logData << [action: "ignored"]
		} else if (s.phase != "connecting") {
			s.phase = "closed"
			s.queue = []
			s.holdAt = null
			s.closeAfterSend = false
			sendEvent(name: "wsStatus", value: "closed")
		}
	} else if (message.startsWith("failure")) {
		wsFailed()
		sendEvent(name: "wsStatus", value: "closed-failure")
	} else {
		logData << [action: "ignored"]
	}
	logDebug(logData)
}

def wsFailed() {
	Map s = ws()
	//	no art channel: nothing is on screen
	if (s.funct == "frameArt" && state.artModeWs && device.currentValue("artModeStatus") == "on") {
		sendEvent(name: "artModeStatus", value: "off")
	}
	//	only a remote-socket failure indicates TV power state
	if (s.funct == "remote") {
		state.lastWsFailure = now()
		if (s.probeAt) {
			s.probeAt = null
			probeFailed()
		}
	}
	s.phase = "closed"
	s.queue = []		//	undeliverable; don't fire stale keys on reconnect
	s.holdAt = null
	s.closeAfterSend = false
	s.connectingAt = null
	interfaces.webSocket.close()
}

def wsReady() {
	Map s = ws()
	if (s.phase == "ready") { return }
	s.phase = "ready"
	unschedule("wsReadyTimeout")
	Map logData = [method: "wsReady", funct: s.funct]
	if (s.funct == "remote") {
		state.lastWsFailure = null
		if (s.probeAt) {
			s.probeAt = null
			probeOpened()
		}
	}
	if (s.queue) {
		//	keys queued long ago belong to an earlier attempt; a late KEY_POWER would toggle the TV
		if (s.queuedAt && now() - s.queuedAt < 15000) {
			s.queue.each { execMessage(it) }
			logData << [drained: s.queue.size()]
		} else {
			logData << [discarded: s.queue.size()]
		}
		s.queue = []
	}
	if (s.holdAt) {
		boolean fresh = now() - s.holdAt < 15000
		s.holdAt = null
		if (fresh) {
			logData << [action: "powerHold"]
			powerHold()
		}
	}
	if (s.closeAfterSend) {
		s.closeAfterSend = false
		runIn(2, close)
	}
	logDebug(logData)
}

def wsReadyTimeout() {
	Map s = ws()
	if (s.phase != "open") { return }
	if (s.probeAt) {
		//	accepted but never confirmed: the TV is asleep
		s.probeAt = null
		probeFailed()
		close()
	} else {
		wsReady()
	}
}

def probeOpened() { setPowerState("on", [method: "probePower", powerState: "absent/wsReady"]) }
def probeFailed() { setPowerState("off", [method: "probePower", powerState: "absent/wsFailed"]) }

def parse(resp) {
	def logData = [method: "parse"]
	try {
		resp = parseJson(resp)
		def event = resp.event
		logData << [EVENT: event]
		switch(event) {
			case "ms.channel.connect":
				def newToken = resp.data?.token
				if (newToken != null && newToken != state.token) {
					state.token = newToken
					logData << [TOKEN: "updated"]
				}
				//	the art channel takes requests only after ms.channel.ready
				if (ws().funct != "frameArt") { wsReady() }
				break
			case "ms.channel.ready":
				if (ws().funct == "frameArt") { wsReady() }
				break
			case "ms.channel.timeOut":
				//	connected, but the TV did not confirm: it is asleep
				Map s = ws()
				if (s.funct == "remote") { state.lastWsFailure = now() }
				if (s.probeAt) {
					s.probeAt = null
					probeFailed()
				}
				close()
				break
			case "d2d_service_message":
				def data = parseJson(resp.data)
				if (data.event == "artmode_status" ||
					data.event == "art_mode_changed") {
					def status = data.value
					if (status == null) { status = data.status }
					sendEvent(name: "artModeStatus", value: status)
					logData << [artModeStatus: status]
					state.artModeWs = true
					runIn(2, closeArt)
				}
				break
			case "ed.installedApp.get":
				def payload = resp.data
				if (payload instanceof String) { payload = parseJson(payload) }
				def appList = payload?.data
				logInfo("ed.installedApp.get: [count: ${appList ? appList.size() : 0}, apps: ${appList?.collect { [name: it.name, appId: it.appId] }}]")
				break
			case "ms.channel.unauthorized":
				//	token rejected; reset to the placeholder so the next connect prompts
				//	the on-screen allow, which returns a fresh token via ms.channel.connect
				state.token = "12345678"
				Map s = ws()
				//	the TV answered, so it is on
				if (s.probeAt) {
					s.probeAt = null
					probeOpened()
				}
				logWarn(logData)
				break
			case "ms.error":
			case "ms.channel.clientConnect":
			case "ms.channel.clientDisconnect":
			case "ms.remote.touchEnable":
			case "ms.remote.touchDisable":
				break
			default:
				logData << [STATUS: "Not Parsed", DATA: resp.data]
				break
		}
		logDebug(logData)
	} catch (e) {
		logData << [STATUS: "unhandled", ERROR: e]
		logWarn(logData)
	}
}

// ===== Samsung TV Applications (samsungTvApps) =====

command "appOpenByName", ["string"]
command "appClose"
attribute "nowPlaying", "string"
attribute "appName", "string"
attribute "appId", "string"
attribute "tvChannel", "string"
attribute "tvChannelName", "string"

def tvAppsPreferences() {
	input ("findAppCodes", "enum", title: "Scan for App Codes (takes 10 minutes)", 
		   options: ["off", "startOver", "find"], defaultValue: "off")
}

def appOpenByName(appName) {
	def logData = [method: "appOpenByName"]
	if (!appName || !state.appData) {
		logWarn([method: "appOpenByName", error: "no app name or no app list"])
		return
	}
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) }
	if (thisApp != null) {
		def appId = thisApp.value
		appName = thisApp.key
		logData << [appName: appName, appId: appId]
		def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
		try {
			//	the TV answers a bare true (rejected by the default JSON parser) after about 6 s
			httpPost([uri: uri, body: "", textParser: true, timeout: 15]) { resp ->
				logData << [status: resp.status, data: resp.data?.text?.trim(), success: resp.success]
				if (resp.status == 200) {
					if (stConnected()) { runIn(10, deviceRefresh) }
					sendEvent(name: "appName", value: appName)
					sendEvent(name: "appId", value: appId)
					logDebug(logData)
				} else {
					logWarn(logData)
				}
			}
		} catch (err) {
			logData << [status: "httpPost error", data: err]
			logWarn(logData)
		}
	} else {
		logData << [error: "appId is null"]
		logWarn(logData)
	}
}

def appClose(appId = device.currentValue("appId")) {
	def logData = [method: "appClose", appId: appId]
	if (appId == null || appId == " ") {
		logData << [status: "appId is null", action: "try exit()"]
		sendEvent(name: "appName", value: " ")
		sendEvent(name: "appId", value: " ")
		if (stConnected()) { runIn(5, deviceRefresh) }
	} else {
		logData << [status: "sending appClose"]
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}",
					  timeout: 3]
		asynchttpDelete("appCloseParse", params, [appId: appId])
	}
	logDebug(logData)
}

def appCloseParse(resp, data) {
	Map logData = [method: "appCloseParse", data: data]
	if (resp.status == 200 && resp.json == true) {
		logData << [status: resp.status, success: resp.json]
		logDebug(logData)
	} else {
		logData << [status: resp.status, success: "false", action: "exit()"]
		exit()
		exit()
		logWarn(logData)
	}
	if (stConnected()) { runIn(5, deviceRefresh) }
	sendEvent(name: "appName", value: " ")
	sendEvent(name: "appId", value: " ")
}

def updateAppCodes() {
	Map logData = [method: "updateAppCodes", findAppCodes: findAppCodes]
	def scan = findAppCodes in ["startOver", "find"]
	if (scan && device.currentValue("switch") == "on") {
		device.updateSetting("findAppCodes", [type:"enum", value: "off"])
		if (findAppCodes == "startOver") { 
			state.appData = [:]
			logData << [appData: "reset"]
		}
		state.appsInstalled = []
		def appIds = appIdList()
		def appId = 0
		logData << [codesToCheck: appIds.size(), status: "OK"]
		runIn(5, getAppData)
		logInfo(logData)
	} else if (scan) {
		logData << [status: "FAILED", reason: "tv off"]
		logWarn(logData)
	}
	return logData
}

def getAppData(appId = 0) {
	Map logData = [method: "getAppData", appId: appId]
	def appIds = appIdList()
	if (appId < appIds.size()) {
		def appCode = appIds[appId]
		logData << [appCode: appCode]
		def thisDevice = state.appData.find { it.value == appCode }
		if (thisDevice != null) {
			logData << [thisDevice: thisDevice, status: "Already in appData"]
			appId = appId + 1
			runInMillis(100, getAppData, [data: appId])
		} else {
			logData << [status: "looking for App"]
			Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appCode}",
						  timeout: 10]
			asynchttpGet("parseGetAppData", params, [appId:appId, appCode: appCode])
		}
		logDebug(logData)
	} else {
		logData << [status: "Done finding", totalApps: state.appData.size(), appsInstalled: state.appsInstalled]
		state.remove("appsInstalled")
		state.remove("retry")
		logInfo(logData)
	}
}

def parseGetAppData(resp, data) {
	Map logData = [method: "parseGetAppData", data: data, status: resp.status]
	def respData = null
	if (resp.status == 200) {
		try { respData = new JsonSlurper().parseText(resp.data) } catch (e) { }
	}
	if (respData?.name && respData?.id) {
		String name = shortenName(respData.name)
		logData << [name: name, status: "appAdded"]
		state.appData << ["${name}": respData.id]
		state.appsInstalled << name
		logDebug(logData)
		state.retry = false
		runIn(1, getAppData, [data: data.appId + 1])
	} else if (resp.status == 404) {
		logData << [status: "appNotAdded", reason: "not installed in TV"]
		logDebug(logData)
		state.retry = false
		runIn(1, getAppData, [data: data.appId + 1])
	} else {
		logData << [retry: state.retry, status: "appNotAdded",
					reason: "invalid response from device"]
		if (!state.retry) {
			logData << [action: "<b>RETRYING</b>"]
			state.retry = true
			runIn(5, getAppData, [data: data.appId])
		} else {
			runIn(1, getAppData, [data: data.appId + 1])
		}
		logWarn(logData)
	}
}

def shortenName(name) {
	if (name == null) { return " " }
	if (name.contains(" - ")) {
		name = name.substring(0, name.indexOf(" - "))
	} else if (name.contains(" by ")) {
		name = name.substring(0, name.indexOf(" by "))
	} else if (name.contains(": ")) {
		name = name.substring(0, name.indexOf(": "))
	} else if (name.contains(" | ")) {
		name = name.substring(0, name.indexOf(" | "))
	}
	return name
}

def appIdList() {
	def appList = [
		"Nuvyyo0002.tablo", "5b8c3eb16b.BeamCTVDev", "kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby", 
		"ZmmGjO6VKO.slingtv", "PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu", 
		"AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV", "cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock", 
		"9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream", "3201907018807", "3201910019365", 
		"3201907018784", "kIciSQlYEM.plex", "ckfgqqzvt0.dplus", "H7DIeAitkn.DisneyNOW",
		"MCmYXNxgcu.DisneyPlus", "tCyZuSsCVw.Britbox", "tzo5Zi4mCPv.fuboTV", "3HYANqBDJD.DFW",
		"EYm8vc1St4.Philo", "N4St7cQBPD.SiriusXM", "sNUyBbfvHf.SpectrumTV", "rJeHak5zRg.Spotify",
		"3KA0pm7a7V.TubiTV", "r1mzFxGfYe.E","3201606009684", "3201910019365", "3201807016597", 
		"3201601007625", "3201710015037", "3201908019041", "3201504001965", "3201907018784", 
		"org.tizen.browser", "org.tizen.primevideo", "org.tizen.netflix-app", 
		"com.samsung.tv.aria-video", "com.samsung.tv.gallery", "org.tizen.apple.apple-music",
		"com.samsung.tv.store",
		
		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421",
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577",
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626",
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378",
		"3201910019354", "3201909019271", "3201909019175", "3201908019041", "3201908019022", 
		"3201907018786", "3201906018693",
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074",
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367",
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067",
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489",
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079",
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210",
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031",
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746",
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230",
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488",
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101",
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148",
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407",
		"11091000000"
	]
	return appList.unique()
}

def updateAppName(tvName = device.currentValue("tvChannelName")) {
	//	If the tvChannel is blank, the name may reflect the appId
	//	that is used by the device.  Thanks SmartThings.
	String appId = " "
	String appName = " "
	Map logData = [method: "updateAppName", tvName: tvName]
	//	There are some names that need translation based on known
	//	idiosyncrasies with the SmartThings implementation.
	//	Go to translation table and if the translation exists,
	//	set the appName to that value.
	def tempName = transTable().find { it.key == tvName }
	if (tempName != null) { 
		appName = tempName.value
		logData << [tempName: tempName]
	}
	//	See if the name is in the app list.  If so, update here
	//	and in states.
	def thisApp = state.appData.find { it.key == appName }
	if (thisApp) {
		logData << [thisApp: thisApp]
		appId = thisApp.value
	} else {
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${tvName}",
				  	timeout: 10]
		try {
			httpGet(params) { resp ->
				appId = resp.data.id
				appName = shortenName(resp.data.name)
				logData << [appId: appId, appName: appName]
			}
		}catch (err) {
			logData << [error: err]
		}
		if (appId?.trim() && appName?.trim()) {
			state.appData << ["${appName}": appId]
			logData << [appData: ["${appName}": appId]]
		}
	}
	sendEvent(name: "appName", value: appName)
	sendEvent(name: "appId", value: appId)
	logDebug(logData)
}

def updateTitle() {
	String tvChannel = device.currentValue("tvChannel")
	String title = "${tvChannel}: ${device.currentValue("tvChannelName")}"
	if (tvChannel == " ") {
		title = "app: ${device.currentValue("appName")}"
	}
	sendEvent(name: "nowPlaying", value: title)
}

def transTable() {
	def translations = [
		"org.tizen.primevideo": "Prime Video",
		"org.tizen.netflix-app": "Netflix",
		"org.tizen.browser": "Internet",
		"com.samsung.tv.aria-video": "Apple TV",
		"com.samsung.tv.gallery": "Gallery",
		"org.tizen.apple.apple-music": "Apple Music"
		]
	return translations
}

// ===== Samsung TV Preset Implementation (samsungTvPresets) =====

command "presetUpdateNext"
command "presetCreate", [
	[name: "Preset Number", type: "ENUM", 
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]]
command "presetExecute", [
	[name: "Preset Number", type: "ENUM",
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]]
command "presetCreateTv", [
	[name: "Preset Number", type: "ENUM",
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]],
	[name: "tvChannel", type: "STRING"],
	[name: "tvChannelName", type: "STRING"]]
attribute "presetUpdateNext", "string"

def presetUpdateNext() {
	//	Sets up next presetExecute to update the preset selected
	//	Has a 10 second timer to select preset to reset.  Will then
	//	revert back to false
	sendEvent(name: "presetUpdateNext", value: "true")
	runIn(5, undoUpdate)
}
def undoUpdate() { sendEvent(name: "presetUpdateNext", value: "false") }

def presetCreate(presetNumber) {
	//	Called from Hubitat Device's page for TV or from presetExecute
	//	when state.updateNextPreset is true
	if (stConnected()) {
		refresh()
		runIn(3, presetCreateRead, [data: [presetNumber: presetNumber]])		//	after the status reply
	} else {
		presetCreateRead([presetNumber: presetNumber])
	}
}

def presetCreateRead(data) {
	def presetNumber = data.presetNumber
	Map logData = [method: "presetCreate", presetNumber: presetNumber]
	String appName = device.currentValue("appName")
	String tvChannel = device.currentValue("tvChannel")
	if (appName != " ") {
		String appId = device.currentValue("appId")
		presetCreateApp(presetNumber, appName, appId)
		logData << [action: "appPresetCreate"]
	} else if (tvChannel != " ") {
		String tvChannelName = device.currentValue("tvChannelName")
		presetCreateTv(presetNumber, tvChannel, tvChannelName)
		logData << [action: "tvPresetCreate"]
	}
	logInfo(logData)
}

def presetCreateApp(presetNumber, appName, appId) {
	Map logData = [method: "presetCreateApp", presetNumber: presetNumber,
				   appName: appName, appId: appId]
	Map thisPresetData = [type: "application", execute: appName, appId: appId]
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"]
	presetDataUpdate(presetNumber, thisPresetData)
	logInfo(logData)
}

def presetCreateTv(presetNumber, tvChannel, tvChannelName) {
	Map logData = [method: "presetCreateTv", presetNumber: presetNumber, 
				   tvChannel: tvChannel, tvChannelName: tvChannelName]
	Map thisPresetData = [type: "tvChannel", execute: tvChannel, tvChannelName: tvChannelName]
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"]
	presetDataUpdate(presetNumber, thisPresetData)
	logInfo(logData)
}

def presetDataUpdate(presetNumber, thisPresetData) {
	Map presetData = state.presetData
	state.remove("presetData")
	if (presetData == null) { presetData = [:] }
	if (presetData.find{it.key == presetNumber}) {
		presetData.remove(presetNumber)
	}
	presetData << ["${presetNumber}": thisPresetData]
	state.presetData = presetData
}

def presetChannelSet(data) {
	channelSet(data.tvChannel)
	sendEvent(name: "appId", value: " ")
	sendEvent(name: "appName", value: " ")
	sendEvent(name: "tvChannel", value: data.tvChannel)
	sendEvent(name: "tvChannelName", value: data.tvChannelName)
}

def presetExecute(presetNumber) {
	Map logData = [method: "presetExecute", presetNumber: presetNumber]
	if (device.currentValue("presetUpdateNext") == "true") {
		sendEvent(name: "presetUpdateNext", value: "false")
		logData << [action: "presetCreate"]
		presetCreate(presetNumber)
	} else {
		if (state.presetData == null) { state.presetData = [:] }
		def thisPreset = state.presetData.find { it.key == presetNumber }
		if (thisPreset == null) {
			logData << [error: "presetNotSet"]
			logWarn(logData)
		} else {
			def execute = thisPreset.value.execute
			def presetType = thisPreset.value.type
			if (presetType == "application") {
				//	Simply open the app.
				appOpenByName(execute)		//	sets appId and appName when the launch works
				sendEvent(name: "tvChannel", value: " ")
				sendEvent(name: "tvChannelName", value: " ")
				logData << [appName: execute, appId: thisPreset.value.appId]
			} else if (presetType == "tvChannel") {
				//	Close the running app, then change the channel
				Map chData = [tvChannel: execute, tvChannelName: thisPreset.value.tvChannelName]
				if (!stConnected() && device.currentValue("appId") != " ") {
					appClose()
					runIn(7, presetChannelSet, [data: chData])		//	after the app closes
				} else {
					presetChannelSet(chData)
				}
				logData << chData
			} else {
				logData << [error: "invalid preset type"]
				logWarn(logData)
			}
		}
		runIn(2, updateTitle)
	}
	logDebug(logData)
}

// ===== Samsung TV SmartThings Capabilities (SmartThingsInterface) =====

def stPreferences() {
	input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
	if (connectST) {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)",
			   options: ["off", "1", "5", "15", "30"], defaultValue: "15")
		input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false)
	}
}

//	usable SmartThings link: enabled, configured, and the key not rejected
def stConnected() {
	return connectST && stApiKey?.trim() && stDeviceId?.trim() && !state.stAuthFailed
}

//	SmartThings personal access tokens created after 30 Dec 2024 expire after 24 hours
def stAuthFailed() {
	if (!state.stAuthFailed) {
		state.stAuthFailed = true
		unschedule("refresh")
		logError("SmartThings rejected the API key (HTTP 401). Tokens created after 30 Dec 2024 " +
				 "expire after 24 hours. SmartThings functions are off until a new key is saved.")
	}
}

def stUpdate() {
	def stData = [:]
	state.remove("stAuthFailed")		//	a Save retries the key
	if (connectST) {
		stData << [connectST: connectST]
		if (!stApiKey || stApiKey == "") {
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n")
			stData << [status: "ERROR", data: "no stApiKey"]
		} else if (!stDeviceId || stDeviceId == "") {
			getDeviceList()
			logWarn("\n\n\t\t<b>Enter the deviceId from the log List and Save Preferences</b>\n\n")
			stData << [status: "ERROR", data: "no stDeviceId"]
		} else {
			def stPollInterval = stPollInterval
			if (stPollInterval == null) { 
				stPollInterval = "15"
				device.updateSetting("stPollInterval", [type:"enum", value: "15"])
			}
			switch(stPollInterval) {
				case "1" : runEvery1Minute(refresh); break
				case "5" : runEvery5Minutes(refresh); break
				case "15" : runEvery15Minutes(refresh); break
				case "30" : runEvery30Minutes(refresh); break
				default: unschedule("refresh")
			}
			deviceSetup()
			stData << [stPollInterval: stPollInterval]
		}
	} else {
		stData << [connectST: "false"]
	}
	logInfo("stUpdate: ${stData}")
}

def deviceSetup() {
	if (!stDeviceId || stDeviceId.trim() == "") {
		logWarn("deviceSetup: [status: ERROR, errorMsg: no stDeviceId]")
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "distResp"
			]
		asyncGet(sendData, "deviceSetup")
	}
}

def getDeviceList() {
	def sendData = [
		path: "/devices",
		parse: "getDeviceListParse"
		]
	asyncGet(sendData)
}

def getDeviceListParse(resp, data) {
	def respData
	if (resp.status == 401) { stAuthFailed() }
	if (resp.status != 200) {
		respData = [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	} else {
		try {
			respData = new JsonSlurper().parseText(resp.data)
		} catch (err) {
			respData = [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	}
	if (respData.status == "ERROR") {
		logWarn("getDeviceListParse: ${respData}")
	} else {
		log.info ""
		respData.items.each {
			log.trace "${it.label}:   ${it.deviceId}"
		}
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>"
	}
}

def deviceSetupParse(mainData) {
	def setupData = [:]
	
	def pictureModes = mainData["custom.picturemode"]?.supportedPictureModes?.value
	state.pictureModes = pictureModes
	setupData << [pictureModes: pictureModes]
	
	def soundModes =  mainData["custom.soundmode"]?.supportedSoundModes?.value
	state.soundModes = soundModes
	setupData << [soundModes: soundModes]
	
	logInfo("deviceSetupParse: ${setupData}")
}

//	async: a slow cloud call must not hold the driver's only thread
def deviceCommand(cmdData) {
	logDebug("deviceCommand: $cmdData")
	if (!stConnected()) {
		logWarn("deviceCommand: [status: FAILED, data: SmartThings not connected, command: ${cmdData.command}]")
		return
	}
	def cmdBody = [commands: [cmdData]]
	def sendCmdParams = [
		uri: "https://api.smartthings.com/v1",
		path: "/devices/${stDeviceId.trim()}/commands",
		headers: ['Authorization': 'Bearer ' + stApiKey.trim()],
		requestContentType: "application/json",
		contentType: "application/json",
		body: new groovy.json.JsonBuilder(cmdBody).toString(),
		timeout: 10
	]
	asynchttpPost("deviceCommandParse", sendCmdParams, [capability: cmdData.capability])
}

def deviceCommandParse(resp, data) {
	if (resp.status == 200) {
		if (data.capability && data.capability != "refresh") {
			deviceRefresh()
		} else {
			poll()
		}
	} else if (resp.status == 401) {
		stAuthFailed()
	} else {
		logWarn("deviceCommand: [status: FAILED, httpCode: ${resp.status}, capability: ${data.capability}]")
		if (resp.status == 409) {
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>")
		}
	}
}

def statusParse(mainData) {
	Map logData = [method: "statusParse"]
	if (stTestData) {
		device.updateSetting("stTestData", [type:"bool", value: false])
		logInfo([stTestData: mainData])
	}
	String onOff = mainData.switch.switch.value
	Map parseResults = [:]
	if (onOff == "on") {
		Integer volume = mainData.audioVolume.volume.value.toInteger()
		sendEvent(name: "volume", value: volume)
		sendEvent(name: "level", value: volume)
		parseResults << [volume: volume]

		String mute = mainData.audioMute.mute.value
		sendEvent(name: "mute", value: mute)
		parseResults << [mute: mute]

		//	2018+ Samsung TVs report the input in samsungvd.mediaInputSource; mediaInputSource stays null
		String inputCap = mainData["samsungvd.mediaInputSource"] ? "samsungvd.mediaInputSource" : "mediaInputSource"
		state.inputSourceCap = inputCap
		String inputSource = mainData[inputCap]?.inputSource?.value
		sendEvent(name: "inputSource", value: inputSource)		
		parseResults << [inputSource: inputSource]

		String tvChannel = mainData.tvChannel.tvChannel.value
		if (tvChannel == null) { tvChannel = " " }
		String tvChannelName = mainData.tvChannel.tvChannelName.value
		parseResults << [tvChannel: tvChannel, tvChannelName: tvChannelName]
		if (tvChannel == " " && tvChannelName != device.currentValue("tvChannelName")) {
			//	a blank tvChannel means an app; tvChannelName is then the app code (SmartThings)
			if (tvChannelName.contains(".")) {
				runIn(2, updateAppName)
			}
		}
		sendEvent(name: "tvChannel", value: tvChannel)
		sendEvent(name: "tvChannelName", value: tvChannelName)

		String pictureMode = mainData["custom.picturemode"].pictureMode.value
		sendEvent(name: "pictureMode",value: pictureMode)
		parseResults << [pictureMode: pictureMode]

		String soundMode = mainData["custom.soundmode"].soundMode.value
		sendEvent(name: "soundMode",value: soundMode)
		parseResults << [soundMode: soundMode]
	}
	logDebug(logData + parseResults)
}

private asyncGet(sendData, passData = "none") {
	if (!stApiKey || stApiKey.trim() == "") {
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]")
	} else {
		logDebug("asyncGet: [parse: ${sendData.parse}, reason: ${passData}]")
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: sendData.path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()],
			timeout: 10]
		try {
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData])
		} catch (error) {
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]")
		}
	}
}

def distResp(resp, data) {
	def resplog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			if (data.reason == "deviceSetup") {
				deviceSetupParse(respData.components.main)
				runIn(1, statusParse, [data: respData.components.main])
			} else {
				statusParse(respData.components.main)
			}
		} catch (err) {
			resplog << [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	} else if (resp.status == 401) {
		stAuthFailed()
	} else {
		resplog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (resplog != [:]) {
		logWarn("distResp: ${resplog}")
	}
}

// ===== Samsung TV SmartThings Capabilities (samsungTvST) =====

command "toggleSoundMode", [[name: "SmartThings Function"]]
command "togglePictureMode", [[name: "SmartThings Function"]]
command "sourceSetST", ["SmartThings Function"]
command "setInputSource", [[name: "Input source (SmartThings)", type: "STRING"]]
attribute "inputSource", "string"
command "setVolume", ["SmartThings Function"]
command "setPictureMode", ["SmartThings Function"]
command "setSoundMode", ["SmartThings Function"]
command "setLevel", ["SmartThings Function"]
attribute "level", "NUMBER"

def deviceRefresh() {
	if (stConnected()) {
		def cmdData = [
			component: "main",
			capability: "refresh",
			command: "refresh",
			arguments: []]
		deviceCommand(cmdData)
	}
}

def poll() {
	if (!stDeviceId || stDeviceId.trim() == "") {
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]")
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "distResp"
			]
		asyncGet(sendData, "statusParse")
	}
}

def setLevel(level) { setVolume(level) }

def setVolume(volume) {
	def cmdData = [
		component: "main",
		capability: "audioVolume",
		command: "setVolume",
		arguments: [volume.toInteger()]]
	deviceCommand(cmdData)
}

def togglePictureMode() {
	//	requires state.pictureModes
	def pictureModes = state.pictureModes
	if (!pictureModes) {
		logWarn("togglePictureMode: no picture modes from SmartThings")
		return
	}
	def totalModes = pictureModes.size()
	def currentMode = device.currentValue("pictureMode")
	def modeNo = pictureModes.indexOf(currentMode)
	def newModeNo = modeNo + 1
	if (newModeNo == totalModes) { newModeNo = 0 }
	def newPictureMode = pictureModes[newModeNo]
	setPictureMode(newPictureMode)
}

def setPictureMode(pictureMode) {
	def cmdData = [
		component: "main",
		capability: "custom.picturemode",
		command: "setPictureMode",
		arguments: [pictureMode]]
	deviceCommand(cmdData)
}

def toggleSoundMode() {
	def soundModes = state.soundModes
	if (!soundModes) {
		logWarn("toggleSoundMode: no sound modes from SmartThings")
		return
	}
	def totalModes = soundModes.size()
	def currentMode = device.currentValue("soundMode")
	def modeNo = soundModes.indexOf(currentMode)
	def newModeNo = modeNo + 1
	if (newModeNo == totalModes) { newModeNo = 0 }
	def soundMode = soundModes[newModeNo]
	setSoundMode(soundMode)
}

def setSoundMode(soundMode) { 
	def cmdData = [
		component: "main",
		capability: "custom.soundmode",
		command: "setSoundMode",
		arguments: [soundMode]]
	deviceCommand(cmdData)
}

def toggleInputSource() { sourceToggle() }

def setInputSource(inputSource) { sourceSetST(inputSource) }
def sourceSetST(inputSource) {
	def cmdData = [
		component: "main",
		capability: state.inputSourceCap ?: "mediaInputSource",
		command: "setInputSource",
		arguments: [inputSource]]
	deviceCommand(cmdData)
}

def setMuteST(muteCmd) {
	def cmdData = [
		component: "main",
		capability: "audioMute",
		command: muteCmd,
		arguments: []]
	deviceCommand(cmdData)
}

def setTvChannel(newChannel) {
	def cmdData = [
		component: "main",
		capability: "tvChannel",
		command: "setTvChannel",
		arguments: [newChannel]]
	deviceCommand(cmdData)
}

// ===== Logging =====

def version() { return "2.4.2" }

def label() { return device.displayName + "-${version()}" }

def logTrace(msg){ log.trace "${label()}: ${msg}" }

def logInfo(msg) { 
	if (infoLog != false) { log.info "${label()}: ${msg}" }		//	unset counts as on
}

def debugLogOff() {
	device.updateSetting("logEnable", [type:"bool", value: false])
	logWarn("debug logging turned off")
}

def logDebug(msg) {
	if (logEnable) { log.debug "${label()}: ${msg}" }
}

def logWarn(msg) { log.warn "${label()}: ${msg}" }

def logError(msg) { log.error "${label()}: ${msg}" }
