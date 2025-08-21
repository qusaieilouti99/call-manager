import Foundation
import CallKit
import AVFoundation
import OSLog

/// A protocol to delegate CallKit actions to a higher-level manager (the `CallEngine`).
/// This keeps the `CallKitManager` clean and focused only on framework interactions.
protocol CallKitManagerDelegate: AnyObject {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String)
    func callKitManager(_ manager: CallKitManager, didEndCall callId: String)
    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool)
    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool)
    func callKitManager(_ manager: CallKitManager, didStartOutgoingCall callId: String)
    func callKitManager(_ manager: CallKitManager, didActivateAudioSession session: AVAudioSession)
    func callKitManager(_ manager: CallKitManager, didDeactivateAudioSession session: AVAudioSession)
    func callKitManagerDidReset(_ manager: CallKitManager)
}

/// A thin wrapper around the CallKit `CXProvider` and `CXCallController`.
/// Its sole responsibility is to communicate with the CallKit framework. It holds no state of its own,
/// delegating all events immediately to the `CallEngine`.
class CallKitManager: NSObject {
    private let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "CallKitManager"
    )
    let provider: CXProvider
    let callController = CXCallController()
    weak var delegate: CallKitManagerDelegate?

    init(delegate: CallKitManagerDelegate) {
        self.delegate = delegate
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallsPerCallGroup = 3
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic] // .generic is the most flexible handle type.
        config.includesCallsInRecents = true

        provider = CXProvider(configuration: config)
        super.init()
        // Set the delegate to receive callbacks from the CallKit system on the main thread.
        provider.setDelegate(self, queue: nil)
        logger.info("CallKitManager initialized and provider delegate set.")
    }

    // MARK: - Public Actions (Commands to CallKit)

    /// Tells CallKit to display the native incoming call UI.
    func reportIncomingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        guard let uuid = UUID(uuidString: callInfo.callId) else {
            completion(NSError(domain: "CallKitManager", code: -1, userInfo: nil))
            return
        }
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callInfo.displayName)
        update.localizedCallerName = callInfo.displayName
        update.hasVideo = callInfo.callType.lowercased().contains("video")
        update.supportsHolding = true

        logger.info("Reporting new incoming call to system: \(callInfo.callId)")
        provider.reportNewIncomingCall(with: uuid, update: update, completion: completion)
    }

    /// Tells CallKit to start an outgoing call. This will trigger the native "Calling..." UI.
    func startOutgoingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        guard let uuid = UUID(uuidString: callInfo.callId) else {
            completion(NSError(domain: "CallKitManager", code: -1, userInfo: nil))
            return
        }
        let handle = CXHandle(type: .generic, value: callInfo.displayName)
        let action = CXStartCallAction(call: uuid, handle: handle)
        action.isVideo = callInfo.callType.lowercased().contains("video")
        let transaction = CXTransaction(action: action)
        requestTransaction(transaction, completion: completion)
    }

    /// Programmatically answers a call.
    func answerCall(callId: String) {
        guard let uuid = UUID(uuidString: callId) else { return }
        let action = CXAnswerCallAction(call: uuid)
        requestTransaction(CXTransaction(action: action))
    }

    /// Programmatically ends a call.
    func endCall(callId: String) {
        guard let uuid = UUID(uuidString: callId) else { return }
        let action = CXEndCallAction(call: uuid)
        requestTransaction(CXTransaction(action: action))
    }

    /// Programmatically places a call on hold or takes it off hold.
    func setCallOnHold(callId: String, onHold: Bool) {
        guard let uuid = UUID(uuidString: callId) else { return }
        let action = CXSetHeldCallAction(call: uuid, onHold: onHold)
        requestTransaction(CXTransaction(action: action))
    }

    /// Programmatically mutes or unmutes a call.
    func setMuted(callId: String, muted: Bool) {
        guard let uuid = UUID(uuidString: callId) else { return }
        let action = CXSetMutedCallAction(call: uuid, muted: muted)
        requestTransaction(CXTransaction(action: action))
    }

    /// Updates the display name for an ongoing call.
    func updateCall(callId: String, displayName: String) {
        guard let uuid = UUID(uuidString: callId) else { return }
        let update = CXCallUpdate()
        update.localizedCallerName = displayName
        provider.reportCall(with: uuid, updated: update)
    }

    /// Informs CallKit that the outgoing call has started connecting (e.g., ringing).
    func reportOutgoingCallStartedConnecting(callId: String) {
        guard let uuid = UUID(uuidString: callId) else { return }
        provider.reportOutgoingCall(with: uuid, startedConnectingAt: nil)
    }

    /// Informs CallKit that the outgoing call is now fully connected.
    func reportOutgoingCallConnected(callId: String) {
        guard let uuid = UUID(uuidString: callId) else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: nil)
    }

    /// Centralized method to request a transaction from the system.
    private func requestTransaction(
        _ transaction: CXTransaction,
        completion: ((Error?) -> Void)? = nil
    ) {
        callController.request(transaction) { error in
            if let e = error {
                self.logger
                    .error(
                        "Transaction failed: \(transaction.actions.first?.description ?? "Unknown") - Error: \(e.localizedDescription)"
                    )
            }
            completion?(error)
        }
    }
}

// MARK: - CXProviderDelegate

extension CallKitManager: CXProviderDelegate {
    /// Called when the provider is reset by the system. All calls are now invalid.
    func providerDidReset(_ provider: CXProvider) {
        logger.error("CallKit provider was reset.")
        delegate?.callKitManagerDidReset(self)
    }

    /// The user answered the call from the native UI.
    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        delegate?.callKitManager(self, didAnswerCall: action.callUUID.uuidString.lowercased())
        action.fulfill()
    }

    /// The user ended the call from the native UI.
    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        delegate?.callKitManager(self, didEndCall: action.callUUID.uuidString.lowercased())
        action.fulfill()
    }

    /// The user toggled the hold button from the native UI.
    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        delegate?
            .callKitManager(
                self,
                didSetHeld: action.callUUID.uuidString.lowercased(),
                onHold: action.isOnHold)
        action.fulfill()
    }

    /// An outgoing call was started.
    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        delegate?.callKitManager(self, didStartOutgoingCall: action.callUUID.uuidString.lowercased())
        action.fulfill()
    }

    /// The user toggled the mute button from the native UI.
    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        delegate?
            .callKitManager(
                self,
                didSetMuted: action.callUUID.uuidString.lowercased(),
                muted: action.isMuted)
        action.fulfill()
    }

    /// The system has activated the audio session for the call.
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        delegate?.callKitManager(self, didActivateAudioSession: audioSession)
    }

    /// The system has deactivated the audio session.
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        delegate?.callKitManager(self, didDeactivateAudioSession: audioSession)
    }
}
