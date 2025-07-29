import Foundation
import CallKit
import AVFoundation
import OSLog

protocol CallKitManagerDelegate: AnyObject {
  func callKitManager(_ manager: CallKitManager, didAnswerCall callId: String)
  func callKitManager(_ manager: CallKitManager, didEndCall callId: String)
  func callKitManager(_ manager: CallKitManager,
                      didSetHeld callId: String,
                      onHold: Bool)
  func callKitManager(_ manager: CallKitManager,
                      didSetMuted callId: String,
                      muted: Bool)
  func callKitManager(_ manager: CallKitManager,
                      didStartOutgoingCall callId: String)
  func callKitManager(_ manager: CallKitManager,
                      didActivateAudioSession session: AVAudioSession)
  func callKitManager(_ manager: CallKitManager,
                      didDeactivateAudioSession session: AVAudioSession)
}

class CallKitManager: NSObject {
  private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager",
                              category: "CallManager")
  let provider: CXProvider
  let callController = CXCallController()
  weak var delegate: CallKitManagerDelegate?
  private var activeCallIds: Set<String> = []

  init(delegate: CallKitManagerDelegate) {
    self.delegate = delegate
    let config = CXProviderConfiguration()
    config.supportsVideo = true
    config.maximumCallsPerCallGroup = 3
    config.maximumCallGroups = 1
    config.supportedHandleTypes = [.phoneNumber, .generic]
    config.includesCallsInRecents = true
    config.ringtoneSound = "ringtone.caf" // put “ringtone.caf” in your bundle
    provider = CXProvider(configuration: config)
    super.init()
    provider.setDelegate(self, queue: nil)
    logger.info("CallKitManager init")
  }

  func reportIncomingCall(callInfo: CallInfo,
                          completion: @escaping (Error?) -> Void)
  {
    logger.info("reportIncomingCall: \(callInfo.callId)")
    guard let uuid = UUID(uuidString: callInfo.callId) else {
      let err = NSError(domain: "CallKitManager",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Invalid UUID"])
      completion(err)
      return
    }
    let update = CXCallUpdate()
    update.remoteHandle = CXHandle(type: .generic,
                                   value: callInfo.displayName)
    update.localizedCallerName = callInfo.displayName
    update.hasVideo = callInfo.callType == "Video"
    update.supportsHolding = true
    update.supportsGrouping = true
    update.supportsUngrouping = false
    update.supportsDTMF = true

    activeCallIds.insert(callInfo.callId)
    provider.reportNewIncomingCall(with: uuid, update: update) { error in
      if let e = error {
        self.logger.error("reportNewIncomingCall error: \(e.localizedDescription)")
        self.activeCallIds.remove(callInfo.callId)
      }
      completion(error)
    }
  }

  func startOutgoingCall(callInfo: CallInfo,
                         completion: @escaping (Error?) -> Void)
  {
    logger.info("startOutgoingCall: \(callInfo.callId)")
    guard let uuid = UUID(uuidString: callInfo.callId) else {
      let err = NSError(domain: "CallKitManager",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Invalid UUID"])
      completion(err)
      return
    }
    let handle = CXHandle(type: .generic,
                          value: callInfo.displayName)
    let action = CXStartCallAction(call: uuid, handle: handle)
    action.isVideo = callInfo.callType == "Video"
    let tx = CXTransaction(action: action)
    activeCallIds.insert(callInfo.callId)
    callController.request(tx) { error in
      if let e = error {
        self.logger.error("startCallAction error: \(e.localizedDescription)")
        self.activeCallIds.remove(callInfo.callId)
      }
      completion(error)
    }
  }

  func answerCall(callId: String) {
    logger.info("answerCall: \(callId)")
    guard let uuid = UUID(uuidString: callId) else { return }
    let action = CXAnswerCallAction(call: uuid)
    let tx = CXTransaction(action: action)
    callController.request(tx) { error in
      if let e = error {
        self.logger.error("answerCallAction error: \(e.localizedDescription)")
      }
    }
  }

  func endCall(callId: String) {
    logger.info("endCall: \(callId)")
    guard let uuid = UUID(uuidString: callId),
          activeCallIds.contains(callId)
    else { return }
    let action = CXEndCallAction(call: uuid)
    let tx = CXTransaction(action: action)
    callController.request(tx) { error in
      if let e = error {
        self.logger.error("endCallAction error: \(e.localizedDescription)")
      }
    }
  }

  func setCallOnHold(callId: String, onHold: Bool) {
    logger.info("setCallOnHold: \(callId), onHold=\(onHold)")
    guard let uuid = UUID(uuidString: callId),
          activeCallIds.contains(callId)
    else { return }
    let action = CXSetHeldCallAction(call: uuid, onHold: onHold)
    let tx = CXTransaction(action: action)
    callController.request(tx) { error in
      if let e = error {
        self.logger.error("setHeldAction error: \(e.localizedDescription)")
      }
    }
  }

  func setMuted(callId: String, muted: Bool) {
    logger.info("setMuted: \(callId), muted=\(muted)")
    guard let uuid = UUID(uuidString: callId),
          activeCallIds.contains(callId)
    else { return }
    let action = CXSetMutedCallAction(call: uuid, muted: muted)
    let tx = CXTransaction(action: action)
    callController.request(tx) { error in
      if let e = error {
        self.logger.error("setMutedAction error: \(e.localizedDescription)")
      }
    }
  }

  func updateCall(callId: String, displayName: String) {
    logger.info("updateCall: \(callId), \(displayName)")
    guard let uuid = UUID(uuidString: callId),
          activeCallIds.contains(callId)
    else { return }
    let update = CXCallUpdate()
    update.remoteHandle = CXHandle(type: .generic, value: displayName)
    update.localizedCallerName = displayName
    provider.reportCall(with: uuid, updated: update)
  }
}

extension CallKitManager: CXProviderDelegate {
  func providerDidReset(_ provider: CXProvider) {
    logger.info("providerDidReset")
    activeCallIds.removeAll()
  }

  func provider(_ provider: CXProvider,
                perform action: CXAnswerCallAction) {
    let id = action.callUUID.uuidString.lowercased()
    logger.info("provider perform answer: \(id)")
    delegate?.callKitManager(self, didAnswerCall: id)
    action.fulfill()
  }

  func provider(_ provider: CXProvider,
                perform action: CXEndCallAction) {
    let id = action.callUUID.uuidString.lowercased()
    logger.info("provider perform end: \(id)")
    activeCallIds.remove(id)
    delegate?.callKitManager(self, didEndCall: id)
    action.fulfill()
  }

  func provider(_ provider: CXProvider,
                perform action: CXSetHeldCallAction) {
    let id = action.callUUID.uuidString.lowercased()
    logger.info("provider perform setHeld: \(id), onHold=\(action.isOnHold)")
    delegate?.callKitManager(self, didSetHeld: id, onHold: action.isOnHold)
    action.fulfill()
  }

  func provider(_ provider: CXProvider,
                perform action: CXStartCallAction) {
    let id = action.callUUID.uuidString.lowercased()
    logger.info("provider perform start: \(id)")
    delegate?.callKitManager(self, didStartOutgoingCall: id)
    action.fulfill()
  }

  func provider(_ provider: CXProvider,
                perform action: CXSetMutedCallAction) {
    let id = action.callUUID.uuidString.lowercased()
    logger.info("provider perform setMuted: \(id), muted=\(action.isMuted)")
    delegate?.callKitManager(self, didSetMuted: id, muted: action.isMuted)
    action.fulfill()
  }

  func provider(_ provider: CXProvider,
                didActivate audioSession: AVAudioSession) {
    logger.info("provider didActivate audioSession")
    delegate?.callKitManager(self, didActivateAudioSession: audioSession)
  }

  func provider(_ provider: CXProvider,
                didDeactivate audioSession: AVAudioSession) {
    logger.info("provider didDeactivate audioSession")
    delegate?.callKitManager(self, didDeactivateAudioSession: audioSession)
  }
}
