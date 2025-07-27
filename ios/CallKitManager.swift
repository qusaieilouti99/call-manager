import Foundation
import CallKit
import OSLog

protocol CallKitManagerDelegate: AnyObject {
    func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String)
    func callKitManager(_ manager: CallKitManager, didEndCall callId: String)
    func callKitManager(_ manager: CallKitManager, didSetHeld callId: String, onHold: Bool)
    func callKitManager(_ manager: CallKitManager, didSetMuted callId: String, muted: Bool)
}

class CallKitManager: NSObject {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallKitManager")
    private let provider: CXProvider
    private let callController = CXCallController()
    private weak var delegate: CallKitManagerDelegate?

    private var activeCallIds: Set<String> = []

    init(delegate: CallKitManagerDelegate) {
        self.delegate = delegate

        logger.info("📲 CallKitManager initializing...")

        let configuration = CXProviderConfiguration()
        configuration.localizedName = "PingMe Call"
        configuration.supportsVideo = true
        configuration.maximumCallsPerCallGroup = 3
        configuration.maximumCallGroups = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = true

        // Audio configuration
        if let ringtonePath = Bundle.main.path(forResource: "ringtone", ofType: "caf") {
            configuration.ringtoneSoundURL = URL(fileURLWithPath: ringtonePath)
            logger.info("📲 Custom ringtone configured")
        } else {
            logger.info("📲 Using default ringtone")
        }

        provider = CXProvider(configuration: configuration)

        super.init()

        provider.setDelegate(self, queue: nil)
        logger.info("📲 ✅ CallKitManager initialized successfully")
    }

    // MARK: - Public Methods
    func reportIncomingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        logger.info("📲 Reporting incoming call: \(callInfo.callId)")

        guard let callUUID = UUID(uuidString: callInfo.callId) else {
            let error = NSError(domain: "CallKitManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid UUID: \(callInfo.callId)"])
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
        update.supportsDTMF = false

        logger.info("📲 Call update configured: name=\(callInfo.displayName), hasVideo=\(update.hasVideo)")

        activeCallIds.insert(callInfo.callId)
        logger.info("📲 Added to active calls. Total active: \(activeCallIds.count)")

        provider.reportNewIncomingCall(with: callUUID, update: update) { [weak self] error in
            if let error = error {
                self?.logger.error("📲 ❌ Failed to report incoming call: \(error.localizedDescription)")
                self?.activeCallIds.remove(callInfo.callId)
            } else {
                self?.logger.info("📲 ✅ Successfully reported incoming call: \(callInfo.callId)")
            }
            completion(error)
        }
    }

    func startOutgoingCall(callInfo: CallInfo, completion: @escaping (Error?) -> Void) {
        logger.info("📲 Starting outgoing call: \(callInfo.callId)")

        guard let callUUID = UUID(uuidString: callInfo.callId) else {
            let error = NSError(domain: "CallKitManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid UUID: \(callInfo.callId)"])
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
        logger.info("📲 Added to active calls. Total active: \(activeCallIds.count)")

        callController.request(transaction) { [weak self] error in
            if let error = error {
                self?.logger.error("📲 ❌ Failed to start outgoing call: \(error.localizedDescription)")
                self?.activeCallIds.remove(callInfo.callId)
            } else {
                self?.logger.info("📲 ✅ Successfully started outgoing call: \(callInfo.callId)")
            }
            completion(error)
        }
    }

    func endCall(callId: String) {
        logger.info("📲 Ending call: \(callId)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot end call \(callId) - not found or invalid UUID")
            return
        }

        let endCallAction = CXEndCallAction(call: callUUID)
        let transaction = CXTransaction(action: endCallAction)

        callController.request(transaction) { [weak self] error in
            if let error = error {
                self?.logger.error("📲 ❌ Failed to end call: \(error.localizedDescription)")
            } else {
                self?.logger.info("📲 ✅ Successfully ended call: \(callId)")
                self?.activeCallIds.remove(callId)
            }
        }
    }

    func setCallOnHold(callId: String, onHold: Bool) {
        logger.info("📲 Setting call \(callId) hold state to: \(onHold)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot set hold for call \(callId) - not found or invalid UUID")
            return
        }

        let holdAction = CXSetHeldCallAction(call: callUUID, onHold: onHold)
        let transaction = CXTransaction(action: holdAction)

        callController.request(transaction) { [weak self] error in
            if let error = error {
                self?.logger.error("📲 ❌ Failed to set call on hold: \(error.localizedDescription)")
            } else {
                self?.logger.info("📲 ✅ Successfully set call \(callId) hold state to: \(onHold)")
            }
        }
    }

    func updateCall(callId: String, displayName: String) {
        logger.info("📲 Updating call \(callId) display name to: \(displayName)")

        guard let callUUID = UUID(uuidString: callId),
              activeCallIds.contains(callId) else {
            logger.warning("📲 ⚠️ Cannot update call \(callId) - not found or invalid UUID")
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: displayName)
        update.localizedCallerName = displayName

        provider.reportCall(with: callUUID, updated: update)
        logger.info("📲 ✅ Updated call \(callId) display name to: \(displayName)")
    }
}

// MARK: - CXProviderDelegate
extension CallKitManager: CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) {
        logger.info("📲 🔄 Provider did reset - clearing all active calls")
        activeCallIds.removeAll()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        logger.info("📲 📞 Provider perform answer call action")
        let callId = action.callUUID.uuidString
        logger.info("📲 Answering call: \(callId)")
        delegate?.callKitManager(self, didAnswerCall: callId)
        action.fulfill()
        logger.info("📲 ✅ Answer action fulfilled")
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        logger.info("📲 📞 Provider perform end call action")
        let callId = action.callUUID.uuidString
        logger.info("📲 Ending call: \(callId)")
        activeCallIds.remove(callId)
        delegate?.callKitManager(self, didEndCall: callId)
        action.fulfill()
        logger.info("📲 ✅ End call action fulfilled")
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        logger.info("📲 📞 Provider perform set held call action: \(action.isOnHold)")
        let callId = action.callUUID.uuidString
        logger.info("📲 Setting call \(callId) hold state to: \(action.isOnHold)")
        delegate?.callKitManager(self, didSetHeld: callId, onHold: action.isOnHold)
        action.fulfill()
        logger.info("📲 ✅ Set held action fulfilled")
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        logger.info("📲 📞 Provider perform set muted call action: \(action.isMuted)")
        let callId = action.callUUID.uuidString
        logger.info("📲 Setting call \(callId) mute state to: \(action.isMuted)")
        delegate?.callKitManager(self, didSetMuted: callId, muted: action.isMuted)
        action.fulfill()
        logger.info("📲 ✅ Set muted action fulfilled")
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        logger.info("📲 📞 Provider perform start call action")
        logger.info("📲 Starting outgoing call with handle: \(action.handle.value)")
        action.fulfill()
        logger.info("📲 ✅ Start call action fulfilled")
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        logger.info("📲 🔊 Provider did activate audio session")
        logger.info("📲 Audio session category: \(audioSession.category.rawValue)")
        logger.info("📲 Audio session mode: \(audioSession.mode.rawValue)")
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        logger.info("📲 🔊 Provider did deactivate audio session")
    }
}
