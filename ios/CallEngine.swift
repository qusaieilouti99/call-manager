import Foundation
import CallKit
import AVFoundation
import OSLog
import WebRTC

class CallEngine {
    static let shared = CallEngine()

    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallManager")

    private var callKitManager: CallKitManager!
    private var audioManager: AudioManager!
    private var activeCalls: [String: CallInfo] = [:]
    private var callMetadata: [String: String] = [:]
    private var currentCallId: String?
    private var canMakeMultipleCalls = false

    private var eventHandler: ((CallEventType, String) -> Void)?
    private var cachedEvents: [(CallEventType, String)] = []

    private var callEndListeners: [(String) -> Void] = []
    private var isInitialized = false

    // Track video calls that need speaker activation
    private var videoCallsNeedingSpeaker: Set<String> = []

    private init() {
        logger.info("CallEngine singleton created")
    }

    func initialize() {
        guard !isInitialized else {
            logger.warning("CallEngine already initialized")
            return
        }
        logger.info("Initializing CallEngine")
        callKitManager = CallKitManager(delegate: self)
        audioManager = AudioManager(delegate: self)
        VoIPTokenManager.shared.setupPushKit()
        isInitialized = true
        logger.info("CallEngine initialized")
    }

    func setCanMakeMultipleCalls(_ allow: Bool) {
        canMakeMultipleCalls = allow
        logger.info("canMakeMultipleCalls = \(allow)")
    }

    func getCurrentCallState() -> String {
        logger.debug("getCurrentCallState")
        let array = activeCalls.values.map { $0.toJSONObject() }
        do {
            let data = try JSONSerialization.data(withJSONObject: array, options: .prettyPrinted)
            let json = String(data: data, encoding: .utf8) ?? "[]"
            return json
        } catch {
            logger.error("serialize state failed: \(error.localizedDescription)")
            return "[]"
        }
    }

    // MARK: Incoming

    func reportIncomingCall(callId: String,
                           callType: String,
                           displayName: String,
                           pictureUrl: String? = nil,
                           metadata: String? = nil)
    {
        logger.info("reportIncomingCall: \(callId), \(displayName)")
        if let m = metadata {
            callMetadata[callId] = m
            logger.info("metadata cached for \(callId)")
        }

        // collision
        if let inc = activeCalls.values.first(where: { $0.state == .incoming }),
           inc.callId != callId
        {
            logger.warning("incoming collision → reject \(callId)")
            emitEvent(.callRejected, data: ["callId": callId, "reason": "Another incoming exists"])
            return
        }

        if !canMakeMultipleCalls &&
            activeCalls.values.contains(where: { $0.state == .active || $0.state == .held })
        {
            logger.warning("active exists → reject incoming \(callId)")
            emitEvent(.callRejected, data: ["callId": callId, "reason": "Another call is active"])
            return
        }

        if canMakeMultipleCalls {
            activeCalls.values
                .filter { $0.state == .active }
                .forEach { call in
                    logger.info("holding existing \(call.callId)")
                    callKitManager.setCallOnHold(callId: call.callId, onHold: true)
                }
        }

        var info = CallInfo(callId: callId,
                            callType: callType,
                            displayName: displayName,
                            pictureUrl: pictureUrl,
                            state: .incoming)
        activeCalls[callId] = info
        currentCallId = callId

        callKitManager.reportIncomingCall(callInfo: info) { error in
            if let e = error {
                self.logger.error("reportIncoming failed: \(e.localizedDescription)")
                self.endCallInternal(callId: callId)
            }
        }
    }

    // MARK: Outgoing

    func startOutgoingCall(callId: String,
                           callType: String,
                           targetName: String,
                           metadata: String? = nil)
    {
        logger.info("startOutgoingCall: \(callId), \(targetName)")
        if let m = metadata {
            callMetadata[callId] = m
            logger.info("metadata cached for \(callId)")
        }

        guard validateOutgoingCallRequest() else {
            logger.warning("rejecting outgoing \(callId) conflicts")
            emitEvent(.callRejected, data: ["callId": callId, "reason": "Conflict with existing call"])
            return
        }

        if canMakeMultipleCalls {
            activeCalls.values
                .filter { $0.state == .active }
                .forEach { call in
                    logger.info("holding existing \(call.callId)")
                    callKitManager.setCallOnHold(callId: call.callId, onHold: true)
                }
        }

        var info = CallInfo(callId: callId,
                            callType: callType,
                            displayName: targetName,
                            pictureUrl: nil,
                            state: .dialing)
        activeCalls[callId] = info
        currentCallId = callId

        // Mark video calls for speaker activation
        if callType.lowercased().contains("video") {
            logger.info("📞 Marking video outgoing call for speaker activation")
            videoCallsNeedingSpeaker.insert(callId)
        }

        callKitManager.startOutgoingCall(callInfo: info) { error in
            if let e = error {
                self.logger.error("startOutgoing failed: \(e.localizedDescription)")
                self.videoCallsNeedingSpeaker.remove(callId)
                self.endCallInternal(callId: callId)
            }
        }
    }

    // MARK: Direct Active (Join Ongoing Call)

    func startCall(callId: String,
                   callType: String,
                   targetName: String,
                   metadata: String? = nil)
    {
        logger.info("startCall (join ongoing): \(callId), type=\(callType)")
        if let m = metadata {
            callMetadata[callId] = m
            logger.info("metadata cached for \(callId)")
        }

        guard activeCalls[callId] == nil else {
            logger.warning("call \(callId) already exists")
            return
        }

        guard validateOutgoingCallRequest() else {
            logger.warning("rejecting join call \(callId) - conflicts")
            emitEvent(.callRejected, data: ["callId": callId, "reason": "Conflict with existing call"])
            return
        }

        if canMakeMultipleCalls {
            activeCalls.values
                .filter { $0.state == .active }
                .forEach { call in
                    logger.info("holding existing \(call.callId)")
                    callKitManager.setCallOnHold(callId: call.callId, onHold: true)
                }
        }

        var info = CallInfo(callId: callId,
                            callType: callType,
                            displayName: targetName,
                            pictureUrl: nil,
                            state: .dialing)
        activeCalls[callId] = info
        currentCallId = callId

        // Mark video calls for speaker activation
        if callType.lowercased().contains("video") {
            logger.info("📞 Marking video startCall for speaker activation")
            videoCallsNeedingSpeaker.insert(callId)
        }

        callKitManager.startOutgoingCall(callInfo: info) { [weak self] error in
            if let e = error {
                self?.logger.error("startCall failed: \(e.localizedDescription)")
                self?.videoCallsNeedingSpeaker.remove(callId)
                self?.endCallInternal(callId: callId)
                return
            }

            // For startCall (join ongoing), report connected immediately
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                if let uuid = UUID(uuidString: callId) {
                    self?.callKitManager.provider.reportOutgoingCall(with: uuid, connectedAt: Date())
                    self?.logger.info("startCall: reported as connected immediately")
                }
            }
        }
    }

    // MARK: JS Actions

    func answerCall(callId: String) {
        logger.info("answerCall (JS): \(callId)")
        callKitManager.answerCall(callId: callId)
    }

    func setOnHold(callId: String, onHold: Bool) {
        logger.info("setOnHold (JS): \(callId), onHold=\(onHold)")
        callKitManager.setCallOnHold(callId: callId, onHold: onHold)
    }

    func setMuted(callId: String, muted: Bool) {
        logger.info("setMuted (JS): \(callId), muted=\(muted)")
        callKitManager.setMuted(callId: callId, muted: muted)
    }

    func endCall(callId: String) {
        logger.info("endCall (JS): \(callId)")
        videoCallsNeedingSpeaker.remove(callId)
        callKitManager.endCall(callId: callId)
    }

    func endAllCalls() {
        logger.info("endAllCalls (JS)")
        videoCallsNeedingSpeaker.removeAll()
        activeCalls.keys.forEach { endCall(callId: $0) }
    }

    func updateDisplayCallInformation(callId: String, callerName: String) {
        logger.info("updateDisplayCallInformation: \(callId), \(callerName)")
        guard activeCalls[callId] != nil else { return }
        callKitManager.updateCall(callId: callId, displayName: callerName)
    }


    // MARK: Internal
    private func coreCallAnswered(callId: String, isLocalAnswer: Bool) {
        logger.info("📞 Core call answered: callId=\(callId), isLocalAnswer=\(isLocalAnswer)")

        guard var callInfo = self.activeCalls[callId] else {
            logger.warning("📞 ⚠️ Cannot answer call \(callId) – not found in activeCalls")
            return
        }

        let previousState = callInfo.state
        callInfo.updateState(.active)
        self.activeCalls[callId] = callInfo
        self.currentCallId = callId
        logger.info("📞 Call state updated: \(previousState.stringValue) → \(CallState.active.stringValue)")

        // Mark video calls for speaker activation on answer
        if callInfo.callType.lowercased().contains("video") {
            logger.info("📞 Video call answered - marking for speaker activation")
            videoCallsNeedingSpeaker.insert(callId)
            // Set speaker after a delay to ensure audio session is stable
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                if self.videoCallsNeedingSpeaker.contains(callId) {
                    self.audioManager.setAudioRoute("Speaker", force: true)
                    self.videoCallsNeedingSpeaker.remove(callId)
                }
            }
        }

        if isLocalAnswer {
            if previousState == .incoming {
                logger.info("📞 Emitting CALL_ANSWERED for incoming call \(callId)")
                emitCallAnsweredWithMetadata(callId: callId)
            } else {
                logger.info("📞 Skipping CALL_ANSWERED for non‐incoming call \(callId)")
            }
        } else {
            logger.info("📞 Emitting OUTGOING_CALL_ANSWERED for call \(callId)")
            emitOutgoingCallAnsweredWithMetadata(callId: callId)
        }
    }

    private func endCallInternal(callId: String) {
        logger.info("endCallInternal: \(callId)")
        videoCallsNeedingSpeaker.remove(callId)
        guard var info = activeCalls[callId] else { return }
        info.updateState(.ended)
        activeCalls.removeValue(forKey: callId)
        if currentCallId == callId {
            currentCallId = activeCalls.values
                .first(where: { $0.state != .ended })?.callId
        }
        if activeCalls.isEmpty {
            // Reset audio session to default after all calls end
            let audioSession = AVAudioSession.sharedInstance()
            do {
                try audioSession.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            } catch {
                logger.error("Failed to reset audio session: \(error.localizedDescription)")
            }
        }
        callEndListeners.forEach { listener in
            DispatchQueue.main.async { listener(callId) }
        }
        var data: [String: Any] = ["callId": callId]
        if let m = callMetadata.removeValue(forKey: callId) {
            data["metadata"] = m
        }
        emitEvent(.callEnded, data: data)
    }

    // MARK: Audio

    func getAudioDevices() -> AudioRoutesInfo {
        audioManager.getAudioDevices()
    }

    func setAudioRoute(route: String) {
        audioManager.setAudioRoute(route)
    }

    // MARK: Eventing

    func setEventHandler(_ handler: ((CallEventType, String) -> Void)?) {
        eventHandler = handler
        if let h = handler {
            cachedEvents.forEach { h($0.0, $0.1) }
            cachedEvents.removeAll()
        }
    }

    private func emitEvent(_ type: CallEventType, data: [String: Any]) {
        logger.info("emitEvent: \(type.stringValue)")
        do {
            let json = try JSONSerialization.data(withJSONObject: data, options: .prettyPrinted)
            let s = String(data: json, encoding: .utf8) ?? "{}"
            if let h = eventHandler {
                h(type, s)
            } else {
                cachedEvents.append((type, s))
            }
        } catch {
            logger.error("serialize event failed: \(error.localizedDescription)")
        }
    }

    private func emitCallAnsweredWithMetadata(callId: String) {
        guard let info = activeCalls[callId] else { return }
        var data: [String: Any] = [
            "callId": info.callId,
            "callType": info.callType,
            "displayName": info.displayName
        ]
        if let pic = info.pictureUrl { data["pictureUrl"] = pic }
        if let m = callMetadata[callId] { data["metadata"] = m }
        emitEvent(.callAnswered, data: data)
    }

    private func emitOutgoingCallAnsweredWithMetadata(callId: String) {
        guard let info = activeCalls[callId] else { return }
        var data: [String: Any] = [
            "callId": info.callId,
            "callType": info.callType,
            "displayName": info.displayName
        ]
        if let pic = info.pictureUrl { data["pictureUrl"] = pic }
        if let m = callMetadata[callId] { data["metadata"] = m }
        emitEvent(.outgoingCallAnswered, data: data)
    }

    // Updated to check all relevant call states, not just active
    func hasActiveCalls() -> Bool {
        logger.debug("hasActiveCalls check")
        let hasRelevantCalls = !activeCalls.isEmpty && activeCalls.values.contains { call in
            // Include all states except ended - this covers incoming, dialing, active, held
            call.state != .ended
        }
        logger.debug("hasActiveCalls: \(hasRelevantCalls), total: \(self.activeCalls.count)")
        let callStates = activeCalls.values.map { "\($0.callId): \($0.state.stringValue)" }
        logger.debug("Call states: \(callStates)")
        return hasRelevantCalls
    }

    // MARK: Helpers

    private func validateOutgoingCallRequest() -> Bool {
        !activeCalls.values.contains { $0.state == .incoming || (!canMakeMultipleCalls && ($0.state == .active || $0.state == .held)) }
    }
}

// MARK: CallKitManagerDelegate

extension CallEngine: CallKitManagerDelegate {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String) {
        coreCallAnswered(callId: callId, isLocalAnswer: true)
    }

    func callKitManager(_ manager: CallKitManager, didEndCall callId: String) {
        endCallInternal(callId: callId)
    }

    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool) {
        logger.info("didSetHeld: \(callId), onHold=\(onHold)")
        guard var info = activeCalls[callId] else { return }

        if onHold {
            info.updateState(.held)
            info.wasHeldBySystem = true
            emitEvent(.callHeld, data: ["callId": callId])
        } else {
            info.updateState(.active)
            info.wasHeldBySystem = false
            currentCallId = callId
            emitEvent(.callUnheld, data: ["callId": callId])
        }
        activeCalls[callId] = info
    }

    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool) {
        logger.info("didSetMuted: \(callId), muted=\(muted)")
        guard var info = activeCalls[callId] else { return }
        info.isManuallySilenced = muted
        activeCalls[callId] = info
        let ev: CallEventType = muted ? .callMuted : .callUnmuted
        emitEvent(ev, data: ["callId": callId])
    }

    func callKitManager(_ manager: CallKitManager, didStartOutgoingCall callId: String) {
        logger.info("didStartOutgoingCall: \(callId)")
        if var info = activeCalls[callId], info.state == .dialing {
            info.updateState(.active)
            activeCalls[callId] = info

            // Handle video calls needing speaker activation
            if videoCallsNeedingSpeaker.contains(callId) {
                logger.info("📞 Video call connected - setting speaker after delay")
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    if self.videoCallsNeedingSpeaker.contains(callId) {
                        self.audioManager.setAudioRoute("Speaker", force: true)
                        self.videoCallsNeedingSpeaker.remove(callId)
                    }
                }
            }

            emitOutgoingCallAnsweredWithMetadata(callId: callId)
        }
    }

    func callKitManager(_ manager: CallKitManager, didActivateAudioSession session: AVAudioSession) {
        logger.info("CallKit didActivate audioSession")
        audioManager.callKitDidActivateAudioSession(session)

        // Set speaker ONCE for video calls, after WebRTC is enabled and session is active
        if let callId = currentCallId,
           let info = activeCalls[callId],
           info.callType.lowercased().contains("video")
        {
            logger.info("Setting speaker for video call \(callId) after activation")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                self.audioManager.setAudioRoute("Speaker", force: true)
            }
        }
    }
    func callKitManager(_ manager: CallKitManager, didDeactivateAudioSession session: AVAudioSession) {
        logger.info("CallKit didDeactivate audioSession")
        audioManager.callKitDidDeactivateAudioSession(session)
    }
}

// MARK: AudioManagerDelegate

extension CallEngine: AudioManagerDelegate {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo) {
        let deviceStrings = routeInfo.devices.map { $0.value }
        emitEvent(.audioRouteChanged, data: ["devices": deviceStrings, "currentRoute": routeInfo.currentRoute])
    }

    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo) {
        let deviceStrings = routeInfo.devices.map { $0.value }
        emitEvent(.audioDevicesChanged, data: ["devices": deviceStrings, "currentRoute": routeInfo.currentRoute])
    }

    func audioManagerDidActivateAudioSession(_ manager: AudioManager) {
        logger.info("AudioManager did activate audio session")
    }

    func audioManagerDidDeactivateAudioSession(_ manager: AudioManager) {
        logger.info("AudioManager did deactivate audio session")
    }
}
