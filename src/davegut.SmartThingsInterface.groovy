library (
	name: "SmartThingsInterface",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Samsung TV SmartThings Capabilities",
	category: "utilities",
	documentationLink: ""
)

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

def stUpdate() {
	def stData = [:]
	if (connectST) {
		stData << [connectST: "true"]
		stData << [connectST: connectST]
		if (!stApiKey || stApiKey == "") {
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n")
			stData << [status: "ERROR", date: "no stApiKey"]
		} else if (!stDeviceId || stDeviceId == "") {
			getDeviceList()
			logWarn("\n\n\t\t<b>Enter the deviceId from the log List and Save Preferences</b>\n\n")
			stData << [status: "ERROR", date: "no stDeviceId"]
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
		respData = "[status: FAILED, data: no stDeviceId]"
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]")
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
	
	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value
	state.pictureModes = pictureModes
	setupData << [pictureModes: pictureModes]
	
	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value
	state.soundModes = soundModes
	setupData << [soundModes: soundModes]
	
	logInfo("deviceSetupParse: ${setupData}")
}

def deviceCommand(cmdData) {
	logDebug("deviceCommand: $cmdData")
	def respData = [:]
	if (!stDeviceId || stDeviceId.trim() == "") {
		respData << [status: "FAILED", data: "no stDeviceId"]
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/commands",
			cmdData: cmdData
		]
		respData = syncPost(sendData)
	}
	if (respData.status == "OK") {
		if (cmdData.capability && cmdData.capability != "refresh") {
			deviceRefresh()
		} else {
			poll()
		}
	}else {
		logWarn("deviceCommand: [status: ${respData.status}, data: ${respData}]")
		if (respData.toString().contains("Conflict")) {
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>")
		}
	}
}

def statusParse(mainData) {
	Map logData = [method: "statusParse"]
	if (stTestData) {
		device.updateSetting("stTestData", [type:"bool", value: false])
		Map testData = [stTestData: mainData]
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

		String inputSource = mainData.mediaInputSource.inputSource.value
		sendEvent(name: "inputSource", value: inputSource)		
		parseResults << [inputSource: inputSource]

		String tvChannel = mainData.tvChannel.tvChannel.value
		if (tvChannel == null) { tvChannel = " " }
		String tvChannelName = mainData.tvChannel.tvChannelName.value
		parseResults << [tvChannel: tvChannel, tvChannelName: tvChannelName]
		if (tvChannel == " " && tvChannelName != device.currentValue("tvChannelName")) {
			//	tvChannel indicates app, tvChannelName is thrn spp code (ST Version)
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
	logDebug(logData)
}

private asyncGet(sendData, passData = "none") {
	if (!stApiKey || stApiKey.trim() == "") {
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]")
	} else {
		logDebug("asyncGet: ${sendData}, ${passData}")
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: sendData.path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]]
		try {
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData])
		} catch (error) {
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]")
		}
	}
}

private syncGet(path){
	def respData = [:]
	if (!stApiKey || stApiKey.trim() == "") {
		respData << [status: "FAILED",
					 errorMsg: "No stApiKey"]
	} else {
		logDebug("syncGet: ${sendData}")
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]
		]
		try {
			httpGet(sendCmdParams) {resp ->
				if (resp.status == 200 && resp.data != null) {
					respData << [status: "OK", results: resp.data]
				} else {
					respData << [status: "FAILED",
								 httpCode: resp.status,
								 errorMsg: resp.errorMessage]
				}
			}
		} catch (error) {
			respData << [status: "FAILED",
						 errorMsg: error]
		}
	}
	return respData
}

private syncPost(sendData){
	def respData = [:]
	if (!stApiKey || stApiKey.trim() == "") {
		respData << [status: "FAILED",
					 errorMsg: "No stApiKey"]
	} else {
		logDebug("syncPost: ${sendData}")
		def cmdBody = [commands: [sendData.cmdData]]
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: sendData.path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()],
			body : new groovy.json.JsonBuilder(cmdBody).toString()
		]
		try {
			httpPost(sendCmdParams) {resp ->
				if (resp.status == 200 && resp.data != null) {
					respData << [status: "OK", results: resp.data.results]
				} else {
					respData << [status: "FAILED",
								 httpCode: resp.status,
								 errorMsg: resp.errorMessage]
				}
			}
		} catch (error) {
			respData << [status: "FAILED",
						 errorMsg: error]
		}
	}
	return respData
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
	} else {
		resplog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (resplog != [:]) {
		logWarn("distResp: ${resplog}")
	}
}
