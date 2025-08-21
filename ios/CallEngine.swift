import Foundation
import CallKit
import AVFoundation
import OSLog
import WebRTC
import UIKit

/// The central controller for all call-related activities.
/// This class manages call state, interacts with the `CallKitManager`,
/// and commands the `AudioManager`. It is the "brain" of the native module.
class CallEngine {
    static let shared = CallEngine()

    private let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "CallEngine"
    )

    // MARK: - Private Properties
    private var callKitManager: CallKitManager!
    private var audioManager: AudioManager!

    /// The source of truth for all calls, keyed by their lowercased UUID string.
    private var activeCalls: [String: CallInfo] = [:]
    /// A separate dictionary to store metadata, as it can be large and is only needed for events.
    private var callMetadata: [String: String] = [:]
    /// Tracks calls that were answered via the `startCall` method to emit the correct event type.
    private var answeringViaStartCall: Set<String> = []

    private var eventHandler: ((CallEventType, String) -> Void)?
    private var cachedEvents: [(CallEventType, String)] = []
    private var isInitialized = false
    private var manualIdleTimerDisabled: Bool = false

    private init() {
        logger.info("CallEngine singleton created.")
    }

    // MARK: - Public Setup

    func initialize() {
        guard !isInitialized else {
            logger.warning("CallEngine already initialized. Ignoring.")
            return
        }
        logger.info("Initializing CallEngine...")
        callKitManager = CallKitManager(delegate: self)
        audioManager = AudioManager(delegate: self)
        VoIPTokenManager.shared.setupPushKit()
        isInitialized = true
        updateOverallIdleTimerDisabledState()
        logger.info("✅ CallEngine initialized.")
    }

    // MARK: - Public JS Bridge Methods

    func reportIncomingCall(
        callId: String,
        callType: String,
        displayName: String,
        pictureUrl: String?,
        metadata: String?,
        completion: ((Bool) -> Void)?
    ) {
        let callId = callId.lowercased()
        logger.info("JS -> reportIncomingCall: \(callId)")

        guard activeCalls[callId] == nil else {
            logger.warning("Ignoring duplicate incoming call for existing ID: \(callId)")
            completion?(true)
            return
        }

        if let m = metadata { callMetadata[callId] = m }

        let info = CallInfo(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            state: .incoming)
        activeCalls[callId] = info
        emitCallStateChanged()

        callKitManager.reportIncomingCall(callInfo: info) { [weak self] error in
            let success = error == nil
            if !success {
                self?.endCallInternal(callId: callId)
            }
            completion?(success)
        }
    }

    func startOutgoingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?
    ) {
        let callId = callId.lowercased()
        logger.info("JS -> startOutgoingCall: \(callId)")

        guard activeCalls[callId] == nil else {
            logger.warning("Ignoring duplicate outgoing call for existing ID: \(callId)")
            return
        }

        if let m = metadata { callMetadata[callId] = m }

        let info = CallInfo(
            callId: callId,
            callType: callType,
            displayName: targetName,
            pictureUrl: nil,
            state: .dialing)
        activeCalls[callId] = info
        emitCallStateChanged()

        callKitManager.startOutgoingCall(callInfo: info) { [weak self] error in
            if error != nil {
                self?.endCallInternal(callId: callId)
            }
        }
    }

    /// This special function either answers an existing incoming call (emitting `OUTGOING_CALL_ANSWERED`)
    /// or starts a new call and immediately connects it for a "join call" experience.
    func startCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?
    ) {
        let callId = callId.lowercased()
        logger.info("JS -> startCall: \(callId)")

        if let existingCall = activeCalls[callId], existingCall.state == .incoming {
            logger.info("startCall: Answering existing incoming call \(callId).")
            answeringViaStartCall.insert(callId)
            callKitManager.answerCall(callId: callId)
        } else {
            logger.info("startCall: Starting and immediately connecting new call \(callId).")
            if let m = metadata { callMetadata[callId] = m }

            let info = CallInfo(
                callId: callId,
                callType: callType,
                displayName: targetName,
                pictureUrl: nil,
                state: .dialing)
            activeCalls[callId] = info
            emitCallStateChanged()

            callKitManager.startOutgoingCall(callInfo: info) { [weak self] error in
                if let e = error {
                    self?.logger.error("startCall failed to start call: \(e.localizedDescription)")
                    self?.endCallInternal(callId: callId)
                } else {
                    // Immediately report as connected for the "join" experience.
                    self?.callKitManager.reportOutgoingCallConnected(callId: callId)
                }
            }
        }
    }

    /// Called from JS (via the `callAnswered` bridge) to tell CallKit that the remote user has answered the call.
    func connectOutgoingCall(callId: String) {
        let callId = callId.lowercased()
        logger.info("JS -> connectOutgoingCall: \(callId)")
        guard let call = activeCalls[callId], call.state == .connecting else {
            logger
                .warning(
                    "Cannot connect outgoing call, call not found or not in connecting state: \(callId)"
                )
            return
        }
        callKitManager.reportOutgoingCallConnected(callId: callId)
    }

    func endCall(callId: String) {
        let callId = callId.lowercased()
        logger.info("JS -> endCall: \(callId)")
        guard activeCalls[callId] != nil else { return }
        callKitManager.endCall(callId: callId)
    }

    func endAllCalls() {
        logger.info("JS -> endAllCalls")
        activeCalls.keys.forEach { callKitManager.endCall(callId: $0) }
    }

    func setOnHold(callId: String, onHold: Bool) {
        let callId = callId.lowercased()
        logger.info("JS -> setOnHold: \(callId) to \(onHold)")
        guard activeCalls[callId] != nil else { return }
        callKitManager.setCallOnHold(callId: callId, onHold: onHold)
    }

    func setMuted(callId: String, muted: Bool) {
        let callId = callId.lowercased()
        logger.info("JS -> setMuted: \(callId) to \(muted)")
        guard var info = activeCalls[callId] else { return }
        info.isMuted = muted
        activeCalls[callId] = info
        callKitManager.setMuted(callId: callId, muted: muted)
    }

    func updateDisplayCallInformation(callId: String, callerName: String) {
        let callId = callId.lowercased()
        logger.info("JS -> updateDisplay: \(callId)")
        guard activeCalls[callId] != nil else { return }
        callKitManager.updateCall(callId: callId, displayName: callerName)
    }

    func getAudioDevices() -> AudioRoutesInfo {
        return audioManager.getAudioDevices()
    }

    func setAudioRoute(route: String) {
        logger.info("JS -> setAudioRoute: \(route)")
        audioManager.setAudioRoute(route)
    }

    func setEventHandler(_ handler: ((CallEventType, String) -> Void)?) {
        eventHandler = handler
        if let h = handler, !cachedEvents.isEmpty {
            logger.info("Sending \(self.cachedEvents.count) cached events to new JS listener.")
            cachedEvents.forEach { h($0.0, $0.1) }
            cachedEvents.removeAll()
        }
    }

    func hasActiveCalls() -> Bool {
        return !activeCalls.isEmpty
    }

    func setIdleTimerDisabled(shouldDisable: Bool) {
        logger.info("JS -> setIdleTimerDisabled: \(shouldDisable)")
        manualIdleTimerDisabled = shouldDisable
        updateOverallIdleTimerDisabledState()
    }

    // MARK: - Call State Management

    /// Centralized logic for when a call becomes fully active (answered or connected).
    private func callDidBecomeActive(callId: String) {
        guard var callInfo = activeCalls[callId] else { return }

        // Activate the audio session only if this is the first call to become active.
        let otherCallsWereActive = activeCalls.values.contains {
            $0.callId != callId && $0.state == .active
        }
        if !otherCallsWereActive {
            let isVideo = callInfo.callType.lowercased().contains("video")
            audioManager.activate(isVideo: isVideo)
        }

        callInfo.updateState(.active)
        activeCalls[callId] = callInfo
        emitCallStateChanged()
    }

    /// Centralized cleanup logic for any call that ends.
    private func endCallInternal(callId: String) {
        logger.info("Ending call internally: \(callId)")

        // For the CALL_ENDED event, JS only expects the callId.
        let payload = ["callId": callId]

        guard activeCalls.removeValue(forKey: callId) != nil else { return }
        callMetadata.removeValue(forKey: callId)

        // If this was the last active call, deactivate the audio session.
        if !hasActiveCalls() {
            audioManager.deactivate()
        }

        emitEvent(.callEnded, data: payload)
        emitCallStateChanged()
    }

    // MARK: - Event Emission

    /// Emits an event with a rich payload containing the full CallInfo and metadata.
    private func emitRichEvent(for callId: String, type: CallEventType) {
        guard let info = activeCalls[callId] else { return }
        var payload = info.toJSONObject()
        if let metadata = callMetadata[callId] {
            payload["metadata"] = metadata
        }
        emitEvent(type, data: payload)
    }

    /// Emits an event with a specific data payload.
    private func emitEvent(_ type: CallEventType, data: [String: Any]) {
        logger.info("Emitting event: \(type.stringValue)")
        do {
            let json = try JSONSerialization.data(withJSONObject: data, options: [])
            let s = String(data: json, encoding: .utf8) ?? "{}"
            if let h = eventHandler {
                h(type, s)
            } else {
                cachedEvents.append((type, s))
            }
        } catch {
            logger.error("Failed to serialize event payload: \(error.localizedDescription)")
        }
    }

    private func emitCallStateChanged() {
        let isActive = hasActiveCalls()
        emitEvent(.callStateChanged, data: ["isActive": isActive])
        updateOverallIdleTimerDisabledState()
    }

    private func updateOverallIdleTimerDisabledState() {
        let shouldDisable = manualIdleTimerDisabled || hasActiveCalls()

        DispatchQueue.main.async {
            // Only modify UI if app is in foreground/active state
            guard UIApplication.shared.applicationState == .active else {
                self.logger.info("Skipping idle timer update - app not active")
                return
            }
            UIApplication.shared.isIdleTimerDisabled = shouldDisable
        }
    }
}

// MARK: - CallKitManagerDelegate

extension CallEngine: CallKitManagerDelegate {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String) {
        callDidBecomeActive(callId: callId)

        // For a standard answer, the payload is just the pre-parsed metadata object.
        var payload: [String: Any] = [:]
        if let metadataString = callMetadata[callId],
            let data = metadataString.data(using: .utf8),
            let metadataObject = try? JSONSerialization.jsonObject(with: data, options: [])
        {
            payload["metadata"] = metadataObject
        } else {
            payload["metadata"] = [:] // Send empty object if parsing fails
        }

        // If the answer was triggered by `startCall`, emit OUTGOING_CALL_ANSWERED with a full payload.
        if answeringViaStartCall.remove(callId) != nil {
            emitRichEvent(for: callId, type: .outgoingCallAnswered)
        } else {
            emitEvent(.callAnswered, data: payload)
        }
    }

    func callKitManager(_ manager: CallKitManager, didStartOutgoingCall callId: String) {
        guard var info = activeCalls[callId] else { return }

        // The audio session must be activated as soon as CallKit confirms the outgoing call has started.
        let isVideo = info.callType.lowercased().contains("video")
        audioManager.activate(isVideo: isVideo)

        info.updateState(.connecting)
        activeCalls[callId] = info
        manager.reportOutgoingCallStartedConnecting(callId: callId)
    }

    func callKitManager(_ manager: CallKitManager, didEndCall callId: String) {
        endCallInternal(callId: callId)
    }

    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool) {
        guard var info = activeCalls[callId] else { return }
        info.updateState(onHold ? .held : .active)
        activeCalls[callId] = info
        // These events only require the callId.
        emitEvent(onHold ? .callHeld : .callUnheld, data: ["callId": callId])
        emitCallStateChanged()
    }

    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool) {
        guard var info = activeCalls[callId] else { return }
        info.isMuted = muted
        activeCalls[callId] = info
        // These events only require the callId.
        emitEvent(muted ? .callMuted : .callUnmuted, data: ["callId": callId])
    }

    func callKitManagerDidReset(_ manager: CallKitManager) {
        logger.error("CallKit Did Reset. Ending all calls and deactivating audio.")
        audioManager.deactivate()
        activeCalls.keys.forEach { endCallInternal(callId: $0) }
    }

    func callKitManager(_ manager: CallKitManager, didActivateAudioSession session: AVAudioSession) {
        audioManager.callKitDidActivateAudioSession()
    }

    func callKitManager(
        _ manager: CallKitManager,
        didDeactivateAudioSession session: AVAudioSession
    ) {
        logger.info("System confirmed audio session deactivation.")
    }
}

// MARK: - AudioManagerDelegate

extension CallEngine: AudioManagerDelegate {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo) {
        let deviceStrings = routeInfo.devices.map { $0.value }
        emitEvent(
            .audioRouteChanged,
            data: ["devices": deviceStrings, "currentRoute": routeInfo.currentRoute])
    }

    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo) {
        let deviceStrings = routeInfo.devices.map { $0.value }
        emitEvent(
            .audioDevicesChanged,
            data: ["devices": deviceStrings, "currentRoute": routeInfo.currentRoute])
    }
}
