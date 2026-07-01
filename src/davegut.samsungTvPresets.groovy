library (
	name: "samsungTvPresets",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Samsung TV Preset Implementation",
	category: "utilities",
	documentationLink: ""
)

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
	refresh()
	pauseExecution(2000)
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
	Map logData = [method: "appPresetCreate", presetNumber: presetNumber,
				   appName: appName, appId: appId]
	Map thisPresetData = [type: "application", execute: appName, appId: appId]
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"]
	presetDataUpdate(presetNumber, thisPresetData)
	logInfo(logData)
}

def presetCreateTv(presetNumber, tvChannel, tvChannelName) {
	Map logData = [method: "resetCreateTv", presetNumber: presetNumber, 
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
				appOpenByName(execute)
				sendEvent(name: "appId", value: thisPreset.value.appId)
				sendEvent(name: "appName", value: execute)
				sendEvent(name: "tvChannel", value: " ")
				sendEvent(name: "tvChannelName", value: " ")
				logData << [appName: execute, appId: thisPreset.value.appId]
			} else if (presetType == "tvChannel") {
				//	Close running app the update channel
				if (!connectST && device.currentValue("appId") != " ") {
					appClose()
					pauseExecution(7000)
				}
				channelSet(execute)
				sendEvent(name: "appId", value: " ")
				sendEvent(name: "appName", value: " ")
				sendEvent(name: "tvChannel", value: execute)
				sendEvent(name: "tvChannelName", value: thisPreset.value.tvChannelName)
				logData << [tvChannel: execute, tvChannelName: thisPreset.value.tvChannelName]
			} else {
				logData << [error: "invalid preset type"]
				logWarn(logData)
			}
		}
		runIn(2, updateTitle)
	}
	logDebug(logData)
}

