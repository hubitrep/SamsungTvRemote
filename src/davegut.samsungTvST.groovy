library (
	name: "samsungTvST",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Samsung TV SmartThings Capabilities",
	category: "utilities",
	documentationLink: ""
)

command "toggleSoundMode", [[name: "SmartThings Function"]]
command "togglePictureMode", [[name: "SmartThings Function"]]
command "sourceSetST", ["SmartThings Function"]
attribute "inputSource", "string"
command "setVolume", ["SmartThings Function"]
command "setPictureMode", ["SmartThings Function"]
command "setSoundMode", ["SmartThings Function"]
command "setLevel", ["SmartThings Function"]
attribute "level", "NUMBER"

def deviceRefresh() {
	if (connectST && stApiKey!= null) {
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
		respData = "[status: FAILED, data: no stDeviceId]"
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
		capability: "mediaInputSource",
		command: "setInputSource",
		arguments: [inputSource]]
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
