import Foundation
import PushKit
import OSLog

class VoIPTokenManager: NSObject {
    static let shared = VoIPTokenManager()

    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "VoIPTokenManager")
    private var pushRegistry: PKPushRegistry?
    private var tokenListener: ((String) -> Void)?
    private var cachedToken: String?

    private override init() {
        super.init()
        logger.info("🔑 VoIPTokenManager initializing...")
        setupPushKit()
    }

    private func setupPushKit() {
        logger.info("🔑 Setting up PushKit registry...")
        pushRegistry = PKPushRegistry(queue: DispatchQueue.main)
        pushRegistry?.delegate = self
        pushRegistry?.desiredPushTypes = [.voIP]
        logger.info("🔑 PushKit registry setup completed successfully")
    }

    func registerTokenListener(_ listener: @escaping (String) -> Void) {
        logger.info("🔑 Registering VoIP token listener...")
        tokenListener = listener

        // If we have a cached token, immediately return it
        if let cachedToken = cachedToken {
            logger.info("🔑 Returning cached VoIP token: \(cachedToken.prefix(10))...")
            listener(cachedToken)
        } else {
            logger.info("🔑 No cached token available, listener will be called when token is received")
        }
    }

    func unregisterTokenListener() {
        logger.info("🔑 Unregistering VoIP token listener")
        tokenListener = nil
    }

    private func handleIncomingCall(payload: [String: Any]) {
        logger.info("📞 Handling incoming call from VoIP push...")
        logger.debug("📞 Incoming call payload: \(payload)")

        guard let callId = payload["callId"] as? String else {
            logger.error("📞 ❌ Missing callId in incoming call payload")
            return
        }

        guard let callType = payload["callType"] as? String else {
            logger.error("📞 ❌ Missing callType in incoming call payload")
            return
        }

        guard let displayName = payload["displayName"] as? String else {
            logger.error("📞 ❌ Missing displayName in incoming call payload")
            return
        }

        let pictureUrl = payload["pictureUrl"] as? String
        let metadata = payload["metadata"] as? String

        logger.info("📞 Incoming call details: callId=\(callId), type=\(callType), name=\(displayName)")
        if let pictureUrl = pictureUrl {
            logger.info("📞 Picture URL: \(pictureUrl)")
        }
        if let metadata = metadata {
            logger.info("📞 Metadata: \(metadata)")
        }

        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            metadata: metadata
        )

        logger.info("📞 ✅ Incoming call reported to CallEngine")
    }

    private func handleEndCall(payload: [String: Any]) {
        logger.info("📞 Handling end call from VoIP push...")
        logger.debug("📞 End call payload: \(payload)")

        guard let callId = payload["callId"] as? String else {
            logger.error("📞 ❌ Missing callId in end call payload")
            return
        }

        logger.info("📞 Ending call: \(callId)")
        CallEngine.shared.endCall(callId: callId)
        logger.info("📞 ✅ End call reported to CallEngine")
    }
}

// MARK: - PKPushRegistryDelegate
extension VoIPTokenManager: PKPushRegistryDelegate {
    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        logger.info("🔑 VoIP push credentials updated for type: \(type)")

        let deviceToken = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        logger.info("🔑 ✅ VoIP Device Token received: \(deviceToken.prefix(10))...\(deviceToken.suffix(10))")

        // Cache the token
        cachedToken = deviceToken
        logger.info("🔑 Token cached successfully")

        // Notify the listener about the new token
        if let tokenListener = tokenListener {
            logger.info("🔑 Calling token listener with new token")
            tokenListener(deviceToken)
        } else {
            logger.info("🔑 No token listener registered, token will be returned when listener is added")
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        logger.info("🔔 Received VoIP push notification")
        logger.debug("🔔 Full payload: \(payload.dictionaryPayload)")

        let payloadDict = payload.dictionaryPayload

        // Handle different notification types
        if let notificationType = payloadDict["type"] as? String {
            logger.info("🔔 Notification type: \(notificationType)")
            switch notificationType {
            case "Call":
                logger.info("🔔 Processing incoming call notification...")
                handleIncomingCall(payload: payloadDict)
            case "EndCall":
                logger.info("🔔 Processing end call notification...")
                handleEndCall(payload: payloadDict)
            default:
                logger.warning("🔔 ⚠️ Unknown VoIP notification type: \(notificationType)")
            }
        } else {
            logger.info("🔔 No type specified, assuming incoming call...")
            handleIncomingCall(payload: payloadDict)
        }

        logger.info("🔔 ✅ VoIP push notification processing completed")
        completion()
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        logger.warning("🔑 ⚠️ VoIP push token invalidated for type: \(type)")
        cachedToken = nil
        if let tokenListener = tokenListener {
            logger.info("🔑 Notifying listener about token invalidation")
            tokenListener("")
        }
    }
}
