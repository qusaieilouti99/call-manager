import Foundation
import PushKit
import OSLog

/// Manages the VoIP push token lifecycle using Apple's PushKit framework.
/// This class is a singleton responsible for registering for VoIP pushes, receiving the token,
/// and handling incoming push payloads that represent new calls.
class VoIPTokenManager: NSObject, PKPushRegistryDelegate {
    // MARK: - Singleton and Properties

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
        logger.info("VoIPTokenManager singleton created.")
    }

    // MARK: - Public Methods

    /// Configures the PushKit registry. This should be called once when the app's call engine is initialized.
    func setupPushKit() {
        guard pushRegistry == nil else {
            logger.info("PushKit already set up. Ignoring.")
            return
        }
        pushRegistry = PKPushRegistry(queue: .main)
        pushRegistry?.delegate = self
        pushRegistry?.desiredPushTypes = [.voIP]
        logger.info("PushKit registry configured.")
    }

    /// Registers a listener (typically from the JS bridge) to receive the VoIP token.
    func registerTokenListener(_ listener: @escaping (String) -> Void) {
        logger.info("Registering VoIP token listener.")
        tokenListener = listener
        // If the token has already been received, provide it to the new listener immediately.
        if let t = cachedToken {
            logger.info("Providing cached VoIP token to new listener.")
            listener(t)
        }
    }

    /// Unregisters the token listener.
    func unregisterTokenListener() {
        logger.info("Unregistering VoIP token listener.")
        tokenListener = nil
    }

    // MARK: - PKPushRegistryDelegate

    /// Called by the system when a new VoIP push token is available or has been updated.
    func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        // Convert the token data to a hex string for transmission.
        let token = pushCredentials.token
            .map { String(format: "%02.2hhx", $0) }
            .joined()
        logger.info("VoIP token updated.")
        cachedToken = token
        tokenListener?(token)
    }

    /// Called by the system when the VoIP push token has been invalidated.
    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        logger.warning("VoIP token invalidated.")
        cachedToken = nil
        tokenListener?("")
    }

    /// This is the entry point for an incoming VoIP call when the app is in the background or killed.
    /// It is CRITICAL that the `completion` handler is called in all code paths to avoid the
    /// system terminating the app.
    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        logger.info("🔔 Received VoIP Push")

        // Initialize if not already initialized
        CallEngine.shared.initialize()

        // *** THIS IS THE WATCHDOG TIMER ***
        // It creates a failsafe that will call the completion handler after 10 seconds
        // if our main logic hangs or fails silently, preventing the OS from killing the app.
        let completionTimeout = DispatchWorkItem {
            self.logger.error("⚠️ VoIP push handling timed out. Forcing completion.")
            completion()
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: completionTimeout)

        // The payload is expected to have a specific structure.
        // Using a key like "custom_payload" or "data" is common.
        guard let info = payload.dictionaryPayload["custom_payload"] as? [AnyHashable: Any] else {
            logger.error("❌ Invalid payload format or missing 'custom_payload' dictionary.")
            completionTimeout.cancel() // Defuse the timer
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
            completionTimeout.cancel() // Defuse the timer
            completion()
            return
        }

        let pictureUrl = info["pictureUrl"] as? String
        let metadata = info["metadata"] as? String

        logger.info("📞 Reporting incoming call from VoIP push: \(callId)")

        // Delegate the call reporting to the CallEngine.
        // The CallEngine is self-initializing, making this call safe.
        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            metadata: metadata
        ) { success in
            self.logger.info("📞 CallKit report from VoIP push completed. Success: \(success)")
            // This is the crucial call to satisfy the PushKit watchdog.
            completionTimeout.cancel() // Defuse the timer
            completion()
        }
    }
}
