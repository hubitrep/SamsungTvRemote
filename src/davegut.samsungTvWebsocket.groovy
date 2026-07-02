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

def getInstalledApps() {
	//	Diagnostic: ask the TV for its installed-app list over the remote channel.
	//	2020+ Tizen often refuses this; parse() "ed.installedApp.get" logs any result.
	def data = [method:"ms.channel.emit",
				params:[event:"ed.installedApp.get", to:"host"]]
	sendMessage("remote", JsonOutput.toJson(data).toString())
	logInfo("getInstalledApps: request sent (watch for 'ed.installedApp.get' in logs)")
}

def sendMessage(funct, data) {
	def wsStat = device.currentValue("wsStatus")
	def prevFunct = state.currentFunction
	Map logData = [method: "sendMessage", wsStat: wsStat, funct: funct, data: data]
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${prevFunct}]")
	if (wsStat == "open" && prevFunct == funct) {
		execMessage(data)
		logData << [action: "execMessage"]
	} else {
		if (wsStat == "open") { close() }
		//	queue the payload and connect; webSocketStatus drains the queue in order on
		//	open, so a burst issued before the socket is up is not lost to a single slot.
		if (prevFunct != funct || state.wsQueue == null) { state.wsQueue = [] }
		if (state.wsQueue.size() >= 10) { state.wsQueue.remove(0) }	//	bound the queue
		state.wsQueue << data
		connect(funct)
		//	optional idle-close; default "never" keeps the socket open
		if (settings.wsIdleClose && settings.wsIdleClose != "never") {
			runIn(settings.wsIdleClose.toInteger() * 60, close)
		}
		logData << [action: "connect", queued: state.wsQueue.size()]
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
		if (state.wsQueue) {
			state.wsQueue.each { execMessage(it) }
			logData << [drained: state.wsQueue.size()]
			state.wsQueue = []
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
		//	only a remote-socket failure indicates TV power state
		if (state.currentFunction == "remote") {
			state.lastWsFailure = now()
		}
		state.currentFunction = "close"
		state.pendingPowerHold = false
		state.wsQueue = []		//	undeliverable; don't fire stale keys on reconnect
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
				logData << [TOKEN: "rejected, reset - accept the prompt on the TV"]
				logWarn(logData)
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
