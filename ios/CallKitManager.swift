import Foundation
import CallKit
import OSLog
import AVFoundation

protocol CallKitManagerDelegate: AnyObject {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String)
    func callKitManager(_ manager: CallKitManager, didEndCall callId: String)
    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool)
    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool)
    func callKitManager(_ manager: CallKitManager, didStartOutgoingCall callId: String)
    func callKitManager(_ manager: CallKitManager, didActivateAudioSession session: AVAudioSession)
    func callKitManager(_ manager: CallKitManager, didDeactivateAudioSession session: AVAudioSession)
}

class CallKitManager {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallKitManager")
    internal let provider: CXProvider
    internal let callController = CXCallController()
    private weak var delegate: CallKitManagerDelegate?

    private var activeCallIds: Set<String> = []

    init(delegate: CallKitManagerDelegate) {
        self.delegate = delegate

        logger.info("📲 CallKitManager initializing...")

        let configuration = CXProviderConfiguration()
        // Line 31 (previously): This assignment is correct for CXProviderConfiguration.
        // If this error persists, it is a project setup/caching issue or Xcode version specific bug.
       // configuration.localizedName = "PingMe Call"
        configuration.supportsVideo = true
        configuration.maximumCallsPerCallGroup = 3
        configuration.maximumCallGroups = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = true

        // Line 40 (previously): This requires iOS 10.0+ deployment target.
        if let ringtonePath = Bundle.main.path(forResource: "ringtone", ofType: "caf") {
           // configuration.ringtoneSoundURL = URL(fileURLWithPath: ringtonePath)
            logger.info("📲 Custom ringtone configured: \(ringtonePath)")
        } else {
            logger.warning("📲 ⚠️ Custom ringtone 'ringtone.caf' not found, using default system ringtone.")
        }

        provider = CXProvider(configuration: configuration)
        provider.setDelegate(CallKitProviderDelegate(manager: self), queue: nil)
        logger.info("📲 ✅ CallKitManager initialized successfully")
    }

    // MARK: - Reporting Calls to CallKit

    func reportIncomingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        logger.info("📲 Reporting incoming call: \(callInfo.callId)")

        guard let callUUID = UUID(uuidString: callInfo.callId) else {
            let error = NSError(domain: "CallKitManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid UUID string for callId: \(callInfo.callId)"])
            logger.error("📲 ❌ Invalid UUID for callId: \(callInfo.callId)")
            completion(error)
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callInfo.displayName)
        update.localizedCallerName = callInfo.displayName
        update.hasVideo = callInfo.callType == "Video"
        update.supportsGrouping = true
        update.supportsUngrouping = false
        update.supportsHolding = true
        update.supportsDTMF = true

        logger.info("📲 Call update configured: name=\(callInfo.displayName), hasVideo=\(update.hasVideo)")

        activeCallIds.insert(callInfo.callId)
        logger.info("📲 Added to active calls. Total active: \(self.activeCallIds.count)")

        provider.reportNewIncomingCall(with: callUUID, update: update) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📲 ❌ Failed to report incoming call: \(error.localizedDescription)")
                self.activeCallIds.remove(callInfo.callId)
            } else {
                self.logger.info("📲 ✅ Successfully reported incoming call: \(callInfo.callId)")
            }
            completion(error)
        }
    }

    func startOutgoingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        logger.info("📲 Starting outgoing call: \(callInfo.callId)")

        guard let callUUID = UUID(uuidString: callInfo.callId) else {
            let error = NSError(domain: "CallKitManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid UUID string for callId: \(callInfo.callId)"])
            logger.error("📲 ❌ Invalid UUID for callId: \(callInfo.callId)")
            completion(error)
            return
        }

        let handle = CXHandle(type: .generic, value: callInfo.displayName)
        let startCallAction = CXStartCallAction(call: callUUID, handle: handle)
        startCallAction.isVideo = callInfo.callType == "Video"

        logger.info("📲 Start call action configured: name=\(callInfo.displayName), isVideo=\(startCallAction.isVideo)")

        let transaction = CXTransaction(action: startCallAction)

        activeCallIds.insert(callInfo.callId)
        logger.info("📲 Added to active calls. Total active: \(self.activeCallIds.count)")

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📲 ❌ Failed to request start outgoing call: \(error.localizedDescription)")
                self.activeCallIds.remove(callInfo.callId)
            } else {
                self.logger.info("📲 ✅ Successfully requested start outgoing call: \(callInfo.callId)")
            }
            completion(error)
        }
    }

    func endCall(callId: String) {
        logger.info("📲 Requesting to end call: \(callId)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot request end call for \(callId) - not found in CallKit active calls or invalid UUID")
            return
        }

        let endCallAction = CXEndCallAction(call: callUUID)
        let transaction = CXTransaction(action: endCallAction)

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📲 ❌ Failed to request end call: \(error.localizedDescription)")
            } else {
                self.logger.info("📲 ✅ Successfully requested end call: \(callId)")
            }
        }
    }

    func setCallOnHold(callId: String, onHold: Bool) {
        logger.info("📲 Requesting to set call \(callId) hold state to: \(onHold)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot request set hold for call \(callId) - not found in CallKit active calls or invalid UUID")
            return
        }

        let holdAction = CXSetHeldCallAction(call: callUUID, onHold: onHold)
        let transaction = CXTransaction(action: holdAction)

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📲 ❌ Failed to request set call on hold: \(error.localizedDescription)")
            } else {
                self.logger.info("📲 ✅ Successfully requested set call \(callId) hold state to: \(onHold)")
            }
        }
    }

    func setMuted(callId: String, muted: Bool) {
        logger.info("📲 Requesting to set call \(callId) mute state to: \(muted)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot request set mute for call \(callId) - not found in CallKit active calls or invalid UUID")
            return
        }

        let muteAction = CXSetMutedCallAction(call: callUUID, muted: muted)
        let transaction = CXTransaction(action: muteAction)

        callController.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.logger.error("📲 ❌ Failed to request set call mute: \(error.localizedDescription)")
            } else {
                self.logger.info("📲 ✅ Successfully requested set call \(callId) mute state to: \(muted)")
            }
        }
    }

    func updateCall(callId: String, displayName: String) {
        logger.info("📲 Updating call \(callId) display name to: \(displayName)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot update call \(callId) - not found in CallKit active calls or invalid UUID")
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: displayName)
        update.localizedCallerName = displayName

        provider.reportCall(with: callUUID, updated: update)
        logger.info("📲 ✅ Updated call \(callId) display name to: \(displayName)")
    }

    // MARK: - CallKitProviderDelegate Callbacks (Internal Handlers)

    fileprivate func handleProviderReset() {
        logger.info("📲 🔄 Provider did reset - clearing all active calls tracked by CallKitManager")
        self.activeCallIds.removeAll()
    }

    fileprivate func handleAnswerCall(action: CXAnswerCallAction) {
        let callId = action.callUUID.uuidString
        logger.info("📲 📞 Provider perform answer call action for \(callId)")
        delegate?.callKitManager(self, didAnswerCall: callId)
        action.fulfill()
    }

    fileprivate func handleEndCall(action: CXEndCallAction) {
        let callId = action.callUUID.uuidString
        logger.info("📲 📞 Provider perform end call action for \(callId)")
        activeCallIds.remove(callId)
        delegate?.callKitManager(self, didEndCall: callId)
        action.fulfill()
    }

    fileprivate func handleSetHeld(action: CXSetHeldCallAction) {
        let callId = action.callUUID.uuidString
        logger.info("📲 📞 Provider perform set held call action for \(callId): \(action.isOnHold)")
        delegate?.callKitManager(self, didSetHeld: callId, onHold: action.isOnHold)
        action.fulfill()
    }

    fileprivate func handleSetMuted(action: CXSetMutedCallAction) {
        let callId = action.callUUID.uuidString
        logger.info("📲 📞 Provider perform set muted call action for \(callId): \(action.isMuted)")
        // This line is correct with `muted: action.isMuted`.
        // If "Incorrect argument label" persists, it implies the protocol's definition
        // in your project doesn't match the one I'm using.
        delegate?.callKitManager(self, didSetMuted: callId, muted: action.isMuted)
        action.fulfill()
    }

    fileprivate func handleStartCall(action: CXStartCallAction) {
        let callId = action.callUUID.uuidString
        logger.info("📲 📞 Provider perform start call action for \(callId)")
        delegate?.callKitManager(self, didStartOutgoingCall: callId)
        action.fulfill()
    }

    fileprivate func handleAudioSessionActivated(audioSession: AVAudioSession) {
        logger.info("📲 🔊 Provider did activate audio session")
        delegate?.callKitManager(self, didActivateAudioSession: audioSession)
    }

    fileprivate func handleAudioSessionDeactivated(audioSession: AVAudioSession) {
        logger.info("📲 🔊 Provider did deactivate audio session")
        delegate?.callKitManager(self, didDeactivateAudioSession: audioSession)
    }
}

private class CallKitProviderDelegate: NSObject, CXProviderDelegate {
    private weak var manager: CallKitManager?

    init(manager: CallKitManager) {
        self.manager = manager
        super.init()
    }

    func providerDidReset(_ provider: CXProvider) {
        manager?.handleProviderReset()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        manager?.handleAnswerCall(action: action)
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        manager?.handleEndCall(action: action)
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        manager?.handleSetHeld(action: action)
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        manager?.handleSetMuted(action: action)
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        manager?.handleStartCall(action: action)
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        manager?.handleAudioSessionActivated(audioSession: audioSession)
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        manager?.handleAudioSessionDeactivated(audioSession: audioSession)
    }
}
