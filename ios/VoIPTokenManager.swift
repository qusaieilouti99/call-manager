import Foundation
import PushKit
import OSLog
import ChatSharedDataManager

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

    // MARK: - Helper Methods

    /// Gets the bundle identifier from Info.plist
    private func getBundleIdentifier() -> String {
        return Bundle.main.bundleIdentifier ?? "unknown.bundle.id"
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

        // *** WATCHDOG TIMER ***
        let completionTimeout = DispatchWorkItem {
            self.logger.error("⚠️ VoIP push handling timed out. Forcing completion.")
            completion()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: completionTimeout)

        // *** USE YOUR OLD FLEXIBLE PAYLOAD PARSING LOGIC ***
        let full = payload.dictionaryPayload
        logger.info("🔔 VoIP push – full payload keys: \(full.keys)")

        var userInfo: [AnyHashable: Any]?

        // Check for aps["data"] as JSON string first
        if let aps = full["aps"] as? [AnyHashable: Any],
           let dataString = aps["data"] as? String,
           let data = dataString.data(using: .utf8) {
            logger.info("🔍 found aps[\"data\"] string (length=\(dataString.count))")
            do {
                let obj = try JSONSerialization.jsonObject(with: data, options: [])
                if let nestedDict = obj as? [AnyHashable: Any] {
                    let keyList = nestedDict.keys.map { "\($0)" }
                    logger.info("🔍 parsed nested payload keys: \(keyList)")
                    userInfo = nestedDict
                } else {
                    logger.error("❌ parsed aps[\"data\"] but it's not a dictionary")
                }
            } catch {
                logger.error("❌ failed to JSON-parse aps[\"data\"]: \(error.localizedDescription)")
            }
        }

        // Fallback to top-level "data" dictionary
        if userInfo == nil, let topData = full["data"] as? [AnyHashable: Any] {
            logger.info("🔍 found top-level \"data\" dictionary – keys: \(topData.keys)")
            userInfo = topData
        }

        // Fallback to top-level "payload" dictionary
        if userInfo == nil, let wrap = full["payload"] as? [AnyHashable: Any] {
            logger.info("🔍 found top-level \"payload\" dictionary – keys: \(wrap.keys)")
            userInfo = wrap
        }

        // NEW: Also check for your "custom_payload" structure
        if userInfo == nil, let customPayload = full["custom_payload"] as? [AnyHashable: Any] {
            logger.info("🔍 found top-level \"custom_payload\" dictionary – keys: \(customPayload.keys)")
            userInfo = customPayload
        }

        guard let info = userInfo else {
            logger.error("❌ Invalid payload format – no nested info found, full.keys: \(full.keys)")
            completionTimeout.cancel()
            completion()
            return
        }

        logger.info("✅ using custom payload keys: \(info.keys)")

        guard let callId = info["callId"] as? String,
              let callType = info["callType"] as? String else {
            logger.error("❌ Missing required fields in VoIP payload: callId, callType. Available keys: \(info.keys)")
            completionTimeout.cancel()
            completion()
            return
        }

        // Extract username and fallback displayName
        let username = info["username"] as? String
        let fallbackDisplayName = info["name"] as? String ?? "Unknown Caller"

        // Use the more flexible field name checking from your old version
        let pictureUrl = info["pictureUrl"] as? String ?? info["picture"] as? String
        let metadata = info["metadata"] as? String ?? info["data"] as? String

        // Determine the display name to use
        var displayName = fallbackDisplayName
        let bundleId = getBundleIdentifier()

        if let username = username {
            logger.info("📞 Looking up contact for username: \(username)")

            // Try to get contact information
            if let contact = ChatSharedDataManager.shared.getContact(byUsername: username, hostAppBundleId: bundleId) {
                if !contact.name.isEmpty {
                    displayName = contact.name
                    logger.info("📞 Using contact name: \(contact.name) for username: \(username)")
                } else {
                    logger.info("📞 Contact found but name is empty, using fallback: \(fallbackDisplayName)")
                }
            } else {
                logger.info("📞 Contact not found for username: \(username), using fallback: \(fallbackDisplayName)")
            }
        } else {
            logger.info("📞 No username provided, using fallback display name: \(fallbackDisplayName)")
        }

        logger.info("📞 Reporting incoming call from VoIP push: \(callId) with display name: \(displayName)")

        // Report to CallEngine - CRITICAL: Call completion inside the completion handler
        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            metadata: metadata
        ) { success in
            self.logger.info("📞 CallKit report from VoIP push completed. Success: \(success)")
            completionTimeout.cancel()

            // IMPORTANT: Call completion handler here, not outside
            completion()
        }
    }
}
