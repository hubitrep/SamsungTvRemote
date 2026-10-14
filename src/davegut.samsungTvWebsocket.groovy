library (
	name: "samsungTvWebsocket",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Common Samsung TV Websocket Commands",
	category: "utilities",
	documentationLink: ""
)
//	Modified by hubitrep in 2026. Changes: https://github.com/hubitrep/SamsungTvRemote#changes

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

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
		//	a socket being replaced reports closing after the new connect started
		if (s.phase != "connecting") {
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
				wsReady()
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
