library (
	name: "samsungTvWebsocket",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Common Samsung TV Websocket Commands",
	category: "utilities",
	documentationLink: ""
)

import groovy.json.JsonOutput

command "webSocketClose"
command "webSocketOpen"
command "close"
attribute "wsStatus", "string"
if (getDataValue("frameTv") == "true") {
	command "artMode"
	attribute "artModeStatus", "string"
}
command "ambientMode"
//	Remote Control Keys (samsungTV-Keys)
command "pause"
command "play"
command "stop"
command "sendKey", ["string"]
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

def getArtModeStatus() {
	if (getDataValue("frameTv") == "true") {
		if (state.artModeWs) {
			def data = [request:"get_artmode_status",
						id: "${getDataValue("uuid")}"]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
		} else {
			refresh()
		}
	}
}

def artModeCmd(data) {
	def cmdData = [method:"ms.channel.emit",
				   params:[data:"${data}",
						   to:"host",
						   event:"art_app_request"]]
	cmdData = JsonOutput.toJson(cmdData)
	sendMessage("frameArt", cmdData)
}

def ambientMode() {
	sendKey("AMBIENT")
	runIn(10, refresh)
}

//	== Remote Commands
def mute() { sendKeyThenRefresh("MUTE") }

def unmute() { mute() }

def volumeUp() { sendKeyThenRefresh("VOLUP") }

def volumeDown() { sendKeyThenRefresh("VOLDOWN") }

def play() { sendKeyThenRefresh("PLAY") }

def pause() { sendKeyThenRefresh("PAUSE") }

def stop() { sendKeyThenRefresh("STOP") }

def exit() { sendKeyThenRefresh("EXIT") }

def Return() { sendKeyThenRefresh("RETURN") }

def fastBack() {
	sendKey("LEFT", "Press")
	pauseExecution(1000)
	sendKey("LEFT", "Release")
}

def fastForward() {
	sendKey("RIGHT", "Press")
	pauseExecution(1000)
	sendKey("RIGHT", "Release")
}

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
	if (connectST) {
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
	if (connectST) { runIn(3, deviceRefresh) }
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

def xxxsendMessage(funct, data) {
	def wsStat = device.currentValue("wsStatus")
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
	if (wsStat != "open" || state.currentFunction != funct) {
		connect(funct)
		pauseExecution(500)
	}
	interfaces.webSocket.sendMessage(data)
	runIn(600, close)
}

def sendMessage(funct, data) {
	def wsStat = device.currentValue("wsStatus")
	Map logData = [method: "sendMessage", wsStat: wsStat, funct: funct, data: data]
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
	if (wsStat == "open" && state.currentFunction == funct) {
		execMessage(data)
		logData << [action: "execMessage"]
	} else {
		if (wsStat == "open") { close() }
		state.wsData = data
		def await = connect(funct)
		//	No idle-close: the platform's 30s ping keeps the socket healthy while the TV
		//	is on, and a ping failure (TV slept) closes it via webSocketStatus.  Socket
		//	lifetime now tracks power, giving onPollParse a liveness oracle.
		logData << [action: "connect"]
	}
	logDebug(logData)
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
	if (getDataValue("tokenSupport") == "true") {
		if (funct == "remote") {
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}"
		} else if (funct == "frameArt") {
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${state.token}"
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true")
		}
	} else {
		if (funct == "remote") {
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}"
		} else if (funct == "frameArt") {
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}"
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false")
		}
	}
	state.currentFunction = funct
	interfaces.webSocket.connect(url, ignoreSSLIssues: true)
	return
}

def close() {
	logDebug("close")
	interfaces.webSocket.close()
	sendEvent(name: "wsStatus", value: "closed")
}

def webSocketStatus(message) {
	def status
	Map logData = [method: "webSocketStatus"]
	if (message == "status: open") {
		status = "open"
		if (state.wsData != "") {
			execMessage(state.wsData)
			state.wsData = ""
			logData << [action: "execMessage"]
		}
		if (state.pendingPowerHold) {
			state.pendingPowerHold = false
			logData << [action: "powerHold"]
			powerHold()
		}
	} else if (message == "status: closing") {
		status = "closed"
		state.currentFunction = "close"
	} else if (message.substring(0,7) == "failure") {
		status = "closed-failure"
		//	Only a remote-socket failure reflects TV power; a frameArt failure (e.g.
		//	art-app channel absent on a non-Frame set) must not poison the oracle.
		if (state.currentFunction == "remote") {
			state.lastWsFailure = now()
		}
		state.currentFunction = "close"
		state.pendingPowerHold = false
		close()
	}
	sendEvent(name: "wsStatus", value: status)
	logData << [wsStatus: status]
	logDebug(logData)
}

def parse(resp) {
	def logData = [method: "parse"]
	try {
		resp = parseJson(resp)
		def event = resp.event
		logData << [EVENT: event]
		switch(event) {
			case "ms.channel.connect":
				def newToken = resp.data.token
				if (newToken != null && newToken != state.token) {
					state.token = newToken
					logData << [TOKEN: "updated"]
				} else {
					logData << [TOKEN: "noChange"]
				}
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
				}
				break
			case "ms.error":
			case "ms.channel.ready":
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
