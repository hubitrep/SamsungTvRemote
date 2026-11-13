// Copyright 2026 hubitrep
// SPDX-License-Identifier: Apache-2.0

/*
 Samsung TV SmartThings Link: signs in to SmartThings with your own API Access App and keeps the
 SmartThings key of your Samsung TV Remote devices fresh, so their SmartThings functions keep
 working past the 24 hours a personal access token lasts. The TVs stay the devices they are; the
 app only fills in their SmartThings settings. Needs a SmartThings API plan (Developer Plan).
 */

definition(
    name: "Samsung TV SmartThings Link",
    namespace: "hubitrep",
    author: "hubitrep",
    description: "Keeps the SmartThings key of your Samsung TV Remote devices fresh, through your SmartThings API Access App",
    category: "Convenience",
    oauth: true,
    // Installed when first opened: the sign-in callback needs the app's access token before Done is pressed.
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/hubitrep/SamsungTvRemote/smartthings-link/SamsungTVSmartThingsLink.groovy",
    documentationLink: "https://github.com/hubitrep/SamsungTvRemote/blob/smartthings-link/README.md#smartthings",
    iconUrl: "",
    iconX2Url: "",
    // Refresh tokens are single-use: two refreshes at once would lose the session.
    singleThreaded: true
)

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String CODE_VERSION = "0.1.0"

@Field static final String ST_API = "https://api.smartthings.com"
@Field static final String REDIRECT_URI = "https://cloud.hubitat.com/oauth/stateredirect"
// What the driver calls: the device list, a device's status, and commands.
@Field static final List<String> SCOPES = ["r:devices:*", "x:devices:*"]
// Refresh when the access token has less than this left; the check runs every 3 hours.
@Field static final long REFRESH_MARGIN_MS = 13L * 3600 * 1000
// The driver's SmartThings TV filter (getDeviceListParse), so both list the same TVs.
@Field static final List<String> ST_TV_TYPES = ["Samsung OCF TV", "x.com.st.d.monitor"]

// Decrypted secrets, keyed by app id and name, holding [ciphertext, plaintext]: decrypt() costs ~350 ms a call.
@Field static final ConcurrentHashMap<String, List<String>> secretCache = new ConcurrentHashMap<>()

preferences {
    page(name: "mainPage")
}

mappings {
    path("/oauth/callback") { action: [GET: "oauthCallback"] }
}

def mainPage() {
    checkOAuth()
    storeClientSecret()
    dynamicPage(name: "mainPage", title: "Samsung TV SmartThings Link ${CODE_VERSION}", install: true, uninstall: true) {
        section("1. Your SmartThings API Access App") {
            paragraph "In the SmartThings Developer Center console (needs the SmartThings Developer Plan), register " +
                "an API Access App under Service Integrations with:<br>" +
                "Redirect URI: <b>${REDIRECT_URI}</b><br>Scopes: <b>${SCOPES.join(' ')}</b><br>" +
                "Then copy its client ID and client secret here."
            input "clientId", "text", title: "Client ID", required: false, submitOnChange: true
            input "clientSecret", "password", required: false, submitOnChange: true,
                title: state.clientSecretEnc ? "Client secret (saved; enter it again only to replace it)" : "Client secret"
        }
        section("2. Sign in") {
            if (!clientId || !state.clientSecretEnc) {
                paragraph "Enter the client ID and secret first."
            } else if (!state.accessToken) {
                paragraph "<b>OAuth could not be turned on for this app.</b> In Apps Code, open this app, click OAuth, " +
                    "then Enable OAuth in App, Update, and reopen this page."
            } else {
                href url: authorizeUrl(), style: "external", title: state.refreshEnc ? "Sign in again" : "Sign in to SmartThings",
                    description: "Opens SmartThings in a new tab. Come back here afterwards and reload this page."
            }
            paragraph statusText()
        }
        section("3. TVs") {
            input "tvs", "device.SamsungTVRemote", title: "Samsung TV Remote devices to keep linked", multiple: true,
                required: false, submitOnChange: true
            Map stTvs = state.stTvs ?: [:]
            if (tvs && stTvs.size() > 1) {
                tvs.each { tv ->
                    input "stTv_${tv.id}", "enum", title: "SmartThings TV for ${tv.displayName}", options: stTvs,
                        required: false, submitOnChange: true
                }
                paragraph "Left blank, the driver looks the TV up itself."
            }
        }
        section("Actions") {
            input "btnRefresh", "button", title: "Refresh the key now"
            input "btnSignOut", "button", title: "Sign out and remove the key from the TVs"
        }
        section("Advanced", hideable: true, hidden: true) {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false, submitOnChange: true
            input "stServer", "text", title: "SmartThings API address (leave blank; for testing)", required: false
        }
    }
}

void installed() {
    checkOAuth()
    initialize()
}

void updated() {
    checkOAuth()
    storeClientSecret()
    initialize()
    pushToTvs()
}

void uninstalled() {
    clearTvs()
    secretCache.keySet().removeIf { it.startsWith("${app.id}:") }
}

void initialize() {
    unschedule()
    unsubscribe()
    if (state.refreshEnc) {
        // Every 3 hours at a random minute, so hubs don't all call SmartThings at once.
        Random r = new Random()
        schedule("${r.nextInt(60)} ${r.nextInt(60)} */3 * * ?", "tokenCheck")
        subscribe(location, "systemStart", "hubStarted")
    }
    ["configuredWith", "rejectWarned"].each { state.remove(it) }   // kept by 0.1.0
    if (logEnable) runIn(1800, "debugLogOff")
    // TVs taken out of the list are no longer kept fresh; their key expires within 24 hours.
    List<String> picked = (tvs ?: []).collect { it.id as String }
    List<String> dropped = (state.linked ?: []).findAll { !(it in picked) }
    if (dropped) {
        logInfo "No longer linked: device ${dropped.join(', ')}. Its SmartThings key stops working within 24 hours."
        state.linked = (state.linked ?: []) - dropped
    }
}

void hubStarted(evt) {
    runIn(30, "tokenCheck")
}

void debugLogOff() {
    app.updateSetting("logEnable", [type: "bool", value: false])
    logInfo "Debug logging turned off"
}

void appButtonHandler(String btn) {
    if (btn == "btnRefresh") {
        if (refreshTokens()) pushToTvs()
    } else if (btn == "btnSignOut") {
        signOut()
    }
}

// ===== Sign-in =====

String apiBase() {
    String s = settings.stServer?.trim()
    return s ? s.replaceAll('/+$', '') : ST_API
}

String authorizeUrl() {
    if (!state.oauthNonce) state.oauthNonce = UUID.randomUUID().toString()
    String back = "${getHubUID()}/apps/${app.id}/oauth/callback?access_token=${hubToken()}&nonce=${state.oauthNonce}"
    return "${apiBase()}/oauth/authorize?client_id=${enc(clientId)}&response_type=code" +
        "&scope=${enc(SCOPES.join(' '))}&redirect_uri=${enc(REDIRECT_URI)}&state=${enc(back)}"
}

// The app's own access token; state.accessToken can hold a comma-joined list.
private String hubToken() {
    return (state.accessToken ?: "").toString().split(",").find { it } ?: ""
}

private static String enc(s) {
    return URLEncoder.encode(s?.toString() ?: "", "UTF-8")
}

def oauthCallback() {
    if (!state.oauthNonce || params.nonce != state.oauthNonce) {
        logWarn "Sign-in callback refused: it does not match the last sign-in link. Open the app and sign in again."
        return page("This sign-in link is no longer valid. Open the app and sign in again.", 403)
    }
    if (params.error) {
        logWarn "SmartThings sign-in failed: ${params.error}"
        return page("SmartThings did not grant access (${params.error}). Open the app to try again.", 200)
    }
    if (!params.code) return page("SmartThings sent no authorization code. Open the app to try again.", 400)
    Map r = tokenPost([grant_type: "authorization_code", code: params.code, redirect_uri: REDIRECT_URI])
    if (!r.ok) return page("Sign-in failed: ${r.error}. Open the app to try again.", 200)
    state.remove("oauthNonce")
    logInfo "Signed in to SmartThings"
    initialize()
    listStTvs()
    pushToTvs()
    return page("Signed in to SmartThings. Close this tab and reload the app's page.", 200)
}

private Map page(String msg, int status) {
    String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width'>" +
        "<title>Samsung TV SmartThings Link</title></head><body style='font-family:sans-serif;padding:1em'>" +
        "<p>${msg}</p></body></html>"
    return render(contentType: "text/html", data: html, status: status)
}

// ===== Tokens =====

void tokenCheck() {
    if (!state.refreshEnc) return
    if (state.needsLogin) return
    if (!state.expiresAt || now() > (state.expiresAt as long) - REFRESH_MARGIN_MS) {
        if (!refreshTokens()) return
    }
    pushToTvs()
}

boolean refreshTokens() {
    String rt = secret("refreshEnc")
    if (!rt) {
        logWarn "Not signed in to SmartThings"
        return false
    }
    Map r = tokenPost([grant_type: "refresh_token", refresh_token: rt])
    if (!r.ok && r.status in [400, 401]) {
        // The session is gone (refresh token used, expired or revoked): only a new sign-in brings it back.
        state.needsLogin = true
        logWarn "SmartThings refused to renew the session (HTTP ${r.status}). Open the app and sign in again."
    }
    return r.ok
}

// POST to the token endpoint; on success stores the new token pair. Returns [ok, status, error].
private Map tokenPost(Map form) {
    String secretValue = secret("clientSecretEnc")
    if (!clientId || !secretValue) return [ok: false, status: 0, error: "client ID or secret missing"]
    form.client_id = clientId
    Map params = [
        uri: apiBase(),
        path: "/oauth/token",
        requestContentType: "application/x-www-form-urlencoded",
        headers: [Authorization: "Basic " + "${clientId}:${secretValue}".bytes.encodeBase64().toString(), Accept: "application/json"],
        body: form,
        timeout: 30
    ]
    Map out = [ok: false, status: 0, error: "no reply"]
    try {
        httpPost(params) { resp ->
            Map d = resp.data instanceof Map ? resp.data : [:]
            if (d.access_token && d.refresh_token) {
                storeTokens(d)
                out = [ok: true, status: resp.status]
            } else {
                out = [ok: false, status: resp.status, error: "reply without tokens"]
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        out = [ok: false, status: e.statusCode, error: "HTTP ${e.statusCode} ${errorText(e)}"]
    } catch (Exception e) {
        out = [ok: false, status: 0, error: e.message]
    }
    if (!out.ok) {
        state.lastError = "${form.grant_type == 'refresh_token' ? 'Renewal' : 'Sign-in'} failed at ${nowText()}: ${out.error}"
        logWarn state.lastError
    }
    return out
}

private String errorText(groovyx.net.http.HttpResponseException e) {
    def d = e.response?.data
    if (d instanceof Map) return [d.error, d.error_description].findAll { it }.join(": ")
    return ""
}

private void storeTokens(Map d) {
    // Both values are new on every refresh, and the old access token stops working at once.
    state.stToken = d.access_token
    putSecret("refreshEnc", d.refresh_token as String)
    long life = (d.expires_in ?: 86399) as long
    state.expiresAt = now() + life * 1000
    state.lastRefresh = nowText()
    state.scope = d.scope
    state.remove("needsLogin")
    state.remove("lastError")
    logDebug "Token stored: expires in ${life} s, scope ${d.scope}"
}

void signOut() {
    clearTvs()
    ["stToken", "refreshEnc", "expiresAt", "lastRefresh", "scope", "needsLogin", "oauthNonce", "stTvs", "linked", "configuredWith", "rejectWarned", "notReady", "notStored"].each { state.remove(it) }
    secretCache.remove("${app.id}:refreshEnc".toString())
    unschedule()
    unsubscribe()
    logInfo "Signed out of SmartThings"
}

// ===== SmartThings TVs =====

void listStTvs() {
    Map tvsFound = [:]
    try {
        httpGet([uri: apiBase(), path: "/v1/devices", headers: [Authorization: "Bearer ${state.stToken}", Accept: "application/json"],
                 timeout: 30]) { resp ->
            tvsFound = (resp.data?.items ?: []).findAll { it.type == "OCF" && it.deviceTypeName in ST_TV_TYPES }
                .collectEntries { [(it.deviceId): it.label ?: it.name] }
        }
    } catch (Exception e) {
        logWarn "Could not list the account's TVs: ${e.message}"
        return
    }
    state.stTvs = tvsFound
    logInfo "SmartThings TVs on the account: ${tvsFound.values().join(', ') ?: 'none'}"
}

// ===== The TVs' settings =====

// Fills each picked TV's SmartThings settings. A setting written to an input the driver does not
// declare at that moment is not stored: the driver declares connectST only once the TV's IP is set,
// and stApiKey and stDeviceId only while connectST is on. So TVs without an IP are skipped, and
// connectST goes first.
void pushToTvs() {
    String token = state.stToken
    if (!token || state.needsLogin || !tvs) return
    List<String> linked = (state.linked ?: []).collect { it as String }
    Map stTvs = state.stTvs ?: [:]
    List<String> notReady = []
    tvs.each { tv ->
        String id = tv.id as String
        if (!tv.getSetting("deviceIp")?.toString()?.trim()) {
            notReady << tv.displayName
            linked.remove(id)
            return
        }
        boolean setup = !(id in linked)
        if (tv.getSetting("connectST") != true) {
            tv.updateSetting("connectST", [type: "bool", value: true])
            setup = true
        }
        String pick = settings["stTv_${id}"] ?: (stTvs.size() == 1 && tvs.size() == 1 ? stTvs.keySet().first() : null)
        if (pick && tv.getSetting("stDeviceId") != pick) {
            tv.updateSetting("stDeviceId", [type: "string", value: pick])
            setup = true
        }
        boolean newKey = tv.getSetting("stApiKey") != token
        if (!setup && tv.hasCommand("setSmartThingsKey")) {
            // The driver stores the key, and restarts its SmartThings functions itself if a rejected key had stopped them.
            if (newKey) {
                tv.setSmartThingsKey(token)
                logDebug "${tv.displayName}: new key sent"
            }
        } else if (setup || newKey) {
            // First link, a changed setting, or a driver before 2.4.11: only configure() makes it take them up.
            if (newKey) tv.updateSetting("stApiKey", [type: "string", value: token])
            tv.configure()
            logDebug "${tv.displayName}: SmartThings settings filled, configure() sent"
        }
        if (!(id in linked)) {
            linked << id
            logInfo "${tv.displayName}: linked to SmartThings"
        }
    }
    state.linked = linked
    if (notReady != (state.notReady ?: [])) {
        if (notReady) logWarn "Not linked yet: ${notReady.join(', ')}. Set up the TV in the driver first (its IP address), then open this app and click Done."
        state.notReady = notReady
    }
    // Check in a new execution that the writes were stored: a same-execution read sees them either way.
    if (linked) runIn(5, "verifyTvs")
}

void verifyTvs() {
    String token = state.stToken
    if (!token) return
    List<String> missing = []
    tvs?.each { tv ->
        if ((tv.id as String) in (state.linked ?: []) && tv.getSetting("stApiKey") != token) missing << tv.displayName
    }
    if (missing) {
        logWarn "The SmartThings key did not stick on ${missing.join(', ')}. Open the TV's device page, check that its " +
            "IP address is set and Save, then click Refresh the key now here."
        state.linked = (state.linked ?: []) - (tvs.findAll { it.displayName in missing }.collect { it.id as String })
    }
    state.notStored = missing
}

// Removes the key from the picked TVs and turns their SmartThings link off.
private void clearTvs() {
    tvs?.each { tv ->
        if (tv.getSetting("stApiKey")) tv.updateSetting("stApiKey", [type: "string", value: ""])
        if (tv.getSetting("connectST") == true) tv.updateSetting("connectST", [type: "bool", value: false])
        logInfo "${tv.displayName}: SmartThings key removed"
    }
}

// ===== Status =====

String statusText() {
    if (state.needsLogin) return "<b>Signed out by SmartThings.</b> Sign in again."
    if (!state.refreshEnc) return "Not signed in."
    List lines = ["Signed in. Key renewed ${state.lastRefresh}, valid until ${new Date(state.expiresAt as long).format('yyyy-MM-dd HH:mm', location.timeZone)}."]
    int n = (state.linked ?: []).size()
    lines << "${n} TV${n == 1 ? '' : 's'} linked."
    if (state.notReady) lines << "<b>Not linked yet</b> (set the TV's IP address in the driver first): ${state.notReady.join(', ')}"
    if (state.notStored) lines << "<b>Key not stored on:</b> ${state.notStored.join(', ')}"
    if (state.lastError) lines << "Last error: ${state.lastError}"
    return lines.join("<br>")
}

private String nowText() {
    return new Date().format("yyyy-MM-dd HH:mm", location.timeZone)
}

// ===== Secrets =====

// Moves a newly entered client secret into state, encrypted, and clears the input.
private void storeClientSecret() {
    String s = settings.clientSecret?.trim()
    if (!s) return
    putSecret("clientSecretEnc", s)
    app.removeSetting("clientSecret")
}

private void putSecret(String name, String plain) {
    String c = encrypt(plain)
    state[name] = c
    secretCache.put("${app.id}:${name}".toString(), [c, plain])
}

private String secret(String name) {
    String c = state[name]
    if (!c) return null
    String key = "${app.id}:${name}".toString()
    List<String> hit = secretCache.get(key)
    if (hit && hit[0] == c) return hit[1]
    String plain = decrypt(c)
    if (plain == null) {
        // Encrypted on another hub (restored backup): the key stays with the hub that made it.
        logWarn "A saved secret can't be read on this hub. Enter the client secret again and sign in."
        return null
    }
    secretCache.put(key, [c, plain])
    return plain
}

// ===== OAuth self-enable =====

// Creates the access token, turning OAuth on first when the token can't be created yet.
private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return state.accessToken != null
    } catch (Exception e) {
        logDebug "OAuth is not enabled yet, turning it on"
        if (!autoEnableOAuth()) return false
        try {
            createAccessToken()
            return state.accessToken != null
        } catch (Exception e2) {
            logError "OAuth is on but token creation failed: ${e2.message}"
            return false
        }
    }
}

// Turns OAuth on for this app type through the hub's loopback API, which needs no login.
// The save needs the code's current version number, read first.
private boolean autoEnableOAuth() {
    String base = "http://127.0.0.1:8080"
    String typeId = app.getAppTypeId()?.toString()
    if (!typeId) { logError "Could not find this app's type id"; return false }
    String internalVer = null
    try {
        httpGet([uri: base, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        logError "Could not read the app code version: ${e.message}"
        return false
    }
    if (!internalVer) { logError "Could not determine the app code version"; return false }
    boolean success = false
    try {
        httpPost([uri: base, path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
                  body: [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"], timeout: 20]) { resp ->
            success = true
        }
    } catch (Exception e) {
        logError "Could not enable OAuth: ${e.message}"
    }
    return success
}

// ===== Logging =====

private void logDebug(msg) { if (logEnable) log.debug "${app.label}: ${msg}" }
private void logInfo(msg) { log.info "${app.label}: ${msg}" }
private void logWarn(msg) { log.warn "${app.label}: ${msg}" }
private void logError(msg) { log.error "${app.label}: ${msg}" }
