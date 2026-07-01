library (
	name: "Logging",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Common Logging and info gathering Methods",
	category: "utilities",
	documentationLink: ""
)

def nameSpace() { return "davegut" }

def version() { return "2.3.9i" }

def label() {
	if (device) { 
		return device.displayName + "-${version()}"
	} else { 
		return app.getLabel() + "-${version()}"
	}
}

def listAttributes() {
	def attrData = device.getCurrentStates()
	Map attrs = [:]
	attrData.each {
		attrs << ["${it.name}": it.value]
	}
	return attrs
}

def setLogsOff() {
	def logData = [logEnable: logEnable]
	if (logEnable) {
		runIn(1800, debugLogOff)
		logData << [debugLogOff: "scheduled"]
	}
	return logData
}

def logTrace(msg){ log.trace "${label()}: ${msg}" }

def logInfo(msg) { 
	if (infoLog) { log.info "${label()}: ${msg}" }
}

def debugLogOff() {
	if (device) {
		device.updateSetting("logEnable", [type:"bool", value: false])
	} else {
		app.updateSetting("logEnable", false)
	}
	logInfo("debugLogOff")
}

def logDebug(msg) {
	if (logEnable) { log.debug "${label()}: ${msg}" }
}

def logWarn(msg) { log.warn "${label()}: ${msg}" }

def logError(msg) { log.error "${label()}: ${msg}" }
