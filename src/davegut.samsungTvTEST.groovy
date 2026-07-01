library (
	name: "samsungTvTEST",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Common Samsung TV Websocket Commands",
	category: "utilities",
	documentationLink: ""
)

command "aTest", ["string"]


def aTest() {
	def uri = "http://${deviceIp}:8001/api/v2/applications/samsung.tvplus"
	httpGet(uri) { resp ->
		log.trace resp.properties
	}
}
def getAppStates() {
	
	log.warn here
	Map appData = state.appData
	log.debug appData
}
		



/*{
		Netflix=3201907018807, 	[id:3201907018807, name:Netflix, running:false, version:5.2.59020, visible:false]
		Prime Video=3201910019365,  json:[id:3201910019365, name:Prime Video, running:false, version:2.01.33, visible:false]
		TabloTV=Nuvyyo0002.tablo, id:Nuvyyo0002.tablo, name:TabloTV, running:false, version:1.4.0, visible:false]
		Hulu=3201601007625, [id:3201601007625, name:Hulu, running:false, version:6.0.7, visible:false]
		Sling TV=ZmmGjO6VKO.slingtv, :[id:ZmmGjO6VKO.slingtv, name:Sling TV, running:false, version:6.8.23, visible:false]
		Max=5b8c3eb16b.BeamCTVDev, [id:5b8c3eb16b.BeamCTVDev, name:Max, running:false, version:3.0.0, visible:false]
		Amazon Alexa=AQKO41xyKP.AmazonAlexa, [id:AQKO41xyKP.AmazonAlexa, name:Amazon Alexa, running:false, version:0.20.08060, visible:false]]
		Internet=3201907018784, id:3201907018784, name:Internet, running:true, version:3.1.11260, visible:false]
		YouTube TV=PvWgqxV3Xa.YouTubeTV, [id:PvWgqxV3Xa.YouTubeTV, name:YouTube TV, running:false, version:1.0.81, visible:false]]
		YouTube=9Ur5IzDKqV.TizenYouTube} [id:9Ur5IzDKqV.TizenYouTube, name:YouTube, running:false, version:2.1.498, visible:false]
*/

def xxxaTest(channel = "2015") {
	for (int i = 0; i < channel.length(); i++) {
		log.trace "$i /// $channel{i])"
		sendKey(channel[i])
	}
	return
	sendKey("2")
	sendKey("0")
	sendKey("1")
	sendKey("5")
}





//THIS OLD HOUSE
/*[
	tvChannel:[
		tvChannel:[timestamp:2024-08-15T18:24:10.880Z, value:2015],
		tvChannelName:[timestamp:2024-08-15T18:24:10.880Z, value:This Old House]]]
[
	audioMute:[mute:[timestamp:2024-08-15T18:14:10.802Z, value:unmuted]], 
	audioVolume:[volume:[timestamp:2024-08-15T15:42:11.170Z, unit:%, value:21]], 
	custom.picturemode:[
		pictureMode:[timestamp:2024-08-15T15:40:59.833Z, value:Dynamic], 
		supportedPictureModes:[timestamp:2024-08-15T15:40:59.833Z, value:[Dynamic, FILMMAKER MODE, Movie, Natural, Standard]], 
	custom.soundmode:[
		soundMode:[timestamp:2024-08-15T15:40:59.411Z, value:Amplify], 
		supportedSoundModes:[timestamp:2024-08-15T15:40:59.411Z, value:[Adaptive Sound, Amplify, Standard]], 
		supportedSoundModesMap:[timestamp:2024-08-15T15:40:59.411Z, value:[[id:modeAdaptive, name:Adaptive Sound], [id:modeAmplify, name:Amplify], [id:modeStandard, name:Standard]]]], 
	mediaInputSource:[
		inputSource:[timestamp:2023-08-24T17:59:29.562Z, value:HDMI1],
		supportedInputSources:[timestamp:2024-08-08T10:30:34.513Z, value:[]]], 
	refresh:[:], 
	switch:[switch:[timestamp:2024-08-15T18:07:23.841Z, value:on]], 
	tvChannel:[
		tvChannel:[timestamp:2024-08-15T18:40:50.212Z, value:], 
		tvChannelName:[timestamp:2024-08-15T18:40:50.212Z, value:org.tizen.primevideo]]]*/
















