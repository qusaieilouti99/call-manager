import Foundation
import CallKit
import AVFoundation
import UserNotifications
import UIKit
import OSLog

public class CallEngine {
    static let shared = CallEngine()

    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallEngine")
    private let defaultTimeout: TimeInterval = 60.0

    private var callKitManager: CallKitManager?
    private var audioManager: AudioManager?

    private var activeCalls: [String: CallInfo] = [:]
    private var callMetadata: [String: String] = [:]
    private var currentCallId: String?
    private var canMakeMultipleCalls: Bool = false

    private var eventHandler: ((CallEventType, String) -> Void)?
    private var cachedEvents: [(CallEventType, String)] = []

    private var callEndListeners: [(String) -> Void] = []

    private var isInitialized: Bool = false

    private init() {
        logger.info("🚀 CallEngine singleton created")
    }

    public func initialize() {
        guard !isInitialized else {
            logger.warning("🚀 ⚠️ CallEngine already initialized")
            return
        }

        logger.info("🚀 Initializing CallEngine...")

        callKitManager = CallKitManager(delegate: self)
        audioManager = AudioManager(delegate: self)

        isInitialized = true
        logger.info("🚀 ✅ CallEngine initialized successfully")
    }

    public func setCanMakeMultipleCalls(_ allow: Bool) {
        canMakeMultipleCalls = allow
        logger.info("🚀 canMakeMultipleCalls set to: \(allow)")
    }

    public func getCurrentCallState() -> String {
        logger.debug("🚀 Getting current call state...")
        let callsArray = self.activeCalls.values.map { $0.toJSONObject() }
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: callsArray, options: [])
            let result = String(data: jsonData, encoding: .utf8) ?? "[]"
            logger.debug("🚀 Current call state: \(result)")
            return result
        } catch {
            logger.error("🚀 ❌ Failed to serialize call state: \(error.localizedDescription)")
            return "[]"
        }
    }

    public func reportIncomingCall(
        callId: String,
        callType: String,
        displayName: String,
        pictureUrl: String? = nil,
        metadata: String? = nil
    ) {
        logger.info("📞 Reporting incoming call: callId=\(callId), type=\(callType), name=\(displayName)")

        if let metadata = metadata {
            self.callMetadata[callId] = metadata
            logger.info("📞 Metadata stored for call \(callId)")
        }

        if let incomingCall = self.activeCalls.values.first(where: { $0.state == .incoming }),
           incomingCall.callId != callId {
            logger.warning("📞 ⚠️ Incoming call collision detected. Auto-rejecting new call: \(callId)")
            rejectIncomingCallCollision(callId: callId, reason: "Another call is already incoming")
            return
        }

        if let activeCall = self.activeCalls.values.first(where: { $0.state == .active || $0.state == .held }),
           !canMakeMultipleCalls {
            logger.warning("📞 ⚠️ Active call exists when receiving incoming call. Auto-rejecting: \(callId)")
            rejectIncomingCallCollision(callId: callId, reason: "Another call is already active")
            return
        }

        if !canMakeMultipleCalls {
            for call in self.activeCalls.values where call.state == .active {
                logger.info("📞 Holding existing active call: \(call.callId)")
                holdCallInternal(callId: call.callId, heldBySystem: false)
            }
        }

        let callInfo = CallInfo(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            state: .incoming
        )

        self.activeCalls[callId] = callInfo
        self.currentCallId = callId
        logger.info("📞 Call added to active calls. Total: \(self.activeCalls.count)")

        self.callKitManager?.reportIncomingCall(callInfo: callInfo) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📞 ❌ Failed to report incoming call: \(error.localizedDescription)")
                self.endCallInternal(callId: callId)
            } else {
                self.logger.info("📞 ✅ Successfully reported incoming call for \(callId)")
                self.audioManager?.configureForIncomingCall()
            }
        }
    }

    public func startOutgoingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String? = nil
    ) {
        logger.info("📞 Starting outgoing call: callId=\(callId), type=\(callType), target=\(targetName)")

        if let metadata = metadata {
            self.callMetadata[callId] = metadata
            logger.info("📞 Metadata stored for call \(callId)")
        }

        if !validateOutgoingCallRequest() {
            logger.warning("📞 ⚠️ Rejecting outgoing call - incoming/active call exists")
            emitEvent(.callRejected, data: [
                "callId": callId,
                "reason": "Cannot start outgoing call while incoming or active call exists"
            ])
            return
        }

        if !canMakeMultipleCalls {
            for call in self.activeCalls.values where call.state == .active {
                logger.info("📞 Holding existing active call: \(call.callId)")
                holdCallInternal(callId: call.callId, heldBySystem: false)
            }
        }

        let callInfo = CallInfo(
            callId: callId,
            callType: callType,
            displayName: targetName,
            pictureUrl: nil,
            state: .dialing
        )

        self.activeCalls[callId] = callInfo
        self.currentCallId = callId
        logger.info("📞 Call added to active calls. Total: \(self.activeCalls.count)")

        self.callKitManager?.startOutgoingCall(callInfo: callInfo) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📞 ❌ Failed to start outgoing call: \(error.localizedDescription)")
                self.endCallInternal(callId: callId)
            } else {
                self.logger.info("📞 ✅ Successfully started outgoing call for \(callId)")
                self.audioManager?.configureForOutgoingCall(isVideo: callType == "Video")
            }
        }
    }

    public func startCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String? = nil
    ) {
        logger.info("📞 Starting call (direct active): callId=\(callId), type=\(callType), target=\(targetName)")

        if let metadata = metadata {
            self.callMetadata[callId] = metadata
            logger.info("📞 Metadata stored for call \(callId)")
        }

        if self.activeCalls.keys.contains(callId) {
            logger.warning("📞 ⚠️ Call \(callId) already exists, cannot start again")
            return
        }

        if !canMakeMultipleCalls {
            for call in self.activeCalls.values where call.state == .active {
                logger.info("📞 Holding existing active call: \(call.callId)")
                holdCallInternal(callId: call.callId, heldBySystem: false)
            }
        }

        let callInfo = CallInfo(
            callId: callId,
            callType: callType,
            displayName: targetName,
            pictureUrl: nil,
            state: .active
        )

        self.activeCalls[callId] = callInfo
        self.currentCallId = callId
        logger.info("📞 Call added as ACTIVE. Total: \(self.activeCalls.count)")

        self.audioManager?.configureForActiveCall(isVideo: callType == "Video")
        emitOutgoingCallAnsweredWithMetadata(callId: callId)

        logger.info("📞 ✅ Call \(callId) started as ACTIVE")
    }

    public func callAnsweredFromJS(callId: String) {
        logger.info("📞 Remote party answered: \(callId)")
        coreCallAnswered(callId: callId, isLocalAnswer: false)
    }

    public func answerCall(callId: String) {
        logger.info("📞 Local party answering: \(callId)")
        coreCallAnswered(callId: callId, isLocalAnswer: true)
    }

    private func coreCallAnswered(callId: String, isLocalAnswer: Bool) {
        logger.info("📞 Core call answered: callId=\(callId), isLocalAnswer=\(isLocalAnswer)")

        guard var callInfo = self.activeCalls[callId] else {
            logger.warning("📞 ⚠️ Cannot answer call \(callId) - not found in active calls")
            return
        }

        let previousState = callInfo.state
        callInfo.updateState(.active)
        self.activeCalls[callId] = callInfo
        self.currentCallId = callId

        logger.info("📞 Call state updated: \(previousState.stringValue) → \(CallState.active.stringValue)")

        self.audioManager?.configureForActiveCall(isVideo: callInfo.callType == "Video")

        if !canMakeMultipleCalls {
            for call in self.activeCalls.values where call.callId != callId && call.state == .active {
                logger.info("📞 Holding other active call: \(call.callId)")
                holdCallInternal(callId: call.callId, heldBySystem: false)
            }
        }

        if isLocalAnswer {
            emitCallAnsweredWithMetadata(callId: callId)
        } else {
            emitOutgoingCallAnsweredWithMetadata(callId: callId)
        }

        logger.info("📞 ✅ Call \(callId) successfully answered")
    }

    public func setOnHold(callId: String, onHold: Bool) {
        logger.info("📞 Setting hold state: callId=\(callId), onHold=\(onHold)")

        guard let callInfo = self.activeCalls[callId] else {
            logger.warning("📞 ⚠️ Cannot set hold state for call \(callId) - not found")
            return
        }

        if onHold && callInfo.state == .active {
            holdCallInternal(callId: callId, heldBySystem: false)
        } else if !onHold && callInfo.state == .held {
            unholdCallInternal(callId: callId, resumedBySystem: false)
        } else {
            logger.warning("📞 ⚠️ Invalid hold operation: call \(callId) is in state \(callInfo.state.stringValue)")
        }
    }

    private func holdCallInternal(callId: String, heldBySystem: Bool) {
        logger.info("📞 Holding call internally: callId=\(callId), heldBySystem=\(heldBySystem)")

        guard var callInfo = self.activeCalls[callId], callInfo.state == .active else {
            logger.warning("📞 ⚠️ Cannot hold call \(callId) - not in active state")
            return
        }

        callInfo.updateState(.held)
        callInfo.wasHeldBySystem = heldBySystem
        self.activeCalls[callId] = callInfo

        self.callKitManager?.setCallOnHold(callId: callId, onHold: true)
        emitEvent(.callHeld, data: ["callId": callId])

        logger.info("📞 ✅ Call \(callId) held successfully")
    }

    public func unholdCall(callId: String) {
        unholdCallInternal(callId: callId, resumedBySystem: false)
    }

    private func unholdCallInternal(callId: String, resumedBySystem: Bool) {
        logger.info("📞 Unholding call internally: callId=\(callId), resumedBySystem=\(resumedBySystem)")

        guard var callInfo = self.activeCalls[callId], callInfo.state == .held else {
            logger.warning("📞 ⚠️ Cannot unhold call \(callId) - not in held state")
            return
        }

        callInfo.updateState(.active)
        callInfo.wasHeldBySystem = false
        self.activeCalls[callId] = callInfo

        self.callKitManager?.setCallOnHold(callId: callId, onHold: false)
        emitEvent(.callUnheld, data: ["callId": callId])

        logger.info("📞 ✅ Call \(callId) unheld successfully")
    }

    public func setMuted(callId: String, muted: Bool) {
        logger.info("📞 Setting mute state: callId=\(callId), muted=\(muted)")

        guard self.activeCalls[callId] != nil else {
            logger.warning("📞 ⚠️ Cannot set mute state for call \(callId) - not found")
            return
        }

        self.audioManager?.setMuted(muted)
        let eventType: CallEventType = muted ? .callMuted : .callUnmuted
        emitEvent(eventType, data: ["callId": callId])
        logger.info("📞 ✅ Call \(callId) mute state changed to: \(muted)")
    }

    public func endCall(callId: String) {
        logger.info("📞 Ending call: \(callId)")
        endCallInternal(callId: callId)
    }

    public func endAllCalls() {
        logger.info("📞 Ending all calls. Current active calls: \(self.activeCalls.count)")

        let callIds = Array(self.activeCalls.keys)
        for callId in callIds {
            logger.info("📞 Ending call: \(callId)")
            endCallInternal(callId: callId)
        }

        self.activeCalls.removeAll()
        self.callMetadata.removeAll()
        self.currentCallId = nil

        self.audioManager?.cleanup()
        logger.info("📞 ✅ All calls ended and cleanup completed")
    }

    private func endCallInternal(callId: String) {
        logger.info("📞 Ending call internally: \(callId)")

        guard var callInfo = self.activeCalls[callId] else {
            logger.warning("📞 ⚠️ Call \(callId) not found in active calls")
            return
        }

        let metadata = self.callMetadata.removeValue(forKey: callId)
        callInfo.updateState(.ended)
        self.activeCalls.removeValue(forKey: callId)

        if self.currentCallId == callId {
            self.currentCallId = self.activeCalls.values.first { $0.state != .ended }?.callId
            logger.info("📞 Current call ID updated to: \(self.currentCallId ?? "nil")")
        }

        self.callKitManager?.endCall(callId: callId)

        if self.activeCalls.isEmpty {
            logger.info("📞 No more active calls, cleaning up audio")
            self.audioManager?.cleanup()
        }

        logger.info("📞 Notifying \(self.callEndListeners.count) internal call end listeners")
        for listener in self.callEndListeners {
            DispatchQueue.main.async {
                listener(callId)
            }
        }

        var eventData: [String: Any] = ["callId": callId]
        if let metadata = metadata {
            eventData["metadata"] = metadata
            logger.info("📞 Including metadata in end call event")
        }
        emitEvent(.callEnded, data: eventData)

        logger.info("📞 ✅ Call \(callId) ended successfully. Remaining calls: \(self.activeCalls.count)")
    }

    public func updateDisplayCallInformation(callId: String, callerName: String) {
        logger.info("📲 Updating display info: callId=\(callId), callerName=\(callerName)")

        guard var callInfo = self.activeCalls[callId] else {
            logger.warning("📲 ⚠️ Cannot update display info for call \(callId) - not found")
            return
        }

        callInfo.updateDisplayName(callerName)
        self.activeCalls[callId] = callInfo

        self.callKitManager?.updateCall(callId: callId, displayName: callerName)
        logger.info("📲 ✅ Display info updated successfully")
    }

    public func getAudioDevices() -> AudioRoutesInfo {
        logger.debug("🔊 Getting audio devices...")
        let result = self.audioManager?.getAudioDevices() ?? AudioRoutesInfo(devices: [], currentRoute: "Unknown")
        logger.debug("🔊 Audio devices result: \(result.devices), current: \(result.currentRoute)")
        return result
    }

    public func setAudioRoute(_ route: String) {
        logger.info("🔊 Setting audio route: \(route)")
        self.audioManager?.setAudioRoute(route)
    }

    public func setEventHandler(_ handler: ((CallEventType, String) -> Void)?) {
        logger.info("📡 Setting event handler. Handler present: \(handler != nil)")
        self.eventHandler = handler

        if let handler = handler, !self.cachedEvents.isEmpty {
            logger.info("📡 Emitting \(self.cachedEvents.count) cached events")
            for (type, data) in self.cachedEvents {
                logger.debug("📡 Emitting cached event: \(type)")
                handler(type, data)
            }
            self.cachedEvents.removeAll()
            logger.info("📡 ✅ All cached events emitted and cleared")
        }
    }

    private func emitEvent(_ type: CallEventType, data: [String: Any]) {
        logger.info("📡 Emitting event: \(type)")
        logger.debug("📡 Event data: \(data)")

        do {
            let jsonData = try JSONSerialization.data(withJSONObject: data, options: [])
            let dataString = String(data: jsonData, encoding: .utf8) ?? "{}"

            if let eventHandler = self.eventHandler {
                logger.debug("📡 Calling event handler with data: \(dataString)")
                eventHandler(type, dataString)
            } else {
                logger.info("📡 No event handler, caching event: \(type)")
                self.cachedEvents.append((type, dataString))
            }
        } catch {
            logger.error("📡 ❌ Failed to serialize event data: \(error.localizedDescription)")
        }
    }

    internal func registerCallEndListener(_ listener: @escaping (String) -> Void) {
        self.callEndListeners.append(listener)
        logger.info("🔗 Internal call end listener registered. Total: \(self.callEndListeners.count)")
    }

    internal func unregisterCallEndListener() {
        self.callEndListeners.removeAll()
        logger.info("🔗 All internal call end listeners unregistered")
    }

    public func getActiveCalls() -> [CallInfo] {
        let calls = Array(self.activeCalls.values)
        logger.debug("📊 Getting active calls. Count: \(calls.count)")
        return calls
    }

    public func getCurrentCallId() -> String? {
        logger.debug("📊 Current call ID: \(self.currentCallId ?? "nil")")
        return self.currentCallId
    }

    public func isCallActive() -> Bool {
        let hasActiveCalls = self.activeCalls.values.contains { call in
            call.state == .active || call.state == .incoming || call.state == .dialing || call.state == .held
        }
        logger.debug("📊 Is call active: \(hasActiveCalls)")
        return hasActiveCalls
    }

    private func validateOutgoingCallRequest() -> Bool {
        let hasConflictingCalls = self.activeCalls.values.contains { call in
            call.state == .incoming || call.state == .active
        }
        let isValid = !hasConflictingCalls
        logger.debug("📊 Validate outgoing call request: \(isValid)")
        return isValid
    }

    private func rejectIncomingCallCollision(callId: String, reason: String) {
        logger.warning("📞 ⚠️ Rejecting call collision: \(callId), reason: \(reason)")
        self.callMetadata.removeValue(forKey: callId)
        emitEvent(.callRejected, data: [
            "callId": callId,
            "reason": reason
        ])
    }

    private func emitCallAnsweredWithMetadata(callId: String) {
        logger.info("📡 Emitting call answered event with metadata: \(callId)")

        guard let callInfo = self.activeCalls[callId] else {
            logger.warning("📡 ⚠️ Cannot emit call answered - call not found: \(callId)")
            return
        }

        let metadata = self.callMetadata[callId]

        var eventData: [String: Any] = [
            "callId": callId,
            "callType": callInfo.callType,
            "displayName": callInfo.displayName
        ]

        if let pictureUrl = callInfo.pictureUrl {
            eventData["pictureUrl"] = pictureUrl
        }

        if let metadata = metadata {
            eventData["metadata"] = metadata
            logger.debug("📡 Including metadata in call answered event")
        }

        emitEvent(.callAnswered, data: eventData)
    }

    private func emitOutgoingCallAnsweredWithMetadata(callId: String) {
        logger.info("📡 Emitting outgoing call answered event with metadata: \(callId)")

        guard let callInfo = self.activeCalls[callId] else {
            logger.warning("📡 ⚠️ Cannot emit outgoing call answered - call not found: \(callId)")
            return
        }

        let metadata = self.callMetadata[callId]

        var eventData: [String: Any] = [
            "callId": callId,
            "callType": callInfo.callType,
            "displayName": callInfo.displayName
        ]

        if let pictureUrl = callInfo.pictureUrl {
            eventData["pictureUrl"] = pictureUrl
        }

        if let metadata = metadata {
            eventData["metadata"] = metadata
            logger.debug("📡 Including metadata in outgoing call answered event")
        }

        emitEvent(.outgoingCallAnswered, data: eventData)
    }
}

extension CallEngine: CallKitManagerDelegate {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String) {
        logger.info("📲 CallKit delegate: answer call \(callId)")
        answerCall(callId: callId)
    }

    func callKitManager(_ manager: CallKitManager, didEndCall callId: String) {
        logger.info("📲 CallKit delegate: end call \(callId)")
        endCallInternal(callId: callId)
    }

    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool) {
        logger.info("📲 CallKit delegate: set held \(callId), onHold: \(onHold)")
        if onHold {
            holdCallInternal(callId: callId, heldBySystem: true)
        } else {
            unholdCallInternal(callId: callId, resumedBySystem: true)
        }
    }

    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool) {
        logger.info("📲 CallKit delegate: set muted \(callId), muted: \(muted)")
        setMuted(callId: callId, muted: muted)
    }
}

extension CallEngine: AudioManagerDelegate {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo) {
        logger.info("🔊 Audio manager delegate: route changed to \(routeInfo.currentRoute)")
        let eventData: [String: Any] = [
            "devices": routeInfo.devices,
            "currentRoute": routeInfo.currentRoute
        ]
        emitEvent(.audioRouteChanged, data: eventData)
    }

    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo) {
        logger.info("🔊 Audio manager delegate: devices changed, current: \(routeInfo.currentRoute)")
        let eventData: [String: Any] = [
            "devices": routeInfo.devices,
            "currentRoute": routeInfo.currentRoute
        ]
        emitEvent(.audioDevicesChanged, data: eventData)
    }
}
