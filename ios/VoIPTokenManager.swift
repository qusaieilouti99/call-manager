import Foundation
import PushKit
import OSLog

class VoIPTokenManager: NSObject, PKPushRegistryDelegate {
  static let shared = VoIPTokenManager()
  private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager",
                              category: "CallManager")
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
      logger.info("returning cached token.len=\(t.count)")
      listener(t)
    }
  }

  func unregisterTokenListener() {
    logger.info("unregisterTokenListener")
    tokenListener = nil
  }

  func pushRegistry(_ registry: PKPushRegistry,
                    didUpdate pushCredentials: PKPushCredentials,
                    for type: PKPushType) {
    let token = pushCredentials.token
      .map { String(format: "%02.2hhx", $0) }
      .joined()
    logger.info("didUpdate push token.len=\(token.count)")
    cachedToken = token
    tokenListener?(token)
  }

  func pushRegistry(_ registry: PKPushRegistry,
                    didInvalidatePushTokenFor type: PKPushType) {
    logger.warning("didInvalidatePushToken")
    cachedToken = nil
    tokenListener?("")
  }

    // MARK: - PKPushRegistryDelegate
    func pushRegistry(_ registry: PKPushRegistry,
                      didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType,
                      completion: @escaping () -> Void) {

        logger.info("🔔 didReceiveIncomingPush started")

        // Add timeout protection
        let completionTimeout = DispatchWorkItem {
            self.logger.error("⚠️ VoIP push handling timed out")
            completion()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: completionTimeout)

        // Extract payload (keep your existing logic)
        let full = payload.dictionaryPayload
        logger.info("🔔 didReceiveIncomingPush – full payload keys: \(full.keys)")

        var userInfo: [AnyHashable: Any]?

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

        if userInfo == nil, let topData = full["data"] as? [AnyHashable: Any] {
            logger.info("🔍 found top-level \"data\" dictionary – keys: \(topData.keys)")
            userInfo = topData
        }

        if userInfo == nil, let wrap = full["payload"] as? [AnyHashable: Any] {
            logger.info("🔍 found top-level \"payload\" dictionary – keys: \(wrap.keys)")
            userInfo = wrap
        }

        guard let info = userInfo else {
            logger.error("❌ invalid payload – no nested info found, full.keys: \(full.keys)")
            completionTimeout.cancel()
            completion()
            return
        }
        logger.info("✅ using custom payload keys: \(info.keys)")

        guard let callId = info["callId"] as? String,
              let callType = info["callType"] as? String,
              let displayName = info["name"] as? String else {
            logger.error("❌ missing one of: callId / callType / name in keys: \(info.keys)")
            completionTimeout.cancel()
            completion()
            return
        }

        let pictureUrl = info["pictureUrl"] as? String ?? info["picture"] as? String
        let metadata = info["metadata"] as? String ?? info["data"] as? String

        // REMOVED: EndCall VoIP push handling (server won't send these anymore)

        // Handle ONLY incoming calls via VoIP push
        logger.info("📞 VoIP push → IncomingCall \(callId), displayName=\(displayName)")
        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: displayName,
            pictureUrl: pictureUrl,
            metadata: metadata
        ) { [weak self] success in
            self?.logger.info("📞 CallKit report result: \(success) for \(callId)")
            completionTimeout.cancel()
            completion()
        }

        logger.info("🔔 didReceiveIncomingPush dispatch completed")
    }
}
