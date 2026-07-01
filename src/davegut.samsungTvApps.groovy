library (
	name: "samsungTvApps",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Samsung TV Applications",
	category: "utilities",
	documentationLink: ""
)

import groovy.json.JsonSlurper

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
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) }
	if (thisApp != null) {
		def appId = thisApp.value
		appName = thisApp.key
		logData << [appName: appName, appId: appId]
		def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
		try {
			httpPost(uri, "") { resp ->
				logData << [status: resp.statusLine, data: resp.data, success: resp.success]
				if (resp.status == 200) {
					if (connectST) { runIn(10, deviceRefresh) }
					sendEvent(name: "appName", value: appName)
					sendEvent(name: "appId", value: appId)
					logDebug(logData)
				} else {
					logWarn(logData)
				}
			}
			logDebug(logData)
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
		if (connectST) { runIn(5, deviceRefresh) }
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
	if (connectST) { runIn(5, deviceRefresh) }
	sendEvent(name: "appName", value: " ")
	sendEvent(name: "appId", value: " ")
}

def updateAppCodes() {
	Map logData = [method: "updateAppCodes", findAppCodes: findAppCodes]
	if (findAppCodes != "off" && 
		device.currentValue("switch") == "on") {
		device.updateSetting("findAppCodes", [type:"enum", value: "off"])
		if (findAppCodes == "startOver") { 
			state.appData = [:]
			logData << [appData: "reset"]
		}
		unschedule("onPoll")
		state.appsInstalled = []
		def appIds = appIdList()
		def appId = 0
		logData << [codesToCheck: appIds.size(), status: "OK"]
		runIn(5, getAppData)
		logInfo(logData)
	} else if (device.currentValue("switch") == "off") {
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
		runIn(5, setOnPollInterval)
		logData << [status: "Done finding", totalApps: state.appData.size(), appsInstalled: state.appsInstalled]
		state.remove("appsInstalled")
		state.remove("retry")
		logInfo(logData)
	}
}

def parseGetAppData(resp, data) {
	Map logData = [method: "parseGetAppData", data: data, status: resp.status]
	if (resp.status == 200) {
		def respData = new JsonSlurper().parseText(resp.data)
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
		if (state.retry == false) {
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
	return appList
}

def updateAppName(tvName = device.currentValue("tvChannelName")) {
	//	If the tvChannel is blank, the the name may reflect the appId
	//	that is used by the device.  Thanks SmartThings.
	String appId = " "
	String appName = " "
	Map logData = [method: "updateAppName", tvName: tvName]
	//	There are some names that need translation based on known
	//	idiosyncracies with the SmartThings implementation.
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
		if (appId != "") {
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
