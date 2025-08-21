import Foundation
import PushKit
import OSLog

class VoIPTokenManager: NSObject, PKPushRegistryDelegate {
    static let shared = VoIPTokenManager()
    private let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "VoIPTokenManager"
    )
    private var pushRegistry: PKPushRegistry?
    private var tokenListener: ((String) -> Void)?
    private var cachedToken: String?

    private override init() {
        super.init()
        logger.info("VoIPTokenManager init")
    }

    func setupPushKit() {
        guard pushRegistry == nil else {
            logger.info("PushKit already setup")
            return
        }
        pushRegistry = PKPushRegistry(queue: .main)
        pushRegistry?.delegate = self
        pushRegistry?.desiredPushTypes = [.voIP]
        logger.info("PushKit registry configured")
    }

    func registerTokenListener(_ listener: @escaping (String) -> Void) {
        logger.info("registerTokenListener")
        tokenListener = listener
        if let t = cachedToken {
            logger.info("returning cached token")
            listener(t)
        }
    }

    func unregisterTokenListener() {
        logger.info("unregisterTokenListener")
        tokenListener = nil
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        let token = pushCredentials.token
            .map { String(format: "%02.2hhx", $0) }
            .joined()
        logger.info("VoIP token updated.")
        cachedToken = token
        tokenListener?(token)
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        logger.warning("VoIP token invalidated.")
        cachedToken = nil
        tokenListener?("")
    }

    // MARK: - PKPushRegistryDelegate
    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {

        logger.info("🔔 Received VoIP Push")

        guard let info = payload.dictionaryPayload["custom_payload"] as? [AnyHashable: Any] else {
            logger.error("❌ Invalid payload format or missing 'custom_payload' dictionary.")
            completion()
            return
        }

        guard let callId = info["callId"] as? String,
            let callType = info["callType"] as? String,
            let displayName = info["name"] as? String
        else {
            logger
                .error(
                    "❌ Missing required fields in VoIP payload: callId, callType, or name."
                )
            completion()
            return
        }

        let pictureUrl = info["pictureUrl"] as? String
        let metadata = info["metadata"] as? String

        logger.info("📞 Reporting incoming call from VoIP push: \(callId)")
        // *** FIX: Updated to match the corrected CallEngine signature ***
        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            metadata: metadata
        ) { success in
            self.logger.info("📞 CallKit report from VoIP push completed. Success: \(success)")
            completion()
        }
    }
}
